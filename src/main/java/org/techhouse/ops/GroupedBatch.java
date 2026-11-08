package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.concurrency.SettleWindow;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.resp.OperationResponse;

final class GroupedBatch {
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final Logger logger = Logger.logFor(GroupedBatch.class);

    private enum Settled {
        DURABLE, TAKEN_BACK, UNKNOWN
    }

    private final String dbName;
    private final String collName;
    private final String subject;
    private final List<GroupCommit.GroupedWrite> writes;
    private final WriteEffects effects = WriteEffects.grouped();
    private final List<OperationResponse> responses;
    private final int[] stagedBy;
    private final BitSet unsure = new BitSet();
    private int durablePrefix;
    private int commitsStarted;
    private boolean settled;

    private GroupedBatch(String dbName, String collName, List<GroupCommit.GroupedWrite> writes) {
        this.dbName = dbName;
        this.collName = collName;
        this.subject = Cache.getCollectionIdentifier(dbName, collName);
        this.writes = writes;
        this.responses = new ArrayList<>(writes.size());
        this.stagedBy = new int[writes.size()];
    }

    static void run(String dbName, String collName, List<GroupCommit.GroupedWrite> writes) {
        if (writes.isEmpty()) {
            return;
        }
        try {
            fs.openWriteGroup(dbName, collName);
        } catch (IOException e) {
            logger.warning("Could not open a write group on " + Cache.getCollectionIdentifier(dbName, collName)
                    + "; its writes run one by one: " + e.getMessage());
            writes.forEach(write -> write.serve(write.runAlone()));
            return;
        }
        new GroupedBatch(dbName, collName, writes).run();
    }

    private void run() {
        final int durableWrites;
        try (var ignored = listenManager.openBatch(List.of(subject))) {
            durableWrites = applyAndSettle();
        }
        for (var index = 0; index < writes.size(); index++) {
            writes.get(index).serve(answerOf(index, durableWrites));
        }
    }

    private OperationResponse answerOf(int index, int durableWrites) {
        final var write = writes.get(index);
        if (index >= responses.size()) {
            return unsure.get(index) ? write.unknownOutcome() : write.failure();
        }
        final var response = responses.get(index);
        if (response.getStatus() != OperationStatus.OK) {
            return response;
        }
        if (index >= durableWrites) {
            return write.failure();
        }
        return unsure.get(index) ? write.unknownOutcome() : response;
    }

    private int applyAndSettle() {
        final boolean clustered;
        final boolean forcedFirst;
        try {
            prepareAndCommit();
            clustered = clusterConfig.isEnabled();
            forcedFirst = clustered && force();
        } catch (Throwable thrown) {
            return afterThrow(thrown);
        }
        try (var window = !clustered || forcedFirst
                ? locks.openSettleWindow(dbName, collName)
                : SettleWindow.exclusive()) {
            try {
                return settleInWindow(window, clustered, forcedFirst);
            } catch (Throwable thrown) {
                window.fail();
                return afterThrow(thrown);
            }
        }
    }

    private void prepareAndCommit() {
        final var commits = new ArrayList<GroupCommit.GroupedCommit>(writes.size());
        for (var index = 0; index < writes.size(); index++) {
            commits.add(writes.get(index).prepare(effects));
            stagedBy[index] = effects.stagedCount();
        }
        effects.flushStaging(subject);
        for (var index = 0; index < writes.size(); index++) {
            commitsStarted++;
            responses.add(writes.get(index).commit(commits.get(index)));
            if (reopenAfterRewrite()) {
                if (!fs.forceGroupTombstones(dbName, collName)) {
                    unsure.set(durablePrefix, index + 1);
                }
                durablePrefix = index + 1;
                effects.markDurablePrefix(stagedBy[index]);
            }
        }
    }

    private int settleInWindow(SettleWindow window, boolean clustered, boolean forcedFirst) {
        if (!(clustered ? forcedFirst : force())) {
            window.fail();
        }
        if (settle()) {
            settled = true;
            replicate(effects.upsertIds(), effects.deleteVersions(), writes.size());
            effects.runAfterForce();
            return writes.size();
        }
        window.fail();
        return takenBack();
    }

