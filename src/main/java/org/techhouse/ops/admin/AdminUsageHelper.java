package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
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

    private AdminUsageHelper() {
    }

    private static void lockUsageCollection() throws InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME);
    }

    private static void releaseUsageCollection() {
        locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME);
    }

    public static void upsertCollectionUsage(CollectionUsageEvent event) throws IOException, InterruptedException {
        if (Globals.ADMIN_DB_NAME.equals(event.getDbName())) {
            return;
        }
        if (cache.getAdminCollectionEntry(event.getDbName(), event.getCollName()) == null) {
            return;
        }
        memoryManagement.recordAccess(event.getKind(), event.getDbName(), event.getCollName(), event.getIndexKey());
        final var counter = memoryManagement.getCounter(event.getKind(), event.getDbName(), event.getCollName(),
                event.getIndexKey());
        if (counter == null) {
            return;
        }
        lockUsageCollection();
        try {
            final var entryId = AdminCollectionUsageEntry.buildId(counter.dbName(), counter.collName(),
                    counter.indexKey());
            final var usageEntry = new AdminCollectionUsageEntry(counter.kind(), counter.dbName(), counter.collName(),
                    counter.indexKey(), counter.getAccessCount(), counter.getLastAccessMillis());
            final var existingPk = cache.getPkIndexCollectionUsage(entryId);
            PkIndexEntry savedPk;
            if (existingPk != null) {
                usageEntry.setPage(existingPk.getPage());
                usageEntry.setPreviousByteSize(existingPk.getLength());
                final var updateResult = fs.updateFromCollection(usageEntry, existingPk);
                savedPk = updateResult.indexEntry();
                cache.shiftPkPositionsAfterCompaction(updateResult.compaction());
                AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME,
                        EventType.UPDATED, List.of(usageEntry), false);
            } else {
                usageEntry.setPage(cache.selectPageForInsert(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME,
                        usageEntry.byteSize()));
                savedPk = fs.insertIntoCollection(usageEntry);
                AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME,
                        EventType.CREATED, List.of(usageEntry), false);
            }
            cache.putPkIndexCollectionUsage(savedPk);
        } finally {
            releaseUsageCollection();
        }
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
                    final var compaction = fs.deleteFromCollection(pk);
                    cache.shiftPkPositionsAfterCompaction(compaction);
                    cache.removePkIndexCollectionUsage(pk.getValue());
                    memoryManagement.clearCounter(usage.getKind(), usage.getDbName(), usage.getCollName(),
                            usage.getIndexKey());
                    AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME,
                            EventType.DELETED, List.of(usage), false);
                }
            }
        } finally {
            releaseUsageCollection();
        }
    }
}
