package org.techhouse.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class WriteEffects {
    static final WriteEffects IMMEDIATE = new WriteEffects(false, false);
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private static final Logger logger = Logger.logFor(WriteEffects.class);

    @FunctionalInterface
    interface TakeBack {
        void run() throws Exception;
    }

    private final boolean deferred;
    private final TriggerRunLog.EntrySink stagingSink;
    private final List<AdminTriggerRunEntry> staging;
    private final List<Runnable> afterForce;
    private final List<StagedTriggerRuns> discarded;
    private final Set<String> upsertIds;
    private final Map<String, Long> deleteVersions;
    private final List<TakeBack> takeBacks;
    private DurablePrefix durablePrefix = DurablePrefix.NONE;

    private record DurablePrefix(int staged, int afterForce, int discarded, Set<String> upsertIds,
            Map<String, Long> deleteVersions, int takeBacks) {
        static final DurablePrefix NONE = new DurablePrefix(0, 0, 0, Set.of(), Map.of(), 0);
    }

    private WriteEffects(boolean deferred, boolean buffered) {
        this.deferred = deferred;
        staging = buffered ? new ArrayList<>() : List.of();
        stagingSink = buffered ? staging::add : AdminOperationHelper::saveTriggerRun;
        if (deferred) {
            afterForce = new ArrayList<>();
            discarded = new ArrayList<>();
            upsertIds = new LinkedHashSet<>();
            deleteVersions = new LinkedHashMap<>();
            takeBacks = new ArrayList<>();
        } else {
            afterForce = List.of();
            discarded = List.of();
            upsertIds = Set.of();
            deleteVersions = Map.of();
            takeBacks = List.of();
        }
    }

    static WriteEffects grouped() {
        return new WriteEffects(true, true);
    }

    public static WriteEffects batched() {
        return new WriteEffects(false, true);
    }

    TriggerRunLog.EntrySink stagingSink() {
        return stagingSink;
    }

    int stagedCount() {
        return staging.size();
    }

    void markDurablePrefix(int stagedByPrefix) {
        durablePrefix = new DurablePrefix(stagedByPrefix, afterForce.size(), discarded.size(),
                new LinkedHashSet<>(upsertIds), new LinkedHashMap<>(deleteVersions), takeBacks.size());
    }

    List<String> durablePrefixUpsertIds() {
        return List.copyOf(durablePrefix.upsertIds());
    }

    Map<String, Long> durablePrefixDeleteVersions() {
        return durablePrefix.deleteVersions();
    }

    void submitLanded(StagedTriggerRuns staged, Map<EventType, Set<String>> landedIds, String dbName, String collName,
            String actingUser, int depth) {
        if (deferred) {
            afterForce.add(() -> staged.submitLanded(landedIds, dbName, collName, actingUser, depth));
        } else {
            staged.submitLanded(landedIds, dbName, collName, actingUser, depth);
        }
    }

    void discard(StagedTriggerRuns staged) {
        if (deferred) {
            discarded.add(staged);
        } else {
            staged.discard();
        }
    }

    boolean defersReplication() {
        return deferred;
    }

    void replicateUpsertLater(String id) {
        upsertIds.add(id);
    }

    void replicateDeleteLater(String id, Long version, TakeBack retract) {
        deleteVersions.put(id, version);
        takeBacks.add(retract);
    }

    List<String> upsertIds() {
        return List.copyOf(upsertIds);
    }

    Map<String, Long> deleteVersions() {
        return deleteVersions;
    }

    public void flushStaging(String subject) {
        if (staging.isEmpty()) {
            return;
        }
        writeStaging(subject);
        if (deferred) {
            faultInjector.at(FaultPoint.GROUP_RUNS_STAGED, subject);
        }
    }

    private void writeStaging(String subject) {
        final var runs = TriggerRunLog.chunksByRun(staging);
        try {
            AdminOperationHelper.saveTriggerRuns(staging, TriggerRunLog.recordIdsForAll(runs.keySet()));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted while recording the staged trigger runs of a write on " + subject
                    + "; they run without a durable record");
            return;
        } catch (Exception e) {
            logger.warning("Failed to record the staged trigger runs of a write on " + subject
                    + " in one record; recording them one run at a time: " + e.getMessage());
        }
        var unrecorded = 0;
        for (final var run : runs.entrySet()) {
            if (!TriggerRunLog.rewriteRun(run.getKey(), run.getValue())) {
                unrecorded++;
            }
        }
        if (unrecorded > 0) {
            logger.warning(unrecorded + " of the " + runs.size() + " staged trigger runs of a write on " + subject
                    + " could not be recorded either; they run without a durable record");
        }
    }

    void runAfterForce() {
        runAfterForce(afterForce.size(), discarded.size());
    }

    private void runAfterForce(int effects, int discards) {
        for (final var effect : afterForce.subList(0, effects)) {
            effect.run();
        }
        for (final var staged : discarded.subList(0, discards)) {
            staged.discard();
        }
    }

    void abandon(String subject) {
        runAfterForce(durablePrefix.afterForce(), durablePrefix.discarded());
        TriggerRunLog.discardAll(
                TriggerRunLog.chunksByRun(staging.subList(durablePrefix.staged(), staging.size())).keySet());
        for (final var takeBack : takeBacks.subList(durablePrefix.takeBacks(), takeBacks.size())) {
            try {
                takeBack.run();
            } catch (Exception e) {
                logger.error("Could not retract a delete tombstone of a taken-back write group on " + subject
                        + "; anti-entropy settles it", e);
            }
        }
    }
}
