package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cache.UsageCounter;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollectionUsageEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

public final class AdminUsageHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);

    private static final int USAGE_ROWS_PER_RECORD = 1024;
    private static final AdminBatchWrite.PkRows USAGE_ROWS = new AdminBatchWrite.PkRows() {
        @Override
        public PkIndexEntry get(String id) {
            return cache.getPkIndexCollectionUsage(id);
        }

        @Override
        public void publish(PkIndexEntry row) {
            cache.putPkIndexCollectionUsage(row);
        }

        @Override
        public void unpublish(Set<String> ids) {
            for (final var id : ids) {
                cache.removePkIndexCollectionUsage(id);
            }
        }
    };

    private AdminUsageHelper() {
    }

    private static void lockUsageCollection() throws InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME);
    }

    private static void releaseUsageCollection() {
        locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME);
    }

    public static void flushCollectionUsage(List<UsageCounter> counters) throws IOException, InterruptedException {
        lockUsageCollection();
        try {
            final var rows = new LinkedHashMap<String, AdminCollectionUsageEntry>();
            for (final var counter : counters) {
                final var row = rowOf(counter);
                if (row != null) {
                    rows.remove(row.get_id());
                    rows.put(row.get_id(), row);
                }
            }
            final var lines = new HashMap<String, byte[]>();
            for (final var row : rows.values()) {
                lines.put(row.get_id(), row.encodeLine());
            }
            final var pending = new ArrayList<>(rows.values());
            for (var from = 0; from < pending.size(); from += USAGE_ROWS_PER_RECORD) {
                final var chunk = new ArrayList<>(
                        pending.subList(from, Math.min(pending.size(), from + USAGE_ROWS_PER_RECORD)));
                AdminBatchWrite.write(Globals.ADMIN_COLLECTION_USAGE_NAME, USAGE_ROWS, chunk,
                        row -> lines.get(row.get_id()), List.of());
            }
        } finally {
            releaseUsageCollection();
        }
    }

    private static AdminCollectionUsageEntry rowOf(UsageCounter counter) {
        if (Globals.ADMIN_DB_NAME.equals(counter.dbName())
                || cache.getAdminCollectionEntry(counter.dbName(), counter.collName()) == null) {
            return null;
        }
        final var live = memoryManagement.getCounter(counter.kind(), counter.dbName(), counter.collName(),
                counter.indexKey());
        if (live == null) {
            return null;
        }
        return new AdminCollectionUsageEntry(live.kind(), live.dbName(), live.collName(), live.indexKey(),
                live.getAccessCount(), live.getLastAccessMillis());
    }

    public static void cleanupCollectionUsage(long maxAgeMillis) throws IOException, InterruptedException {
        final var threshold = System.currentTimeMillis() - maxAgeMillis;
        lockUsageCollection();
        try {
            final var rows = new ArrayList<>(cache.getCollectionUsagePkIndexes().values());
            for (var from = 0; from < rows.size(); from += USAGE_ROWS_PER_RECORD) {
                final var expired = expiredAmong(
                        rows.subList(from, Math.min(rows.size(), from + USAGE_ROWS_PER_RECORD)), threshold);
                deleteRows(expired.stream().map(DbEntry::get_id).toList());
                for (final var usage : expired) {
                    memoryManagement.clearCounter(usage.getKind(), usage.getDbName(), usage.getCollName(),
                            usage.getIndexKey());
                }
            }
        } finally {
            releaseUsageCollection();
        }
    }

    private static List<AdminCollectionUsageEntry> expiredAmong(List<PkIndexEntry> rows, long threshold)
            throws IOException {
        final List<DbEntry> entries;
        try {
            entries = fs.getByIndexEntries(rows);
        } catch (IOException | RuntimeException ex) {
            throw new IOException("Failed to read " + rows.size() + " collection usage entries", ex);
        }
        final var expired = new ArrayList<AdminCollectionUsageEntry>();
        for (final var entry : entries) {
            if (entry == null) {
                continue;
            }
            final var data = entry.getData();
            data.addProperty(Globals.PK_FIELD, entry.get_id());
            final var usage = AdminCollectionUsageEntry.fromJsonObject(data);
            if (usage.getLastAccessMillis() < threshold) {
                expired.add(usage);
            }
        }
        return expired;
    }

    public static void deleteUsageOfCollection(String dbName, String collName)
            throws IOException, InterruptedException {
        deleteUsageWithPrefix(AdminCollectionUsageEntry.buildId(dbName, collName, ""));
    }

    public static void deleteUsageOfDatabase(String dbName) throws IOException, InterruptedException {
        deleteUsageWithPrefix(dbName + Globals.COLL_IDENTIFIER_SEPARATOR);
    }

    private static void deleteUsageWithPrefix(String prefix) throws IOException, InterruptedException {
        lockUsageCollection();
        try {
            final var ids = new ArrayList<String>();
            for (final var id : cache.getCollectionUsagePkIndexes().keySet()) {
                if (id.startsWith(prefix)) {
                    ids.add(id);
                }
            }
            for (var from = 0; from < ids.size(); from += USAGE_ROWS_PER_RECORD) {
                deleteRows(ids.subList(from, Math.min(ids.size(), from + USAGE_ROWS_PER_RECORD)));
            }
        } finally {
            releaseUsageCollection();
        }
    }

    private static void deleteRows(List<String> ids) throws IOException, InterruptedException {
        if (ids.isEmpty()) {
            return;
        }
        AdminBatchWrite.write(Globals.ADMIN_COLLECTION_USAGE_NAME, USAGE_ROWS, List.of(),
                AdminCollectionUsageEntry::encodeLine, List.copyOf(ids));
    }
}
