package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.techhouse.bckg_ops.events.EntityRef;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class TransactionRecords {
    private static final String COLL = Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME;
    private static final List<String> UNPUBLISHED_FIRST = List.of(AdminTransactionEntry.MARKER_PARTICIPANT,
            AdminTransactionEntry.MARKER_LOCAL_COMMIT);
    private static final Logger logger = Logger.logFor(TransactionRecords.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static Cache cache = IocContainer.get(Cache.class);

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
        final var chunks = chunksOf(ops, lines);
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

    private static List<List<AdminTransactionEntry>> chunksOf(List<AdminTransactionEntry> ops,
            Map<AdminTransactionEntry, byte[]> lines) {
        final var chunks = new ArrayList<List<AdminTransactionEntry>>();
        var current = new ArrayList<AdminTransactionEntry>();
        var bytes = 0L;
        for (final var op : ops) {
            final var length = lines.get(op).length;
            if (!current.isEmpty() && bytes + length > Globals.TRANSACTION_OP_RECORD_BYTES) {
                chunks.add(current);
                current = new ArrayList<>();
                bytes = 0L;
            }
            current.add(op);
            bytes += length;
        }
        chunks.add(current);
        return chunks;
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
        locks.lock(Globals.ADMIN_DB_NAME, COLL);
        try {
            final var removed = resolve(deleteIds);
            final var deletes = new ArrayList<PkIndexEntry>();
            for (final var insert : inserts) {
                final var existing = cache.getPkIndexTransaction(insert.get_id());
                if (existing != null) {
                    removed.remove(insert.get_id());
                    deletes.add(existing);
                }
            }
            if (inserts.isEmpty() && removed.isEmpty()) {
                return;
            }
            deletes.addAll(removed.values());
            final var sizes = new int[inserts.size()];
            for (var index = 0; index < sizes.length; index++) {
                sizes[index] = lines.get(inserts.get(index)).length;
            }
            final var pages = cache.selectPagesForInsert(Globals.ADMIN_DB_NAME, COLL, sizes);
            for (var index = 0; index < sizes.length; index++) {
                inserts.get(index).setPage(pages[index]);
            }
            final var written = fs.writeBatch(Globals.ADMIN_DB_NAME, COLL, inserts, DbEntry::getPage, lines::get,
                    deletes);
            AdminPageHelper.recordLandedRefs(COLL, EventType.DELETED, refsOf(deletes));
            AdminPageHelper.recordLandedDelta(COLL, EventType.CREATED, List.copyOf(inserts));
            for (final var row : written) {
                cache.putPkIndexTransaction(row.getIndex());
            }
            for (final var id : unpublishOrder(removed.keySet())) {
                cache.removePkIndexTransaction(id);
            }
        } finally {
            locks.release(Globals.ADMIN_DB_NAME, COLL);
        }
    }

    private static Map<String, PkIndexEntry> resolve(List<String> deleteIds) {
        final var resolved = new LinkedHashMap<String, PkIndexEntry>();
        for (final var id : deleteIds) {
            final var pk = cache.getPkIndexTransaction(id);
            if (pk != null) {
                resolved.putIfAbsent(id, pk);
            }
        }
        return resolved;
    }

    private static List<EntityRef> refsOf(List<PkIndexEntry> rows) {
        final var refs = new ArrayList<EntityRef>(rows.size());
        for (final var row : rows) {
            refs.add(new EntityRef(row.getValue(), row.getPage(), row.getLength(), 0L));
        }
        return refs;
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
