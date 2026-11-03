package org.techhouse.cluster;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.techhouse.cluster.admin.AdminRecordMerge;
import org.techhouse.cluster.admin.AdminRecords;
import org.techhouse.cluster.admin.AdminTombstoneDigest;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.admin.AdminStamp;

public class AdminAntiEntropyService implements MembershipListener {
    private final Logger logger = Logger.logFor(AdminAntiEntropyService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final CoalescingSweep sweep = new CoalescingSweep(logger, "cluster-admin-anti-entropy",
            "Admin anti-entropy", this::reconcile);
    private final AtomicBoolean adminSyncCompleted = new AtomicBoolean(false);
    private final AtomicLong syncGeneration = new AtomicLong();
    private volatile boolean started;
    private volatile boolean wasCoordinator;

    public void start() {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        started = true;
        publishSyncState();
        sweep.startPeriodic(clusterConfig.antiEntropyIntervalMs());
    }

    public void stop() {
        stop(clusterConfig.antiEntropyIntervalMs());
    }

    public void stop(long awaitMillis) {
        started = false;
        wasCoordinator = false;
        adminSyncCompleted.set(false);
        publishSyncState();
        sweep.stop(awaitMillis);
    }

    public boolean hasCompletedAdminSync() {
        return !started || adminSyncCompleted.get();
    }

    public boolean isSyncing() {
        return clusterConfig.isEnabled() && !adminSyncCompleted.get();
    }

    private void publishSyncState() {
        membershipService.setAdminSyncing(!hasCompletedAdminSync());
    }

    @Override
    public void onMembershipChanged(MembershipView view) {
        final var coordinator = ownershipManager.isAdminCoordinator();
        if (clusterConfig.isEnabled() && coordinator && !wasCoordinator) {
            syncGeneration.incrementAndGet();
            adminSyncCompleted.set(false);
            publishSyncState();
        }
        wasCoordinator = coordinator;
        reconcileSoon();
    }

    public void reconcileSoon() {
        if (clusterConfig.isEnabled()) {
            sweep.schedule();
        }
    }

    public void reconcile() {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        try {
            final var generation = syncGeneration.get();
            final var peers = membershipService.membershipView().peers(membershipService.getSelf());
            var completePeers = 0;
            var changed = false;
            for (final var member : peers) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                final var snapshot = requestSnapshot(member.address());
                if (snapshot == null) {
                    continue;
                }
                final var merged = AdminRecordMerge.apply(snapshot.getRecords(), false);
                if (merged.complete()) {
                    completePeers++;
                }
                changed |= merged.changed();
            }
            if (changed) {
                antiEntropyService.reconcileNow();
            }
            if (completePeers + 1 >= ownershipManager.majority() && syncGeneration.get() == generation) {
                adminSyncCompleted.set(true);
            }
        } finally {
            publishSyncState();
        }
    }

    public AdminSnapshotPayload buildSnapshot() throws IOException {
        return new AdminSnapshotPayload(AdminRecords.all());
    }

    public AdminSnapshotPayload buildSnapshot(AdminSnapshotPayload request) throws IOException {
        if (request == null) {
            return buildSnapshot();
        }
        return new AdminSnapshotPayload(
                AdminRecords.snapshotFor(request.getTombstoneBucketCount(), request.getTombstoneBuckets()));
    }

    private AdminSnapshotPayload tombstoneDigest() {
        try {
            final var digest = AdminTombstoneDigest.of(AdminStamp.tombstones());
            return AdminSnapshotPayload.request(digest.bucketCount(), digest.summaries());
        } catch (IOException unreadable) {
            logger.warning("Asking for every admin tombstone this round: the local ones could not be read: "
                    + unreadable.getMessage());
            return null;
        }
    }

    private AdminSnapshotPayload requestSnapshot(NodeAddress address) {
        final var message = PeerRequest.message(ClusterMessageType.ADMIN_SNAPSHOT);
        message.setAdminSnapshot(tombstoneDigest());
        try {
            final var response = pool.request(address, message, clusterConfig.replicationAckTimeoutMs());
            if (response.getType() == ClusterMessageType.ADMIN_SNAPSHOT_ACK) {
                return response.getAdminSnapshot();
            }
            logger.warning("Admin snapshot request to " + address + " not acknowledged: " + response.getErrorMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logger.warning("Admin snapshot request to " + address + " failed: " + e.getMessage());
            return null;
        }
    }
}
