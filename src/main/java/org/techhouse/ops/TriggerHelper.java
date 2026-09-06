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

/**
 * Queues the after triggers a committed write fires. A before trigger is never queued here: it already ran
 * synchronously on the writing thread ({@code ops.BeforeHookContext}) and has no effect left to apply.
 *
 * <p>
 * Called from OperationProcessor's write handlers only, never from SaveOperationHelper or
 * DeleteOperationHelper: {@code ReplicatedApplyHelper} and {@code ReplicatedTxApplyHelper} reach those
 * helpers directly, bypassing OperationProcessor, so a seam here fires exactly once per logical write - on
 * the owner - while a seam inside the write helpers would fire once per replica.
 *
 * <p>
 * Submitting is a map lookup and a queue offer, so it is safe to call while the collection write lock is
 * held; no script runs on this path.
 */
public final class TriggerHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(TriggerHelper.class);

    private TriggerHelper() {
    }

    public static void afterWrite(String dbName, String collName, EventType type, List<DbEntry> entries,
            String actingUser, int depth) {
        // The history collection is written by the server on behalf of runs that have already finished;
        // letting a trigger fire on it would let an audit trigger cascade on its own record of itself.
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
            // allowCascade defaults to false, so the common configuration cannot cascade even once: a write
            // that a trigger itself issued arrives with depth > 0 and fires nothing.
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

    // One durable record per queued event, so the event's own transaction consumes exactly the record that
    // would replay it. A shared record across an expanded batch could not do that: a crash partway through
    // would leave it behind and replay the events that had already committed.
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

    // Re-reads the committed documents so a trigger sees what was actually stored, the way
    // ClusterWriteHelper re-reads before replicating. Skipped entirely when nothing would fire, so an
    // untriggered collection never pays for the read.
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

    // A BULK_SAVE reports its inserts and updates separately, so it fires two events rather than one.
    // Nothing fires unless the write actually produced a BulkSaveResponse.
    public static void afterBulkSave(String dbName, String collName, OperationResponse response, String actingUser,
            int depth) {
        if (response instanceof BulkSaveResponse bulkSaveResponse) {
            afterWriteIds(dbName, collName, EventType.CREATED, bulkSaveResponse.getInserted(), actingUser, depth);
            afterWriteIds(dbName, collName, EventType.UPDATED, bulkSaveResponse.getUpdated(), actingUser, depth);
        }
    }

    // Reads the document a DELETE is about to remove, so a DELETED trigger can see it. Returns null when no
    // DELETED trigger would fire, which is the common case and costs one map lookup.
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

    // Whether a DELETED trigger would fire for this write. The transactional delete path needs the answer
    // without the read captureForDelete does: it takes the document from its own overlay.
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
