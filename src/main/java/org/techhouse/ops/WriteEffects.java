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

final class WriteEffects {
    static final WriteEffects IMMEDIATE = new WriteEffects(false);
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

    private WriteEffects(boolean deferred) {
        this.deferred = deferred;
        if (deferred) {
            staging = new ArrayList<>();
            afterForce = new ArrayList<>();
            discarded = new ArrayList<>();
            upsertIds = new LinkedHashSet<>();
            deleteVersions = new LinkedHashMap<>();
            takeBacks = new ArrayList<>();
            stagingSink = staging::add;
        } else {
            staging = List.of();
            afterForce = List.of();
            discarded = List.of();
            upsertIds = Set.of();
            deleteVersions = Map.of();
            takeBacks = List.of();
            stagingSink = AdminOperationHelper::saveTriggerRun;
        }
    }

    static WriteEffects grouped() {
        return new WriteEffects(true);
    }

    TriggerRunLog.EntrySink stagingSink() {
        return stagingSink;
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

    boolean replicates() {
        return !upsertIds.isEmpty() || !deleteVersions.isEmpty();
    }

    void flushStaging(String subject) {
        if (staging.isEmpty()) {
            return;
        }
        try {
            AdminOperationHelper.saveTriggerRuns(staging);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted while recording the staged trigger runs of a write group on " + subject
                    + "; they run without a durable record");
        } catch (Exception e) {
            logger.warning("Failed to record the staged trigger runs of a write group on " + subject
                    + "; they run without a durable record: " + e.getMessage());
        }
        faultInjector.at(FaultPoint.GROUP_RUNS_STAGED, subject);
    }

    void runAfterForce() {
        for (final var effect : afterForce) {
            effect.run();
        }
        for (final var staged : discarded) {
            staged.discard();
        }
    }

    void abandon(String subject) {
        final var runIds = new LinkedHashSet<String>();
        for (final var entry : staging) {
            runIds.add(entry.getRunId());
        }
        for (final var runId : runIds) {
            TriggerRunLog.discard(runId);
        }
        for (final var takeBack : takeBacks) {
            try {
                takeBack.run();
            } catch (Exception e) {
                logger.error("Could not retract a delete tombstone of a taken-back write group on " + subject
                        + "; anti-entropy settles it", e);
            }
        }
    }
}