    private int takenBack() {
        forgetTakenBack();
        replicate(effects.durablePrefixUpsertIds(), effects.durablePrefixDeleteVersions(), durablePrefix);
        effects.abandon(subject);
        return durablePrefix;
    }

    private int afterThrow(Throwable thrown) {
        logger.error("A write batch on " + subject + " threw; its group is settled under the collection lock before"
                + " its writes are answered", thrown);
        final var committed = responses.size();
        final var thrownMidCommit = commitsStarted > committed;
        if (thrownMidCommit) {
            unsure.set(committed);
        }
        if (settled) {
            unsure.set(0, committed);
            return committed;
        }
        final var landed = settleAfterThrow();
        if (landed == Settled.UNKNOWN) {
            unsure.set(durablePrefix, Math.max(durablePrefix, committed));
            return committed;
        }
        if (landed == Settled.TAKEN_BACK) {
            return takenBack();
        }
        final var through = thrownMidCommit ? committed : committed - 1;
        effects.markDurablePrefix(through >= 0 ? stagedBy[through] : 0);
        replicate(effects.durablePrefixUpsertIds(), effects.durablePrefixDeleteVersions(), committed);
        effects.abandon(subject);
        return committed;
    }

    private Settled settleAfterThrow() {
        try {
            fs.forceGroupTombstones(dbName, collName);
            return fs.settleWriteGroup(dbName, collName) ? Settled.DURABLE : Settled.TAKEN_BACK;
        } catch (Throwable thrown) {
            logger.error("Could not settle the write group of " + subject + " after its batch threw; its writes are"
                    + " answered with an unknown outcome", thrown);
            return Settled.UNKNOWN;
        }
    }

    static void closeAfterThrow(String dbName, String collName) {
        final var relocked = !locks.holdsCollectionLock(dbName, collName) && relock(dbName, collName);
        if (!relocked && !locks.holdsCollectionLock(dbName, collName)) {
            logger.error("Could not settle the write group of " + Cache.getCollectionIdentifier(dbName, collName)
                    + " after its batch threw: the collection lock could not be taken back; the next write or"
                    + " checkpoint settles it");
            return;
        }
        try {
            fs.settleWriteGroup(dbName, collName);
        } catch (IOException | RuntimeException e) {
            logger.error("Could not settle the write group of " + Cache.getCollectionIdentifier(dbName, collName)
                    + " after its batch threw", e);
        }
    }

    private static boolean relock(String dbName, String collName) {
        try {
            locks.lock(dbName, collName);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean reopenAfterRewrite() {
        try {
            return fs.reopenWriteGroupIfEnded(dbName, collName);
        } catch (IOException e) {
            logger.warning("Could not reopen the write group of " + subject + " after a page rewrite ended it; the"
                    + " rest of its writes are forced one by one: " + e.getMessage());
            return false;
        }
    }

    private boolean force() {
        try {
            return fs.forceWriteGroup(dbName, collName);
        } catch (IOException e) {
            logger.error("Could not force the write group of " + subject, e);
            return false;
        }
    }

    private boolean settle() {
        try {
            return fs.settleWriteGroup(dbName, collName);
        } catch (IOException e) {
            logger.error("Could not settle the write group of " + subject, e);
            return false;
        }
    }

    private void replicate(List<String> upsertIds, Map<String, Long> deleteVersions, int replicatedWrites) {
        if (upsertIds.isEmpty() && deleteVersions.isEmpty()) {
            return;
        }
        final var outcome = coordinator.replicateGroup(dbName, collName, upsertIds, deleteVersions);
        if (outcome == ReplicationOutcome.NOT_CLUSTERED) {
            return;
        }
        for (var index = 0; index < Math.min(replicatedWrites, responses.size()); index++) {
            responses.set(index, ClusterWriteHelper.afterGroupReplication(responses.get(index), outcome));
        }
    }

    private void forgetTakenBack() {
        cache.evictCollection(dbName, collName);
        try {
            PageOccupancyReconciler.reconcile(dbName, collName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warning("Could not reconcile the page rows of " + subject
                    + " after a failed write group; the next startup rebuilds them: " + e.getMessage());
        }
    }
}
