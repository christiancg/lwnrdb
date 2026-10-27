package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.techhouse.bckg_ops.events.EventType;
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
            final var pkIndexes = new ArrayList<>(cache.getCollectionUsagePkIndexes().values());
            for (var pk : pkIndexes) {
                final DbEntry entry;
                try {
                    entry = fs.getById(pk);
                } catch (Exception ex) {
                    throw new IOException("Failed to read collection usage entry " + pk.getValue(), ex);
                }
                if (entry == null)
                    continue;
                final var data = entry.getData();
                data.addProperty(Globals.PK_FIELD, entry.get_id());
                final var usage = AdminCollectionUsageEntry.fromJsonObject(data);
                if (usage.getLastAccessMillis() < threshold) {
                    usage.setPreviousByteSize(pk.getLength());
                    usage.setPage(pk.getPage());
                    fs.deleteFromCollection(pk);
                    cache.removePkIndexCollectionUsage(pk.getValue());
                    memoryManagement.clearCounter(usage.getKind(), usage.getDbName(), usage.getCollName(),
                            usage.getIndexKey());
                    AdminPageHelper.recordLandedDelta(Globals.ADMIN_COLLECTION_USAGE_NAME, EventType.DELETED,
                            List.of(usage));
                }
            }
        } finally {
            releaseUsageCollection();
        }
    }
}
