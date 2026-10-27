package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    private final String dbName;
    private final String collName;
    private final String subject;
    private final List<GroupCommit.GroupedWrite> writes;
    private final WriteEffects effects = WriteEffects.grouped();

    private GroupedBatch(String dbName, String collName, List<GroupCommit.GroupedWrite> writes) {
        this.dbName = dbName;
        this.collName = collName;
        this.subject = Cache.getCollectionIdentifier(dbName, collName);
        this.writes = writes;
    }

    static void run(String dbName, String collName, List<GroupCommit.GroupedWrite> writes) {
        if (writes.size() < 2) {
            writes.forEach(write -> write.serve(write.runAlone()));
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
        final var responses = new ArrayList<OperationResponse>(writes.size());
        final boolean durable;
        try (var ignored = listenManager.openBatch(List.of(subject))) {
            durable = applyAndSettle(responses);
        }
        for (var index = 0; index < writes.size(); index++) {
            final var write = writes.get(index);
            final var response = responses.get(index);
            write.serve(durable || response.getStatus() != OperationStatus.OK ? response : write.failure());
        }
    }

    private boolean applyAndSettle(List<OperationResponse> responses) {
        final var commits = new ArrayList<GroupCommit.GroupedCommit>(writes.size());
        for (final var write : writes) {
            commits.add(write.prepare(effects));
        }
        effects.flushStaging(subject);
        for (var index = 0; index < writes.size(); index++) {
            responses.add(writes.get(index).commit(commits.get(index)));
        }
        final var clustered = clusterConfig.isEnabled();
        final var forcedFirst = clustered && force();
        try (var window = !clustered || forcedFirst
                ? locks.openSettleWindow(dbName, collName)
                : SettleWindow.exclusive()) {
            if (!(clustered ? forcedFirst : force())) {
                window.fail();
            }
            final var durable = settle();
            if (durable) {
                replicate(responses);
                effects.runAfterForce();
            } else {
                window.fail();
                forgetTakenBack();
                effects.abandon(subject);
            }
            return durable;
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

    private void replicate(List<OperationResponse> responses) {
        if (!effects.replicates()) {
            return;
        }
        final var outcome = coordinator.replicateGroup(dbName, collName, effects.upsertIds(), effects.deleteVersions());
        if (outcome == ReplicationOutcome.NOT_CLUSTERED) {
            return;
        }
        responses.replaceAll(response -> ClusterWriteHelper.afterGroupReplication(response, outcome));
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
