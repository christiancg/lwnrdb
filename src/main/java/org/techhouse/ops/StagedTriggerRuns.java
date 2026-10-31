package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
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
        final var vanished = new ArrayList<String>();
        final var keptRuns = new ArrayList<KeptRun>();
        for (final var run : runs) {
            final var landedOfType = landedIds.getOrDefault(run.type(), Set.of());
            final var kept = run.ids().stream().filter(landedOfType::contains).toList();
            if (kept.isEmpty()) {
                vanished.add(run.runId());
                continue;
            }
            claimed.computeIfAbsent(run.type(), _ -> new HashSet<>()).addAll(kept);
            if (kept.size() < run.ids().size()) {
                narrow(run, kept);
            }
            keptRuns.add(new KeptRun(run, kept));
        }
        submitWithCurrentEntries(keptRuns, vanished);
        TriggerRunLog.discardAll(nonNull(vanished));
        submitUnclaimed(landedIds, claimed, dbName, collName, actingUser, depth);
    }

    public void submitAll() {
        for (final var run : runs) {
            submit(run, run.entries());
        }
    }

    public void discard() {
        final var runIds = new ArrayList<String>(runs.size());
        for (final var run : runs) {
            runIds.add(run.runId());
        }
        TriggerRunLog.discardAll(nonNull(runIds));
    }

    private static List<String> nonNull(List<String> runIds) {
        return runIds.stream().filter(Objects::nonNull).toList();
    }

    private record KeptRun(StagedRun run, List<String> ids) {
    }

    private static void submitWithCurrentEntries(List<KeptRun> keptRuns, List<String> vanished) {
        Map<String, Map<String, DbEntry>> current;
        try {
            current = currentEntriesOf(keptRuns);
        } catch (IOException e) {
            logger.error("Could not read the committed documents to fire the trigger runs of a write; their staged"
                    + " runs replay at the next startup", e);
            current = null;
        }
        for (final var kept : keptRuns) {
            final var run = kept.run();
            if (run.type() != EventType.DELETED && current == null) {
                continue;
            }
            final var entries = run.type() == EventType.DELETED
                    ? deletedEntriesOf(run, kept.ids())
                    : presentEntriesOf(current.getOrDefault(collectionOf(run), Map.of()), kept.ids());
            if (entries.isEmpty()) {
                vanished.add(run.runId());
            } else {
                submit(run, entries);
            }
        }
    }

    private static Map<String, Map<String, DbEntry>> currentEntriesOf(List<KeptRun> keptRuns) throws IOException {
        final var idsByCollection = new LinkedHashMap<String, Set<String>>();
        final var targets = new HashMap<String, StagedRun>();
        for (final var kept : keptRuns) {
            final var run = kept.run();
            if (run.type() != EventType.DELETED) {
                final var collection = collectionOf(run);
                targets.putIfAbsent(collection, run);
                idsByCollection.computeIfAbsent(collection, _ -> new LinkedHashSet<>()).addAll(kept.ids());
            }
        }
        final var current = new HashMap<String, Map<String, DbEntry>>();
        for (final var collection : idsByCollection.entrySet()) {
            final var target = targets.get(collection.getKey());
            final var byId = new HashMap<String, DbEntry>();
            for (final var entry : TriggerHelper.readEntriesWithRetry(target.dbName(), target.collName(),
                    collection.getValue())) {
                byId.put(entry.get_id(), entry);
            }
            current.put(collection.getKey(), byId);
        }
        return current;
    }

    private static String collectionOf(StagedRun run) {
        return Cache.getCollectionIdentifier(run.dbName(), run.collName());
    }

    private static List<DbEntry> deletedEntriesOf(StagedRun run, List<String> kept) {
        final var keptIds = Set.copyOf(kept);
        return run.entries().stream().filter(entry -> keptIds.contains(entry.get_id())).toList();
    }

    private static List<DbEntry> presentEntriesOf(Map<String, DbEntry> current, List<String> kept) {
        final var entries = new ArrayList<DbEntry>(kept.size());
        for (final var id : kept) {
            final var entry = current.get(id);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
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
