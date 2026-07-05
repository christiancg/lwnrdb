package org.techhouse.bckg_ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.data.DbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;

public class EventProcessorHelper {
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    public static void processEvent(Event event) throws IOException, InterruptedException {
        switch (event) {
            case EntityEvent entityEvent -> processEntityEvent(entityEvent);
            case BulkEntityEvent bulkEntityEvent -> processBulkEntityEvent(bulkEntityEvent);
            case CollectionUsageEvent usageEvent -> AdminOperationHelper.upsertCollectionUsage(usageEvent);
            case UsageProfileCleanupEvent ignored ->
                AdminOperationHelper.cleanupCollectionUsage(memoryManagement.usageRetentionMillis());
            default -> throw new IllegalStateException("Unexpected value: " + event);
        }
    }

    private static void processBulkEntityEvent(BulkEntityEvent event) throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        if (AdminOperationHelper.getCollectionEntry(dbName, collName) == null) {
            // The collection was dropped while this event was queued; there is nothing to maintain.
            // Clear the pending overlay and skip so we never touch the removed collection's files.
            clearPending(dbName, collName, event.getInsertedEntries());
            clearPending(dbName, collName, event.getUpdatedEntries());
            return;
        }
        try {
            IndexHelper.bulkUpdateIndexes(dbName, collName,
                    idsOf(event.getInsertedEntries(), event.getUpdatedEntries()));
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

    private static List<String> idsOf(List<DbEntry> inserted, List<DbEntry> updated) {
        final var ids = new ArrayList<String>(inserted.size() + updated.size());
        for (var entry : inserted) {
            ids.add(entry.get_id());
        }
        for (var entry : updated) {
            ids.add(entry.get_id());
        }
        return ids;
    }

    private static void processEntityEvent(EntityEvent event) throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        final var dbEntry = event.getDbEntry();
        final var type = event.getType();
        if (AdminOperationHelper.getCollectionEntry(dbName, collName) == null) {
            // The collection was dropped while this event was queued; there is nothing to maintain.
            // Clear the pending overlay and skip so we never touch the removed collection's files.
            pendingIndexWrites.clear(dbName, collName, dbEntry.get_id());
            return;
        }
        try {
            // Index maintenance re-reads the current document by id (order-independent); the event
            // type/snapshot is still authoritative for the admin entry-count delta.
            IndexHelper.updateIndexes(dbName, collName, dbEntry.get_id());
            AdminOperationHelper.updateEntryCount(dbName, collName, type, dbEntry);
        } finally {
            // Indexing is done (or failed): the document is no longer pending. Cleared in a finally so
            // a failure cannot leak the id into the pending overlay (no-op for DELETE, never marked).
            pendingIndexWrites.clear(dbName, collName, dbEntry.get_id());
        }
    }

}
