package org.techhouse.bckg_ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.ScriptRunHistoryEvent;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.ScriptRunHistory;

public class EventProcessorHelper {
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    public static void processBatch(List<Event> batch) throws IOException, InterruptedException {
        if (batch.size() == 1) {
            processEvent(batch.getFirst());
            return;
        }
        final var entityGroups = new LinkedHashMap<String, List<EntityEvent>>();
        final var others = new ArrayList<Event>();
        for (final var event : batch) {
            if (event instanceof EntityEvent entityEvent) {
                final var key = entityEvent.getDbName() + Globals.COLL_IDENTIFIER_SEPARATOR + entityEvent.getCollName();
                entityGroups.computeIfAbsent(key, _ -> new ArrayList<>()).add(entityEvent);
            } else {
                others.add(event);
            }
        }
        for (final var group : entityGroups.values()) {
            processEntityGroup(group);
        }
        for (final var event : others) {
            processEvent(event);
        }
    }

    private static void processEntityGroup(List<EntityEvent> group) throws IOException, InterruptedException {
        if (group.size() == 1) {
            processEntityEvent(group.getFirst());
            return;
        }
        final var dbName = group.getFirst().getDbName();
        final var collName = group.getFirst().getCollName();
        if (AdminOperationHelper.getCollectionEntry(dbName, collName) == null) {
            clearPendingEvents(group);
            return;
        }
        try {
            final var ids = new LinkedHashSet<String>();
            for (final var event : group) {
                ids.add(event.getDbEntry().get_id());
            }
            IndexHelper.bulkUpdateIndexes(dbName, collName, new ArrayList<>(ids));
            for (final var event : group) {
                AdminOperationHelper.updateEntryCount(dbName, collName, event.getType(), event.getDbEntry());
            }
        } finally {
            clearPendingEvents(group);
        }
    }

    private static void clearPendingEvents(List<EntityEvent> group) {
        for (final var event : group) {
            pendingIndexWrites.clear(event.getDbName(), event.getCollName(), event.getDbEntry().get_id());
        }
    }

    public static void processEvent(Event event) throws IOException, InterruptedException {
        switch (event) {
            case EntityEvent entityEvent -> processEntityEvent(entityEvent);
            case BulkEntityEvent bulkEntityEvent -> processBulkEntityEvent(bulkEntityEvent);
            case CollectionUsageEvent usageEvent -> AdminOperationHelper.upsertCollectionUsage(usageEvent);
            case UsageProfileCleanupEvent ignored ->
                AdminOperationHelper.cleanupCollectionUsage(memoryManagement.usageRetentionMillis());
            case ScriptRunHistoryEvent historyEvent -> ScriptRunHistory.write(historyEvent.getRecord());
            default -> throw new IllegalStateException("Unexpected value: " + event);
        }
    }

    private static void processBulkEntityEvent(BulkEntityEvent event) throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        if (AdminOperationHelper.getCollectionEntry(dbName, collName) == null) {
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
            pendingIndexWrites.clear(dbName, collName, dbEntry.get_id());
            return;
        }
        try {
            // Index maintenance re-reads the current document by id, so events may run out of order;
            // the event snapshot stays authoritative only for the admin entry-count delta.
            IndexHelper.updateIndexes(dbName, collName, dbEntry.get_id());
            AdminOperationHelper.updateEntryCount(dbName, collName, type, dbEntry);
        } finally {
            pendingIndexWrites.clear(dbName, collName, dbEntry.get_id());
        }
    }

}
