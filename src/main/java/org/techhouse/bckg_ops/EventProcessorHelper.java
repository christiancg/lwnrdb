package org.techhouse.bckg_ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.CollectionScopedEvent;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.ScriptRunHistoryEvent;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ex.CollectionBusyException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.ScriptRunHistory;
import org.techhouse.ops.admin.CollectionIncarnation;

public class EventProcessorHelper {
    private static final Logger logger = Logger.logFor(EventProcessorHelper.class);
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    public static List<Event> processBatch(List<Event> batch) throws IOException, InterruptedException {
        final var deferred = new ArrayList<Event>();
        if (batch.size() == 1) {
            try {
                processEvent(batch.getFirst());
            } catch (CollectionBusyException busy) {
                deferred.add(batch.getFirst());
            }
            return deferred;
        }
        final var entityGroups = new LinkedHashMap<String, List<EntityEvent>>();
        final var others = new ArrayList<Event>();
        for (final var event : batch) {
            if (event instanceof EntityEvent entityEvent) {
                final var key = entityEvent.getDbName() + Globals.COLL_IDENTIFIER_SEPARATOR + entityEvent.getCollName()
                        + Globals.COLL_IDENTIFIER_SEPARATOR + entityEvent.getIncarnation();
                entityGroups.computeIfAbsent(key, _ -> new ArrayList<>()).add(entityEvent);
            } else {
                others.add(event);
            }
        }
        for (final var group : entityGroups.entrySet()) {
            try {
                processEntityGroup(group.getValue());
            } catch (CollectionBusyException busy) {
                deferred.addAll(group.getValue());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return deferred;
            } catch (Exception e) {
                logger.warning(abandonedWork(group.getKey(), e));
            }
        }
        for (final var event : others) {
            try {
                processEvent(event);
            } catch (CollectionBusyException busy) {
                deferred.add(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return deferred;
            } catch (Exception e) {
                logger.warning(abandonedWork(collectionOf(event), e));
            }
        }
        return deferred;
    }

    private interface IndexMaintenance {
        void run() throws IOException, InterruptedException;
    }

    private static IOException indexFailureOf(IndexMaintenance maintenance) throws InterruptedException {
        try {
            maintenance.run();
            return null;
        } catch (IOException failure) {
            return failure;
        }
    }

    private static String abandonedWork(String collectionIdentifier, Exception cause) {
        final var target = collectionIdentifier == null ? "" : " on " + collectionIdentifier;
        return "Failed to process a background event" + target + ": " + cause.getMessage()
                + ". Its field indexes may be stale - run REINDEX on that collection.";
    }

    private static String collectionOf(Event event) {
        return switch (event) {
            case EntityEvent entityEvent ->
                entityEvent.getDbName() + Globals.COLL_IDENTIFIER_SEPARATOR + entityEvent.getCollName();
            case BulkEntityEvent bulkEvent ->
                bulkEvent.getDbName() + Globals.COLL_IDENTIFIER_SEPARATOR + bulkEvent.getCollName();
            default -> null;
        };
    }

    private static void processEntityGroup(List<EntityEvent> group) throws IOException, InterruptedException {
        if (group.size() == 1) {
            processEntityEvent(group.getFirst());
            return;
        }
        final var first = group.getFirst();
        final var dbName = first.getDbName();
        final var collName = first.getCollName();
        if (belongsToAnotherIncarnation(first)) {
            clearPendingEvents(group);
            return;
        }
        final var ids = new LinkedHashSet<String>();
        for (final var event : group) {
            ids.add(event.getDbEntry().get_id());
        }
        final var indexFailure = indexFailureOf(
                () -> IndexHelper.bulkUpdateIndexes(dbName, collName, new ArrayList<>(ids)));
        for (final var event : group) {
            AdminOperationHelper.updateEntryCount(dbName, collName, event.getType(), event.getDbEntry(),
                    event.getIncarnation());
            if (indexFailure == null) {
                clearPendingEvent(event);
            }
        }
        if (indexFailure != null) {
            throw indexFailure;
        }
    }

    private static void clearPendingEvents(List<EntityEvent> group) {
        for (final var event : group) {
            clearPendingEvent(event);
        }
    }

    private static void clearPendingEvent(EntityEvent event) {
        pendingIndexWrites.clear(event.getDbName(), event.getCollName(), event.getDbEntry().get_id());
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
        if (belongsToAnotherIncarnation(event)) {
            clearPending(dbName, collName, event.getInsertedEntries());
            clearPending(dbName, collName, event.getUpdatedEntries());
            return;
        }
        final var indexFailure = indexFailureOf(() -> IndexHelper.bulkUpdateIndexes(dbName, collName,
                idsOf(event.getInsertedEntries(), event.getUpdatedEntries())));
        AdminOperationHelper.bulkUpdateEntryCount(dbName, collName, EventType.CREATED, event.getInsertedEntries(),
                event.getIncarnation());
        AdminOperationHelper.bulkUpdateEntryCount(dbName, collName, EventType.UPDATED, event.getUpdatedEntries(),
                event.getIncarnation());
        if (indexFailure != null) {
            throw indexFailure;
        }
        clearPending(dbName, collName, event.getInsertedEntries());
        clearPending(dbName, collName, event.getUpdatedEntries());
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
        if (belongsToAnotherIncarnation(event)) {
            pendingIndexWrites.clear(dbName, collName, dbEntry.get_id());
            return;
        }
        final var indexFailure = indexFailureOf(() -> IndexHelper.updateIndexes(dbName, collName, dbEntry.get_id()));
        AdminOperationHelper.updateEntryCount(dbName, collName, type, dbEntry, event.getIncarnation());
        if (indexFailure != null) {
            throw indexFailure;
        }
        pendingIndexWrites.clear(dbName, collName, dbEntry.get_id());
    }

    private static boolean belongsToAnotherIncarnation(CollectionScopedEvent event) {
        final var entry = AdminOperationHelper.getCollectionEntry(event.getDbName(), event.getCollName());
        return entry == null || !CollectionIncarnation.sameLife(event.getIncarnation(), entry.getIncarnation());
    }

}
