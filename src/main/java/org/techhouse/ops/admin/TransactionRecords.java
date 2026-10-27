package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class TransactionRecords {
    private static final String COLL = Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME;
    private static final List<String> UNPUBLISHED_FIRST = List.of(AdminTransactionEntry.MARKER_PARTICIPANT,
            AdminTransactionEntry.MARKER_LOCAL_COMMIT);
    private static final Logger logger = Logger.logFor(TransactionRecords.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static Cache cache = IocContainer.get(Cache.class);
    private static final AdminBatchWrite.PkRows PK_ROWS = new AdminBatchWrite.PkRows() {
        @Override
        public PkIndexEntry get(String id) {
            return cache.getPkIndexTransaction(id);
        }

        @Override
        public void publish(PkIndexEntry row) {
            cache.putPkIndexTransaction(row);
        }

        @Override
        public void unpublish(Set<String> ids) {
            for (final var id : unpublishOrder(ids)) {
                cache.removePkIndexTransaction(id);
            }
        }
    };

    private TransactionRecords() {
    }

    public static void write(List<AdminTransactionEntry> inserts, List<String> deleteIds)
            throws IOException, InterruptedException {
        final var lines = new IdentityHashMap<AdminTransactionEntry, byte[]>();
        final var ops = new ArrayList<AdminTransactionEntry>();
        final var markers = new ArrayList<AdminTransactionEntry>();
        for (final var insert : inserts) {
            lines.put(insert, insert.encodeLine());
            (isSliceOp(insert.get_id()) ? ops : markers).add(insert);
        }
        final var chunks = AdminBatchWrite.chunksOf(ops, lines::get);
        final var last = chunks.removeLast();
        last.addAll(markers);
        final var landed = new ArrayList<String>();
        try {
            for (final var chunk : chunks) {
                writeRecord(chunk, List.of(), lines);
                chunk.forEach(op -> landed.add(op.get_id()));
            }
            writeRecord(last, deleteIds, lines);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            discardLanded(landed, failure);
            throw failure;
        }
    }

    private static void discardLanded(List<String> landed, Exception failure) {
        if (landed.isEmpty()) {
            return;
        }
        try {
            writeRecord(List.of(), landed, Map.of());
        } catch (InterruptedException cleanupFailure) {
            Thread.currentThread().interrupt();
            leftLanded(landed, failure, cleanupFailure);
        } catch (IOException | RuntimeException cleanupFailure) {
            leftLanded(landed, failure, cleanupFailure);
        }
    }

    private static void leftLanded(List<String> landed, Exception failure, Exception cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
        logger.warning("Could not take back " + landed.size() + " transaction op record(s) written before a failed"
                + " write; the startup orphan sweep removes them: " + cleanupFailure.getMessage());
    }

    private static boolean isSliceOp(String recordId) {
        final var separator = recordId.lastIndexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
        if (separator < 0 || separator == recordId.length() - 1) {
            return false;
        }
        for (var index = separator + 1; index < recordId.length(); index++) {
            if (!Character.isDigit(recordId.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static void writeRecord(List<AdminTransactionEntry> inserts, List<String> deleteIds,
            Map<AdminTransactionEntry, byte[]> lines) throws IOException, InterruptedException {
        AdminBatchWrite.write(COLL, PK_ROWS, inserts, lines::get, deleteIds);
    }

    public static List<String> unpublishOrder(Iterable<String> ids) {
        final var ordered = new LinkedHashSet<String>();
        for (final var suffix : UNPUBLISHED_FIRST) {
            for (final var id : ids) {
                if (id.endsWith(Globals.COLL_IDENTIFIER_SEPARATOR + suffix)) {
                    ordered.add(id);
                }
            }
        }
        for (final var id : ids) {
            ordered.add(id);
        }
        return List.copyOf(ordered);
    }
}
