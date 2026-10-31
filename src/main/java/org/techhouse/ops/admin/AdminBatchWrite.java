package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.techhouse.bckg_ops.events.EntityRef;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

final class AdminBatchWrite {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static Cache cache = IocContainer.get(Cache.class);

    interface PkRows {
        PkIndexEntry get(String id);

        void publish(PkIndexEntry row);

        void unpublish(Set<String> ids);
    }

    private AdminBatchWrite() {
    }

    static <T extends DbEntry> List<List<T>> chunksOf(List<T> entries, Function<? super T, byte[]> lines) {
        final var chunks = new ArrayList<List<T>>();
        var current = new ArrayList<T>();
        var bytes = 0L;
        for (final var entry : entries) {
            final var length = lines.apply(entry).length;
            if (!current.isEmpty() && bytes + length > Globals.TRANSACTION_OP_RECORD_BYTES) {
                chunks.add(current);
                current = new ArrayList<>();
                bytes = 0L;
            }
            current.add(entry);
            bytes += length;
        }
        chunks.add(current);
        return chunks;
    }

    static <T extends DbEntry> void writeInChunks(String collName, PkRows rows, List<T> inserts,
            Function<? super T, byte[]> lines, List<String> deleteIds) throws IOException, InterruptedException {
        final var chunks = chunksOf(inserts, lines);
        final var last = chunks.removeLast();
        for (final var chunk : chunks) {
            write(collName, rows, chunk, lines, List.of());
        }
        write(collName, rows, last, lines, chunks.isEmpty() ? deleteIds : notInsertedBy(inserts, deleteIds));
    }

    private static List<String> notInsertedBy(List<? extends DbEntry> inserts, List<String> deleteIds) {
        final var inserted = new HashSet<String>(inserts.size() * 2);
        for (final var insert : inserts) {
            inserted.add(insert.get_id());
        }
        final var stale = new ArrayList<String>(deleteIds.size());
        for (final var id : deleteIds) {
            if (!inserted.contains(id)) {
                stale.add(id);
            }
        }
        return stale;
    }

    static <T extends DbEntry> void write(String collName, PkRows rows, List<T> inserts,
            Function<? super T, byte[]> lines, List<String> deleteIds) throws IOException, InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, collName);
        try {
            final var removed = resolve(rows, deleteIds);
            final var deletes = new ArrayList<PkIndexEntry>();
            for (final var insert : inserts) {
                final var existing = rows.get(insert.get_id());
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
                sizes[index] = lines.apply(inserts.get(index)).length;
            }
            final var pages = cache.selectPagesForInsert(Globals.ADMIN_DB_NAME, collName, sizes);
            for (var index = 0; index < sizes.length; index++) {
                inserts.get(index).setPage(pages[index]);
            }
            final var written = fs.writeBatch(Globals.ADMIN_DB_NAME, collName, inserts, DbEntry::getPage, lines,
                    deletes);
            AdminPageHelper.recordLandedRefs(collName, EventType.DELETED, refsOf(deletes));
            AdminPageHelper.recordLandedDelta(collName, EventType.CREATED, List.copyOf(inserts));
            for (final var row : written) {
                rows.publish(row.getIndex());
            }
            rows.unpublish(removed.keySet());
        } finally {
            locks.release(Globals.ADMIN_DB_NAME, collName);
        }
    }

    private static Map<String, PkIndexEntry> resolve(PkRows rows, List<String> deleteIds) {
        final var resolved = new LinkedHashMap<String, PkIndexEntry>();
        for (final var id : deleteIds) {
            final var pk = rows.get(id);
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
}
