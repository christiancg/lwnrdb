package org.techhouse.bckg_ops;

import java.io.IOException;
import java.util.List;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.CollectionEvent;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.DatabaseEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.IndexEvent;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;

public class EventProcessorHelper {
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    public static void processEvent(Event event) throws IOException, InterruptedException {
        switch (event) {
            case DatabaseEvent databaseEvent -> processDatabaseEvent(databaseEvent);
            case CollectionEvent collectionEvent -> processCollectionEvent(collectionEvent);
            case EntityEvent entityEvent -> processEntityEvent(entityEvent);
            case IndexEvent indexEvent -> processIndexEvent(indexEvent);
            case BulkEntityEvent bulkEntityEvent -> processBulkEntityEvent(bulkEntityEvent);
            case CollectionUsageEvent usageEvent -> AdminOperationHelper.upsertCollectionUsage(usageEvent);
            case UsageProfileCleanupEvent ignored ->
                AdminOperationHelper.cleanupCollectionUsage(memoryManagement.usageRetentionMillis());
            default -> throw new IllegalStateException("Unexpected value: " + event);
        }
    }

    private static void processDatabaseEvent(DatabaseEvent event) throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        if (event.getType() == EventType.CREATED) {
            final var existingDbEntry = AdminOperationHelper.getDatabaseEntry(dbName);
            if (existingDbEntry == null) {
                final var newAdminDbEntry = new AdminDbEntry(dbName);
                AdminOperationHelper.saveDatabaseEntry(newAdminDbEntry);
            }
        } else {
            AdminOperationHelper.deleteDatabaseEntry(dbName);
        }
    }

    private static void processCollectionEvent(CollectionEvent event) throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        if (event.getType() == EventType.CREATED) {
            final var existingCollEntry = AdminOperationHelper.getCollectionEntry(dbName, collName);
            if (existingCollEntry == null) {
                AdminOperationHelper.createPageCollections(dbName, collName);
                final var newAdminCollEntry = new AdminCollEntry(dbName, collName);
                AdminOperationHelper.saveCollectionEntry(newAdminCollEntry);
            }
        } else {
            AdminOperationHelper.deleteCollectionEntry(dbName, collName);
            AdminOperationHelper.deletePageCollections(dbName, collName);
        }
    }

    private static void processBulkEntityEvent(BulkEntityEvent event) throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        try {
            IndexHelper.bulkUpdateIndexes(dbName, collName, event.getInsertedEntries(), event.getUpdatedEntries());
            AdminOperationHelper.bulkUpdateEntryCount(dbName, collName, EventType.CREATED, event.getInsertedEntries());
            AdminOperationHelper.bulkUpdateEntryCount(dbName, collName, EventType.UPDATED, event.getUpdatedEntries());
        } finally {
            // Indexing is done (or failed): the documents are no longer pending. Cleared in a finally
            // so a failure cannot leak ids into the pending overlay.
            clearPending(dbName, collName, event.getInsertedEntries());
            clearPending(dbName, collName, event.getUpdatedEntries());
        }
    }

    private static void clearPending(String dbName, String collName, List<DbEntry> entries) {
        for (var entry : entries) {
            pendingIndexWrites.clear(dbName, collName, entry.get_id());
        }
    }

    private static void processEntityEvent(EntityEvent event) throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        final var dbEntry = event.getDbEntry();
        final var type = event.getType();
        try {
            IndexHelper.updateIndexes(dbName, collName, dbEntry, type);
            AdminOperationHelper.updateEntryCount(dbName, collName, type, dbEntry);
        } finally {
            // Indexing is done (or failed): the document is no longer pending. Cleared in a finally so
            // a failure cannot leak the id into the pending overlay (no-op for DELETE, never marked).
            pendingIndexWrites.clear(dbName, collName, dbEntry.get_id());
        }
    }

    private static void processIndexEvent(IndexEvent event) throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        final var fieldName = event.getFieldName();
        if (event.getType() == EventType.CREATED) {
            if (!AdminOperationHelper.hasIndexEntry(dbName, collName, fieldName)) {
                AdminOperationHelper.saveNewIndex(dbName, collName, fieldName);
            }
        } else {
            AdminOperationHelper.deleteIndex(dbName, collName, fieldName);
        }
    }
}
