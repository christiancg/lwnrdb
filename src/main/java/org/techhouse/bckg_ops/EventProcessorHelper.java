package org.techhouse.bckg_ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.CollectionScopedEvent;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EntityRef;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.ScriptRunHistoryEvent;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
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
    private static final Configuration configuration = Configuration.getInstance();
    private static final long IMMEDIATELY = 0L;

    private enum Outcome {
        DONE, BUSY, INTERRUPTED
    }

    private interface Work {
        void run(long lockBudgetMs) throws IOException, InterruptedException;
    }

    private record WorkUnit(String collection, List<? extends Event> events, boolean budgeted, Work work) {
    }

    public static List<Event> processBatch(List<Event> batch) throws IOException, InterruptedException {
        if (batch.size() == 1) {
            return processAlone(batch.getFirst());
        }
        final var units = workUnitsOf(batch);
        final var deferred = new ArrayList<Event>();
        final var busy = new ArrayList<WorkUnit>();
        for (var i = 0; i < units.size(); i++) {
            final var unit = units.get(i);
            final var outcome = attempt(unit, unit.budgeted() ? IMMEDIATELY : lockBudget());
            if (outcome == Outcome.INTERRUPTED) {
                withEventsOf(busy, deferred);
                return withEventsOf(units.subList(i, units.size()), deferred);
            }
            if (outcome == Outcome.BUSY) {
                busy.add(unit);
            }
        }
        if (busy.size() < units.size()) {
            return withEventsOf(busy, deferred);
        }
        for (var i = 0; i < busy.size(); i++) {
            final var unit = busy.get(i);
            final var outcome = unit.budgeted() ? attempt(unit, lockBudget()) : Outcome.BUSY;
            if (outcome == Outcome.INTERRUPTED) {
                return withEventsOf(busy.subList(i, busy.size()), deferred);
            }
            if (outcome == Outcome.BUSY) {
                deferred.addAll(unit.events());
            }
        }
        return deferred;
    }

    private static List<Event> processAlone(Event event) throws IOException {
        final var deferred = new ArrayList<Event>();
        try {
            if (isIndexMaintenance(event)) {
                processWaitingOnlyWhenBusy(event);
            } else {
                processEvent(event, lockBudget());
            }
        } catch (CollectionBusyException busy) {
            deferred.add(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deferred.add(event);
        }
        return deferred;
    }

    private static void processWaitingOnlyWhenBusy(Event event) throws IOException, InterruptedException {
        try {
            processEvent(event, IMMEDIATELY);
        } catch (CollectionBusyException busy) {
            processEvent(event, lockBudget());
        }
    }

    private static boolean isIndexMaintenance(Event event) {
        return event instanceof EntityEvent || event instanceof BulkEntityEvent;
    }

    private static List<WorkUnit> workUnitsOf(List<Event> batch) {
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
        final var units = new ArrayList<WorkUnit>(entityGroups.size() + others.size());
        for (final var group : entityGroups.entrySet()) {
            final var events = group.getValue();
            units.add(new WorkUnit(group.getKey(), events, true, budget -> processEntityGroup(events, budget)));
        }
        for (final var event : others) {
            units.add(new WorkUnit(collectionOf(event), List.of(event), isIndexMaintenance(event),
                    budget -> processEvent(event, budget)));
        }
        return units;
    }

    private static Outcome attempt(WorkUnit unit, long lockBudgetMs) {
        try {
            unit.work().run(lockBudgetMs);
        } catch (CollectionBusyException busy) {
            return Outcome.BUSY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.INTERRUPTED;
        } catch (Exception e) {
            logger.warning(abandonedWork(unit.collection(), e));
        }
        return Outcome.DONE;
    }

    private static List<Event> withEventsOf(List<WorkUnit> units, List<Event> deferred) {
        for (final var unit : units) {
            deferred.addAll(unit.events());
        }
        return deferred;
    }

    private static long lockBudget() {
        return configuration.getTransactionLockTimeoutMs();
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

    private static void processEntityGroup(List<EntityEvent> group, long lockBudgetMs)
            throws IOException, InterruptedException {
        if (group.size() == 1) {
            processEntityEvent(group.getFirst(), lockBudgetMs);
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
            ids.add(event.getId());
        }
        final var indexFailure = indexFailureOf(
                () -> IndexHelper.bulkUpdateIndexes(dbName, collName, new ArrayList<>(ids), lockBudgetMs));
        for (final var event : group) {
            AdminOperationHelper.updateEntryCount(dbName, collName, event.getType(), event.getEntity(),
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
        pendingIndexWrites.clear(event.getDbName(), event.getCollName(), event.getId(), event.getPendingGeneration());
    }

    public static void processEvent(Event event) throws IOException, InterruptedException {
        processEvent(event, lockBudget());
    }

    private static void processEvent(Event event, long lockBudgetMs) throws IOException, InterruptedException {
        switch (event) {
            case EntityEvent entityEvent -> processEntityEvent(entityEvent, lockBudgetMs);
            case BulkEntityEvent bulkEntityEvent -> processBulkEntityEvent(bulkEntityEvent, lockBudgetMs);
            case CollectionUsageEvent usageEvent -> AdminOperationHelper.upsertCollectionUsage(usageEvent);
            case UsageProfileCleanupEvent ignored ->
                AdminOperationHelper.cleanupCollectionUsage(memoryManagement.usageRetentionMillis());
            case ScriptRunHistoryEvent historyEvent ->
                ScriptRunHistory.write(historyEvent.getRecord(), historyEvent.getRetries());
            default -> throw new IllegalStateException("Unexpected value: " + event);
        }
    }

    private static void processBulkEntityEvent(BulkEntityEvent event, long lockBudgetMs)
            throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        if (belongsToAnotherIncarnation(event)) {
            clearPending(event, event.getInserted());
            clearPending(event, event.getUpdated());
            return;
        }
        final var indexFailure = indexFailureOf(() -> IndexHelper.bulkUpdateIndexes(dbName, collName,
                idsOf(event.getInserted(), event.getUpdated()), lockBudgetMs));
        AdminOperationHelper.bulkUpdateEntryCount(dbName, collName, EventType.CREATED, event.getInserted(),
                event.getIncarnation());
        AdminOperationHelper.bulkUpdateEntryCount(dbName, collName, EventType.UPDATED, event.getUpdated(),
                event.getIncarnation());
        if (indexFailure != null) {
            throw indexFailure;
        }
        clearPending(event, event.getInserted());
        clearPending(event, event.getUpdated());
    }

    private static void clearPending(BulkEntityEvent event, List<EntityRef> entries) {
        final var ids = new ArrayList<String>(entries.size());
        for (var entry : entries) {
            ids.add(entry.id());
        }
        pendingIndexWrites.clear(event.getDbName(), event.getCollName(), ids, event.getPendingGeneration());
    }

    private static List<String> idsOf(List<EntityRef> inserted, List<EntityRef> updated) {
        final var ids = new ArrayList<String>(inserted.size() + updated.size());
        for (var entry : inserted) {
            ids.add(entry.id());
        }
        for (var entry : updated) {
            ids.add(entry.id());
        }
        return ids;
    }

    private static void processEntityEvent(EntityEvent event, long lockBudgetMs)
            throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        final var id = event.getId();
        if (belongsToAnotherIncarnation(event)) {
            pendingIndexWrites.clear(dbName, collName, id, event.getPendingGeneration());
            return;
        }
        final var indexFailure = indexFailureOf(
                () -> IndexHelper.bulkUpdateIndexes(dbName, collName, Collections.singletonList(id), lockBudgetMs));
        AdminOperationHelper.updateEntryCount(dbName, collName, event.getType(), event.getEntity(),
                event.getIncarnation());
        if (indexFailure != null) {
            throw indexFailure;
        }
        pendingIndexWrites.clear(dbName, collName, id, event.getPendingGeneration());
    }

    private static boolean belongsToAnotherIncarnation(CollectionScopedEvent event) {
        final var entry = AdminOperationHelper.getCollectionEntry(event.getDbName(), event.getCollName());
        return entry == null || !CollectionIncarnation.sameLife(event.getIncarnation(), entry.getIncarnation());
    }

}
