package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ex.PartialBulkSaveException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;

public final class TriggerHelper {
    private static final int DOCUMENT_READ_RETRY_ATTEMPTS = 3;
    private static final long DOCUMENT_READ_RETRY_DELAY_MS = 25L;

    @SuppressWarnings("FieldMayBeFinal")
    private static Cache cache = IocContainer.get(Cache.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(TriggerHelper.class);

    private TriggerHelper() {
    }

    static List<DbEntry> readEntriesWithRetry(String dbName, String collName, Set<String> ids) throws IOException {
        IOException lastFailure = null;
        for (var attempt = 0; attempt < DOCUMENT_READ_RETRY_ATTEMPTS; attempt++) {
            try {
                return cache.getEntriesByIds(dbName, collName, ids);
            } catch (IOException e) {
                lastFailure = e;
                sleepBeforeRetry();
            }
        }
        throw lastFailure;
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(DOCUMENT_READ_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<org.techhouse.data.TriggerDefinition> triggersOrNone(String dbName, String collName) {
        try {
            return cache.getTriggersFor(dbName, collName);
        } catch (org.techhouse.ex.MetadataReadException e) {
            logger.warning("Could not read the triggers for " + dbName + "|" + collName
                    + "; firing none for this write: " + e.getMessage());
            return List.of();
        }
    }

    private static List<TriggerDefinition> matchingTriggers(String dbName, String collName, EventType type, int depth) {
        if (!configuration.isTriggersEnabled() || Globals.SCRIPT_RUNS_COLLECTION_NAME.equals(collName)) {
            return List.of();
        }
        final var matching = new ArrayList<TriggerDefinition>();
        for (final var trigger : triggersOrNone(dbName, collName)) {
            if (!trigger.isBefore() && trigger.isEnabled() && trigger.getEvents().contains(type)
                    && (depth == 0 || trigger.isAllowCascade())) {
                matching.add(trigger);
            }
        }
        return matching;
    }

    private static List<List<DbEntry>> runGroups(TriggerDefinition trigger, List<DbEntry> entries) {
        return trigger.isBatchMode() ? List.of(entries) : entries.stream().map(List::of).toList();
    }

    public static void afterWrite(String dbName, String collName, EventType type, List<DbEntry> entries,
            String actingUser, int depth) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (final var trigger : matchingTriggers(dbName, collName, type, depth)) {
            for (final var group : runGroups(trigger, entries)) {
                final var descriptor = descriptor(dbName, collName, trigger, type, group, actingUser, depth);
                final var runId = TriggerRunLog.record(descriptor);
                triggerExecutor
                        .submit(new TriggerEvent(type, dbName, collName, trigger.getName(), trigger.getProcedureName(),
                                trigger.isBatchMode(), group, actingUser, depth, runId, 1, descriptor.firedAt()));
            }
        }
    }

    private static TriggerRunLog.TriggerRunDescriptor descriptor(String dbName, String collName,
            TriggerDefinition trigger, EventType type, List<DbEntry> group, String actingUser, int depth) {
        return new TriggerRunLog.TriggerRunDescriptor(dbName, collName, trigger.getName(), trigger.getProcedureName(),
                type, trigger.isBatchMode(), actingUser, depth, System.currentTimeMillis(), group);
    }

    private interface RunRecorder {
        String record(TriggerDefinition trigger, List<DbEntry> group, TriggerRunLog.TriggerRunDescriptor descriptor);
    }

    private static StagedTriggerRuns stageRuns(String dbName, String collName, EventType type, List<DbEntry> entries,
            String actingUser, int depth, RunRecorder recorder) {
        if (entries.isEmpty()) {
            return StagedTriggerRuns.none();
        }
        final var runs = new ArrayList<StagedTriggerRuns.StagedRun>();
        for (final var trigger : matchingTriggers(dbName, collName, type, depth)) {
            for (final var group : runGroups(trigger, entries)) {
                final var descriptor = descriptor(dbName, collName, trigger, type, group, actingUser, depth);
                final var runId = recorder.record(trigger, group, descriptor);
                runs.add(new StagedTriggerRuns.StagedRun(dbName, collName, trigger.getName(),
                        trigger.getProcedureName(), type, trigger.isBatchMode(), actingUser, depth,
                        group.stream().map(DbEntry::get_id).toList(), group, runId, descriptor.firedAt()));
            }
        }
        return StagedTriggerRuns.of(runs);
    }

    public static StagedTriggerRuns stageSave(SaveRequest request, String actingUser) throws IOException {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (firesOnNoSave(dbName, collName, request.getTriggerDepth())) {
            return StagedTriggerRuns.none();
        }
        final var id = BeforeHookHelper.resolveIdForWrite(request);
        return id == null
                ? StagedTriggerRuns.none()
                : stageBeforeSave(dbName, collName, List.of(id), actingUser, request.getTriggerDepth());
    }

    public static StagedTriggerRuns stageBulkSave(BulkSaveRequest request, String actingUser) throws IOException {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (firesOnNoSave(dbName, collName, request.getTriggerDepth())) {
            return StagedTriggerRuns.none();
        }
        final var ids = new LinkedHashSet<String>();
        for (final var object : request.getObjects()) {
            final var id = BeforeHookHelper.resolveIdForWrite(object);
            if (id != null) {
                ids.add(id);
            }
        }
        return stageBeforeSave(dbName, collName, List.copyOf(ids), actingUser, request.getTriggerDepth());
    }

    public static StagedTriggerRuns stageDelete(DeleteRequest request, DbEntry captured, String actingUser)
            throws IOException {
        if (captured == null) {
            return StagedTriggerRuns.none();
        }
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var versions = priorVersionsOf(dbName, collName, List.of(captured.get_id()));
        return stageRuns(dbName, collName, EventType.DELETED, List.of(captured), actingUser, request.getTriggerDepth(),
                (_, _, descriptor) -> TriggerRunLog.recordStaged(descriptor, versions));
    }

    private static boolean firesOnNoSave(String dbName, String collName, int depth) {
        return hasNotTriggerFor(dbName, collName, EventType.CREATED, depth)
                && hasNotTriggerFor(dbName, collName, EventType.UPDATED, depth);
    }

    private static StagedTriggerRuns stageBeforeSave(String dbName, String collName, List<String> ids,
            String actingUser, int depth) throws IOException {
        final var versions = priorVersionsOf(dbName, collName, ids);
        final var created = new ArrayList<DbEntry>();
        final var updated = new ArrayList<DbEntry>();
        for (final var id : ids) {
            final var placeholder = idOnlyEntry(dbName, collName, id);
            (versions.get(id) == AdminTriggerRunEntry.ABSENT_VERSION ? created : updated).add(placeholder);
        }
        final RunRecorder staged = (_, _, descriptor) -> TriggerRunLog.recordStaged(descriptor, versions);
        return StagedTriggerRuns.combine(
                stageRuns(dbName, collName, EventType.CREATED, created, actingUser, depth, staged),
                stageRuns(dbName, collName, EventType.UPDATED, updated, actingUser, depth, staged));
    }

    private static DbEntry idOnlyEntry(String dbName, String collName, String id) {
        final var entry = new DbEntry();
        entry.setDatabaseName(dbName);
        entry.setCollectionName(collName);
        entry.set_id(id);
        return entry;
    }

    private static Map<String, Long> priorVersionsOf(String dbName, String collName, List<String> ids)
            throws IOException {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var versions = new LinkedHashMap<String, Long>();
        for (final var id : ids) {
            final var position = Collections.binarySearch(primaryKeyIndex, id);
            versions.put(id,
                    position >= 0 ? primaryKeyIndex.get(position).getVersion() : AdminTriggerRunEntry.ABSENT_VERSION);
        }
        return versions;
    }

    public static StagedTriggerRuns stageCommittedIds(String dbName, String collName, EventType type, List<String> ids,
            String actingUser, int depth, String txId) {
        if (ids == null || ids.isEmpty() || hasNotTriggerFor(dbName, collName, type, depth)) {
            return StagedTriggerRuns.none();
        }
        try {
            return stageCommitted(dbName, collName, type, readEntriesWithRetry(dbName, collName, new HashSet<>(ids)),
                    actingUser, depth, txId);
        } catch (IOException e) {
            logger.error("Could not read the committed documents to fire a trigger on " + dbName + "|" + collName, e);
            return StagedTriggerRuns.none();
        }
    }

    public static StagedTriggerRuns stageCommitted(String dbName, String collName, EventType type,
            List<DbEntry> entries, String actingUser, int depth, String txId) {
        return stageRuns(dbName, collName, type, entries, actingUser, depth,
                (trigger, group, descriptor) -> TriggerRunLog.recordDeterministic(descriptor, txId,
                        TriggerRunLog.deterministicRunId(txId, dbName, collName, trigger.getName(), type,
                                trigger.isBatchMode() ? null : group.getFirst().get_id())));
    }

    public static <T> T discardingOnFailure(StagedTriggerRuns staged, Callable<T> step) throws Exception {
        try {
            return step.call();
        } catch (Exception e) {
            staged.discard();
            throw e;
        }
    }

    public static OperationResponse runStaged(StagedTriggerRuns staged, String dbName, String collName,
            String actingUser, int depth, Callable<OperationResponse> write) throws Exception {
        final OperationResponse response;
        try {
            response = write.call();
        } catch (PartialBulkSaveException e) {
            staged.submitLanded(landedIdsOf(e.committed(), staged), dbName, collName, actingUser, depth);
            throw e;
        } catch (Exception e) {
            staged.discard();
            throw e;
        }
        staged.submitLanded(landedIdsOf(response, staged), dbName, collName, actingUser, depth);
        return response;
    }

    private static Map<EventType, Set<String>> landedIdsOf(OperationResponse response, StagedTriggerRuns staged) {
        return switch (response) {
            case SaveResponse save ->
                Map.of(save.isInserted() ? EventType.CREATED : EventType.UPDATED, Set.of(save.get_id()));
            case BulkSaveResponse bulk ->
                Map.of(EventType.CREATED, idSet(bulk.getInserted()), EventType.UPDATED, idSet(bulk.getUpdated()));
            case DeleteResponse _ -> Map.of(EventType.DELETED, staged.stagedDeleteIds());
            case null, default -> Map.of();
        };
    }

    private static Set<String> idSet(List<String> ids) {
        return ids == null ? Set.of() : new LinkedHashSet<>(ids);
    }

    public static void afterWrite(String dbName, String collName, EventType type, DbEntry entry, String actingUser,
            int depth) {
        afterWrite(dbName, collName, type, entry == null ? List.of() : List.of(entry), actingUser, depth);
    }

    public static void afterWriteIds(String dbName, String collName, EventType type, List<String> ids,
            String actingUser, int depth) {
        if (ids == null || ids.isEmpty() || hasNotTriggerFor(dbName, collName, type, depth)) {
            return;
        }
        try {
            afterWrite(dbName, collName, type, readEntriesWithRetry(dbName, collName, new HashSet<>(ids)), actingUser,
                    depth);
        } catch (IOException e) {
            logger.error("Could not read the committed documents to fire a trigger on " + dbName + "|" + collName, e);
        }
    }

    public static DbEntry captureForDelete(String dbName, String collName, String id, int depth) {
        if (hasNotTriggerFor(dbName, collName, EventType.DELETED, depth)) {
            return null;
        }
        try {
            final var entries = readEntriesWithRetry(dbName, collName, Set.of(id));
            return entries.isEmpty() ? null : entries.getFirst();
        } catch (IOException e) {
            logger.error("Could not read the document being deleted to fire a trigger on " + dbName + "|" + collName,
                    e);
            return null;
        }
    }

    public static boolean firesOnDelete(String dbName, String collName, int depth) {
        return !hasNotTriggerFor(dbName, collName, EventType.DELETED, depth);
    }

    private static boolean hasNotTriggerFor(String dbName, String collName, EventType type, int depth) {
        return matchingTriggers(dbName, collName, type, depth).isEmpty();
    }
}
