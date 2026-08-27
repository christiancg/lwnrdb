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
import org.techhouse.data.DbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

/**
 * Queues the triggers a committed write fires.
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
        if (!configuration.isTriggersEnabled() || entries == null || entries.isEmpty()) {
            return;
        }
        final var triggers = cache.getTriggersFor(dbName, collName);
        if (triggers.isEmpty()) {
            return;
        }
        for (final var trigger : triggers) {
            if (!trigger.isEnabled() || !trigger.getEvents().contains(type)) {
                continue;
            }
            // allowCascade defaults to false, so the common configuration cannot cascade even once: a write
            // that a trigger itself issued arrives with depth > 0 and fires nothing.
            if (depth > 0 && !trigger.isAllowCascade()) {
                continue;
            }
            if (trigger.isBatchMode()) {
                triggerExecutor.submit(new TriggerEvent(type, dbName, collName, trigger.getName(),
                        trigger.getProcedureName(), true, entries, actingUser, depth));
            } else {
                for (final var entry : entries) {
                    triggerExecutor.submit(new TriggerEvent(type, dbName, collName, trigger.getName(),
                            trigger.getProcedureName(), false, List.of(entry), actingUser, depth));
                }
            }
        }
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

    private static boolean hasNotTriggerFor(String dbName, String collName, EventType type, int depth) {
        if (!configuration.isTriggersEnabled()) {
            return true;
        }
        for (final var trigger : cache.getTriggersFor(dbName, collName)) {
            if (trigger.isEnabled() && trigger.getEvents().contains(type) && (depth == 0 || trigger.isAllowCascade())) {
                return false;
            }
        }
        return true;
    }
}
