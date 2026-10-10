package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.tx.FencedTriggerRuns;
import org.techhouse.ops.tx.SliceStates;

public final class TriggerRunRecovery {
    private static final Logger logger = Logger.logFor(TriggerRunRecovery.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Configuration configuration = Configuration.getInstance();

    private TriggerRunRecovery() {
    }

    public static Set<String> startupRunIds() {
        try {
            final var ownedByReplay = new HashSet<String>();
            final var startup = new HashSet<String>();
            for (final var entry : TriggerRunLog.pending()) {
                final var txId = entry.getTxId();
                if (txId != null && SliceStates.isFenced(txId)) {
                    ownedByReplay.add(entry.getRunId());
                } else {
                    startup.add(entry.getRunId());
                }
            }
            ownedByReplay.addAll(FencedTriggerRuns.consumedByFencedSlices());
            startup.removeAll(ownedByReplay);
            return startup;
        } catch (Exception e) {
            logger.error("Failed to read the pending trigger runs to decide which ones startup recovery replays;"
                    + " falling back to every pending run", e);
            return TriggerRunLog.pendingRunIds();
        }
    }

    public static void recoverLocal(Set<String> startupRunIds) {
        if (!configuration.isTriggersEnabled() || !TriggerRunLog.isEnabled()) {
            return;
        }
        try {
            final var byRun = groupByRun(TriggerRunLog.pending(), TriggerRunLog.ownNodeIds(), startupRunIds);
            var requeued = 0;
            for (final var chunks : byRun.values()) {
                if (requeue(chunks)) {
                    requeued++;
                }
            }
            if (requeued > 0) {
                logger.info("Re-queued " + requeued + " trigger run(s) left pending by the previous shutdown");
            }
        } catch (Exception e) {
            logger.error("Failed to recover pending trigger runs at startup", e);
        }
    }

    public static void requeueRuns(Set<String> runIds) {
        if (runIds.isEmpty() || !configuration.isTriggersEnabled() || !TriggerRunLog.isEnabled()) {
            return;
        }
        try {
            for (final var chunks : groupByRun(TriggerRunLog.pending(), TriggerRunLog.ownNodeIds(), runIds).values()) {
                requeue(chunks);
            }
        } catch (Exception e) {
            logger.error("Failed to requeue the trigger runs of a discarded transaction slice " + runIds
                    + "; they stay pending until the next restart", e);
        }
    }

    private static boolean requeue(List<AdminTriggerRunEntry> stored) {
        final var first = stored.getFirst();
        try {
            final var chunks = stored.stream().anyMatch(TriggerRunRecovery::isStaged) ? confirmLanded(stored) : stored;
            if (chunks.isEmpty()) {
                TriggerDispatcher.consumeQuietly(first.getRunId(), first.getTriggerName());
                return false;
            }
            final var event = toEvent(chunks);
            if (event == null) {
                TriggerDispatcher.consumeQuietly(first.getRunId(), first.getTriggerName());
                return false;
            }
            triggerExecutor.submit(event);
            return true;
        } catch (Exception e) {
            logger.error("Failed to recover pending trigger run " + first.getRunId() + "; it stays pending", e);
            return false;
        }
    }

    private static List<AdminTriggerRunEntry> confirmLanded(List<AdminTriggerRunEntry> chunks) throws Exception {
        final var first = chunks.getFirst();
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(first.getDbName(), first.getCollName());
        final var landed = new HashSet<String>();
        for (final var chunk : chunks) {
            if (!isStaged(chunk)) {
                continue;
            }
            chunk.getPriorVersions().forEach((id, priorVersion) -> {
                if (currentVersion(primaryKeyIndex, id) != priorVersion) {
                    landed.add(id);
                }
            });
        }
        return TriggerRunLog.confirmStaged(first.getRunId(), landed);
    }

    private static boolean isStaged(AdminTriggerRunEntry chunk) {
        return chunk.getStatus() == TriggerRunStatus.STAGED;
    }

    private static long currentVersion(List<PkIndexEntry> primaryKeyIndex, String id) {
        final var position = Collections.binarySearch(primaryKeyIndex, id);
        return position >= 0 ? primaryKeyIndex.get(position).getVersion() : AdminTriggerRunEntry.ABSENT_VERSION;
    }

    public static void warnAboutStrandedRuns() {
        if (!configuration.isTriggersEnabled() || !TriggerRunLog.isEnabled()) {
            return;
        }
        final var threshold = Math.max(configuration.getTriggerTimeoutMs() * 10L, 60_000L);
        final var now = System.currentTimeMillis();
        try {
            var stranded = 0;
            var dead = 0;
            long oldest = 0L;
            for (final var entry : TriggerRunLog.pending()) {
                if (entry.getStatus() == TriggerRunStatus.DEAD) {
                    dead++;
                    continue;
                }
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
            if (dead > 0) {
                logger.warning(dead + " trigger run(s) exhausted their attempts and were dead-lettered. List them"
                        + " with LIST_TRIGGER_RUNS and replay or discard them with RESOLVE_TRIGGER_RUN.");
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
            TriggerRunLog.garbageCollect(configuration.getTriggerRunRetentionMs(), Math
                    .max(configuration.getTriggerDeadLetterRetentionMs(), configuration.getTriggerRunRetentionMs()));
        } catch (Exception e) {
            logger.warning("Failed to garbage-collect stranded trigger runs: " + e.getMessage());
        }
    }

    private static LinkedHashMap<String, List<AdminTriggerRunEntry>> groupByRun(List<AdminTriggerRunEntry> pending,
            Set<String> ownNodeIds, Set<String> runIds) {
        final var byRun = new LinkedHashMap<String, List<AdminTriggerRunEntry>>();
        for (final var entry : pending) {
            if (!runIds.contains(entry.getRunId()) || !ownNodeIds.contains(entry.getNodeId())
                    || entry.getStatus() == TriggerRunStatus.DEAD) {
                continue;
            }
            byRun.computeIfAbsent(entry.getRunId(), _ -> new ArrayList<>()).add(entry);
        }
        return byRun;
    }

    static TriggerEvent toEvent(List<AdminTriggerRunEntry> chunks) throws Exception {
        return toEvent(chunks, chunks.getFirst().getAttempts() + 1);
    }

    static TriggerEvent toEvent(List<AdminTriggerRunEntry> chunks, int attempt) throws Exception {
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
                first.getRunId(), Math.max(1, attempt), recordedFiredAt(first));
    }

    private static long recordedFiredAt(AdminTriggerRunEntry run) {
        return run.getFiredAt() > 0 ? run.getFiredAt() : System.currentTimeMillis();
    }
}
