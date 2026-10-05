package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.data.DbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class StagedTriggerRuns {
    private static final Logger logger = Logger.logFor(StagedTriggerRuns.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final StagedTriggerRuns NONE = new StagedTriggerRuns(List.of());

    private final List<StagedRun> runs;

    record StagedRun(String dbName, String collName, String triggerName, String procedureName, EventType type,
            boolean batchMode, String actingUser, int depth, List<String> ids, List<DbEntry> entries, String runId,
            long firedAt) {
    }

    private StagedTriggerRuns(List<StagedRun> runs) {
        this.runs = runs;
    }

    public static StagedTriggerRuns none() {
        return NONE;
    }

    static StagedTriggerRuns of(List<StagedRun> runs) {
        return runs.isEmpty() ? NONE : new StagedTriggerRuns(List.copyOf(runs));
    }

    public static StagedTriggerRuns combine(StagedTriggerRuns first, StagedTriggerRuns second) {
        final var runs = new ArrayList<>(first.runs);
        runs.addAll(second.runs);
        return of(runs);
    }

    public boolean isEmpty() {
        return runs.isEmpty();
    }

    Set<String> stagedDeleteIds() {
        final var ids = new LinkedHashSet<String>();
        for (final var run : runs) {
            if (run.type() == EventType.DELETED) {
                ids.addAll(run.ids());
            }
        }
        return ids;
    }

    public void submitLanded(Map<EventType, Set<String>> landedIds, String dbName, String collName, String actingUser,
            int depth) {
        final var claimed = new HashMap<EventType, Set<String>>();
        for (final var run : runs) {
            final var landedOfType = landedIds.getOrDefault(run.type(), Set.of());
            final var kept = run.ids().stream().filter(landedOfType::contains).toList();
            if (kept.isEmpty()) {
                TriggerRunLog.discard(run.runId());
                continue;
            }
            claimed.computeIfAbsent(run.type(), _ -> new HashSet<>()).addAll(kept);
            if (kept.size() < run.ids().size()) {
                narrow(run, kept);
            }
            submitWithCurrentEntries(run, kept);
        }
        submitUnclaimed(landedIds, claimed, dbName, collName, actingUser, depth);
    }

    public void submitAll() {
        for (final var run : runs) {
            submit(run, run.entries());
        }
    }

    public void discard() {
        for (final var run : runs) {
            TriggerRunLog.discard(run.runId());
        }
    }

    private void submitWithCurrentEntries(StagedRun run, List<String> kept) {
        if (run.type() == EventType.DELETED) {
            final var keptIds = Set.copyOf(kept);
            submit(run, run.entries().stream().filter(entry -> keptIds.contains(entry.get_id())).toList());
            return;
        }
        try {
            final var entries = TriggerHelper.readEntriesWithRetry(run.dbName(), run.collName(),
                    new LinkedHashSet<>(kept));
            if (entries.isEmpty()) {
                TriggerRunLog.discard(run.runId());
                return;
            }
            submit(run, entries);
        } catch (IOException e) {
            logger.error("Could not read the committed documents to fire trigger '" + run.triggerName() + "' on "
                    + run.dbName() + "|" + run.collName() + "; its staged run replays at the next startup", e);
        }
    }

    private static void narrow(StagedRun run, List<String> kept) {
        if (run.runId() == null || !TriggerRunLog.isEnabled()) {
            return;
        }
        try {
            TriggerRunLog.confirmStaged(run.runId(), Set.copyOf(kept));
        } catch (Exception e) {
            logger.warning("Could not narrow the staged trigger run '" + run.runId() + "' to the documents that"
                    + " were written: " + e.getMessage());
        }
    }

    private static void submitUnclaimed(Map<EventType, Set<String>> landedIds, Map<EventType, Set<String>> claimed,
            String dbName, String collName, String actingUser, int depth) {
        for (final var type : List.of(EventType.CREATED, EventType.UPDATED)) {
            final var claimedOfType = claimed.getOrDefault(type, Set.of());
            final var unclaimed = landedIds.getOrDefault(type, Set.of()).stream()
                    .filter(id -> !claimedOfType.contains(id)).toList();
            if (!unclaimed.isEmpty()) {
                TriggerHelper.afterWriteIds(dbName, collName, type, unclaimed, actingUser, depth);
            }
        }
    }

    private static void submit(StagedRun run, List<DbEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        triggerExecutor.submit(
                new TriggerEvent(run.type(), run.dbName(), run.collName(), run.triggerName(), run.procedureName(),
                        run.batchMode(), entries, run.actingUser(), run.depth(), run.runId(), 1, run.firedAt()));
    }
}
