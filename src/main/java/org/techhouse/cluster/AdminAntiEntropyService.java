package org.techhouse.cluster;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.techhouse.cluster.admin.AdminRecordMerge;
import org.techhouse.cluster.admin.AdminRecords;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public class AdminAntiEntropyService implements MembershipListener {
    private final Logger logger = Logger.logFor(AdminAntiEntropyService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final CoalescingSweep sweep = new CoalescingSweep(logger, "cluster-admin-anti-entropy",
            "Admin anti-entropy", this::reconcile);
    private final AtomicBoolean adminSyncCompleted = new AtomicBoolean(false);
    private volatile boolean started;

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
        adminSyncCompleted.set(false);
        publishSyncState();
        sweep.stop(awaitMillis);
    }

    public boolean hasCompletedAdminSync() {
        return !started || adminSyncCompleted.get();
    }

    public boolean hasNotSyncedSinceStart() {
        return clusterConfig.isEnabled() && !adminSyncCompleted.get();
    }

    private void publishSyncState() {
        membershipService.setAdminSyncing(!hasCompletedAdminSync());
    }

    @Override
    public void onMembershipChanged(MembershipView view) {
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
            final var peers = membershipService.membershipView().peers(membershipService.getSelf());
            var synced = peers.isEmpty();
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
                synced |= merged.complete();
                changed |= merged.changed();
            }
            if (changed) {
                antiEntropyService.reconcileNow();
            }
            if (synced) {
                adminSyncCompleted.set(true);
            }
        } finally {
            publishSyncState();
        }
    }

    public AdminSnapshotPayload buildSnapshot() throws IOException {
        return new AdminSnapshotPayload(AdminRecords.all());
    }

    private AdminSnapshotPayload requestSnapshot(NodeAddress address) {
        final var message = PeerRequest.message(ClusterMessageType.ADMIN_SNAPSHOT);
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
