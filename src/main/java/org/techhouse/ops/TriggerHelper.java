package org.techhouse.ops;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.OperationResponse;

public final class TriggerHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(TriggerHelper.class);

    private TriggerHelper() {
    }

    public static void afterWrite(String dbName, String collName, EventType type, List<DbEntry> entries,
            String actingUser, int depth) {
        if (!configuration.isTriggersEnabled() || entries == null || entries.isEmpty()
                || Globals.SCRIPT_RUNS_COLLECTION_NAME.equals(collName)) {
            return;
        }
        final var triggers = cache.getTriggersFor(dbName, collName);
        if (triggers.isEmpty()) {
            return;
        }
        for (final var trigger : triggers) {
            if (trigger.isBefore() || !trigger.isEnabled() || !trigger.getEvents().contains(type)) {
                continue;
            }
            if (depth > 0 && !trigger.isAllowCascade()) {
                continue;
            }
            if (trigger.isBatchMode()) {
                submitLogged(trigger, type, dbName, collName, entries, actingUser, depth, true);
            } else {
                for (final var entry : entries) {
                    submitLogged(trigger, type, dbName, collName, List.of(entry), actingUser, depth, false);
                }
            }
        }
    }

    private static void submitLogged(TriggerDefinition trigger, EventType type, String dbName, String collName,
            List<DbEntry> entries, String actingUser, int depth, boolean batchMode) {
        final var runId = TriggerRunLog.record(
                new TriggerRunLog.TriggerRunDescriptor(dbName, collName, trigger.getName(), trigger.getProcedureName(),
                        type, batchMode, actingUser, depth, System.currentTimeMillis(), entries));
        triggerExecutor.submit(new TriggerEvent(type, dbName, collName, trigger.getName(), trigger.getProcedureName(),
                batchMode, entries, actingUser, depth, runId));
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
            afterWrite(dbName, collName, type, cache.getEntriesByIds(dbName, collName, new HashSet<>(ids)), actingUser,
                    depth);
        } catch (IOException e) {
            logger.error("Could not read the committed documents to fire a trigger on " + dbName + "|" + collName, e);
        }
    }

    public static void afterBulkSave(String dbName, String collName, OperationResponse response, String actingUser,
            int depth) {
        if (response instanceof BulkSaveResponse bulkSaveResponse) {
            afterWriteIds(dbName, collName, EventType.CREATED, bulkSaveResponse.getInserted(), actingUser, depth);
            afterWriteIds(dbName, collName, EventType.UPDATED, bulkSaveResponse.getUpdated(), actingUser, depth);
        }
    }

    public static DbEntry captureForDelete(String dbName, String collName, String id, int depth) {
        if (hasNotTriggerFor(dbName, collName, EventType.DELETED, depth)) {
            return null;
        }
        try {
            final var entries = cache.getEntriesByIds(dbName, collName, Set.of(id));
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
        if (!configuration.isTriggersEnabled()) {
            return true;
        }
        for (final var trigger : cache.getTriggersFor(dbName, collName)) {
            if (!trigger.isBefore() && trigger.isEnabled() && trigger.getEvents().contains(type)
                    && (depth == 0 || trigger.isAllowCascade())) {
                return false;
            }
        }
        return true;
    }
}
