package org.techhouse.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

/**
 * Re-queues the trigger runs that were pending when this node stopped.
 *
 * <p>
 * A record only survives if the transaction that would have consumed it never committed, so replaying one
 * cannot double-apply a run that already landed — which is what lets recovery be automatic here rather than
 * something an operator has to adjudicate.
 *
 * <p>
 * Must run <em>after</em> {@code TransactionOperationHelper.cleanupOrphansAtStartup}: a run whose commit was
 * in flight when the process died is finished by that pass, which removes its record, and only what is left
 * afterwards is genuinely un-applied.
 */
public final class TriggerRunRecovery {
    private static final Logger logger = Logger.logFor(TriggerRunRecovery.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Configuration configuration = Configuration.getInstance();

    private TriggerRunRecovery() {
    }

    public static void recoverLocal() {
        if (!configuration.isTriggersEnabled() || !TriggerRunLog.isEnabled()) {
            return;
        }
        try {
            final var byRun = groupByRun(TriggerRunLog.pending(), TriggerRunLog.currentNodeId());
            var requeued = 0;
            for (final var chunks : byRun.values()) {
                final var event = toEvent(chunks);
                if (event == null) {
                    TriggerDispatcher.consumeQuietly(chunks.getFirst().getRunId(), chunks.getFirst().getTriggerName());
                    continue;
                }
                triggerExecutor.submit(event);
                requeued++;
            }
            if (requeued > 0) {
                logger.info("Re-queued " + requeued + " trigger run(s) left pending by the previous shutdown");
            }
        } catch (Exception e) {
            logger.error("Failed to recover pending trigger runs at startup", e);
        }
    }

    /**
     * Warns about runs that have been pending far longer than a run should take. A record that lingers is
     * either stranded (its node never came back, or its collection was dropped) or evidence that the trigger
     * queue is not keeping up; either way an operator wants to know before the retention window silently
     * collects it.
     */
    public static void warnAboutStrandedRuns() {
        if (!configuration.isTriggersEnabled() || !TriggerRunLog.isEnabled()) {
            return;
        }
        final var threshold = Math.max(configuration.getTriggerTimeoutMs() * 10L, 60_000L);
        final var now = System.currentTimeMillis();
        try {
            var stranded = 0;
            long oldest = 0L;
            for (final var entry : TriggerRunLog.pending()) {
                final var age = now - entry.getFiredAt();
                if (age > threshold) {
                    stranded++;
                    oldest = Math.max(oldest, age);
                }
            }
            if (stranded > 0) {
                logger.warning(stranded + " trigger run(s) have been pending for up to " + oldest
                        + "ms. They are replayed when their node restarts, and garbage-collected after"
                        + " triggerRunRetentionMs if it never does.");
            }
        } catch (Exception e) {
            logger.warning("Failed to inspect pending trigger runs: " + e.getMessage());
        }
    }

    public static void garbageCollect() {
        if (!TriggerRunLog.isEnabled()) {
            return;
        }
        try {
            TriggerRunLog.garbageCollect(configuration.getTriggerRunRetentionMs());
        } catch (Exception e) {
            logger.warning("Failed to garbage-collect stranded trigger runs: " + e.getMessage());
        }
    }

    private static LinkedHashMap<String, List<AdminTriggerRunEntry>> groupByRun(List<AdminTriggerRunEntry> pending,
            String nodeId) {
        final var byRun = new LinkedHashMap<String, List<AdminTriggerRunEntry>>();
        for (final var entry : pending) {
            if (!nodeId.equals(entry.getNodeId())) {
                continue;
            }
            byRun.computeIfAbsent(entry.getRunId(), _ -> new ArrayList<>()).add(entry);
        }
        return byRun;
    }

    // Rebuilds the event from its chunks. A CREATED/UPDATED run carries ids and re-reads the committed
    // documents at dispatch time, so it sees what is stored now; a DELETED run carries the documents, which
    // no longer exist to be read.
    private static TriggerEvent toEvent(List<AdminTriggerRunEntry> chunks) throws Exception {
        final var first = chunks.getFirst();
        final var entries = new ArrayList<DbEntry>();
        if (first.getEventType() == EventType.DELETED) {
            for (final var chunk : chunks) {
                for (final var document : chunk.getDocuments()) {
                    entries.add(DbEntry.fromJsonObject(first.getDbName(), first.getCollName(), document));
                }
            }
        } else {
            final var ids = new LinkedHashSet<String>();
            for (final var chunk : chunks) {
                ids.addAll(chunk.getIds());
            }
            entries.addAll(cache.getEntriesByIds(first.getDbName(), first.getCollName(), ids));
        }
        if (entries.isEmpty()) {
            return null;
        }
        return new TriggerEvent(first.getEventType(), first.getDbName(), first.getCollName(), first.getTriggerName(),
                first.getProcedureName(), first.isBatchMode(), entries, first.getActingUser(), first.getDepth(),
                first.getRunId());
    }
}
