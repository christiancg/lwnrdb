package org.techhouse.cluster;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;

public class Tx2pcRecovery implements MembershipListener {
    private final Logger logger = Logger.logFor(Tx2pcRecovery.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final AtomicBoolean pendingRecovery = new AtomicBoolean();
    private final ExecutorService membershipWorker = Executors
            .newSingleThreadExecutor(Thread.ofVirtual().name("tx2pc-membership-", 0).factory());
    private ScheduledExecutorService sweeper;

    private enum Decision {
        COMMIT, ABORT, UNKNOWN
    }

    @Override
    public void onMembershipChanged(MembershipView view) {
        if (!pendingRecovery.compareAndSet(false, true)) {
            return;
        }
        try {
            membershipWorker.execute(() -> {
                pendingRecovery.set(false);
                try {
                    recover();
                } catch (Exception e) {
                    logger.warning("Membership-triggered transaction recovery failed: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException rejected) {
            pendingRecovery.set(false);
            logger.info("Skipping membership-triggered transaction recovery: this node is shutting down");
        }
    }

    public void start() {
        if (!clusterConfig.isEnabled() || clusterConfig.antiEntropyIntervalMs() <= 0) {
            return;
        }
        sweeper = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("tx2pc-recovery-", 0).factory());
        final var interval = clusterConfig.antiEntropyIntervalMs();
        sweeper.scheduleWithFixedDelay(this::sweep, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        membershipWorker.shutdownNow();
    }

    private void sweep() {
        try {
            recover();
            Tx2pcLog.garbageCollectOutcomes(clusterConfig.tombstoneRetentionMs());
            TriggerRunRecovery.warnAboutStrandedRuns();
            TriggerRunRecovery.garbageCollect();
            warnLongInDoubt();
        } catch (Exception e) {
            logger.warning("Transaction recovery sweep failed: " + e.getMessage());
        }
    }

    public void recover() {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        recoverParticipants();
        recoverCoordinator();
    }

    private void recoverParticipants() {
        for (final var dtxId : Tx2pcLog.preparedDtxIds()) {
            try {
                final var marker = Tx2pcLog.readParticipantMarker(dtxId);
                if (marker == null) {
                    continue;
                }
                switch (resolve(marker.coordinatorAddress(), marker.participants(), dtxId)) {
                    case COMMIT -> TwoPhaseParticipant.commitPreparedFromDurable(dtxId, marker.collections());
                    case ABORT -> TwoPhaseParticipant.abortFromDurable(dtxId);
                    default -> logger.info("Transaction " + dtxId + " still in-doubt; will retry");
                }
            } catch (Exception e) {
                logger.warning("Failed to recover prepared transaction " + dtxId + ": " + e.getMessage());
            }
        }
    }

    private void recoverCoordinator() {
        for (final var dtxId : Tx2pcLog.committedDtxIds()) {
            try {
                var allResolved = true;
                for (final var address : Tx2pcLog.readCoordinatorParticipants(dtxId)) {
                    if (isSelf(address)) {
                        resolveLocalCommitted(dtxId);
                    } else if (!sendCommit(address, dtxId)) {
                        allResolved = false;
                    }
                }
                if (allResolved) {
                    Tx2pcLog.deleteCoordinatorMarker(dtxId);
                }
            } catch (Exception e) {
                logger.warning("Failed to re-drive committed transaction " + dtxId + ": " + e.getMessage());
            }
        }
    }

    private void resolveLocalCommitted(String dtxId) throws Exception {
        TwoPhaseParticipant.resolveFromDurable(dtxId, true);
    }

    private Decision resolve(String coordinatorAddress, java.util.List<String> participants, String dtxId) {
        final var fromCoordinator = statusFrom(coordinatorAddress, dtxId);
        if (fromCoordinator != null) {
            return switch (fromCoordinator) {
                case COMMITTED -> Decision.COMMIT;
                case ABORTED, NO_RECORD -> Decision.ABORT;
                case PREPARED, UNKNOWN -> Decision.UNKNOWN;
            };
        }
        for (final var peer : participants) {
            if (isSelf(peer) || peer.equals(coordinatorAddress)) {
                continue;
            }
            final var peerStatus = statusFrom(peer, dtxId);
            if (peerStatus == Tx2pcLog.Status.COMMITTED) {
                return Decision.COMMIT;
            }
            if (peerStatus == Tx2pcLog.Status.ABORTED) {
                return Decision.ABORT;
            }
        }
        return Decision.UNKNOWN;
    }

    private Tx2pcLog.Status statusFrom(String address, String dtxId) {
        if (isSelf(address)) {
            try {
                return Tx2pcLog.status(dtxId);
            } catch (Exception e) {
                return null;
            }
        }
        final var message = PeerRequest.message(ClusterMessageType.TX_STATUS);
        message.setTxId(dtxId);
        try {
            final var response = pool.request(NodeAddress.parse(address), message,
                    clusterConfig.replicationAckTimeoutMs());
            if (response.getType() == ClusterMessageType.TX_STATUS_ACK && response.getTxStatus() != null) {
                return Tx2pcLog.Status.valueOf(response.getTxStatus());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean sendCommit(String address, String dtxId) {
        final var message = PeerRequest.message(ClusterMessageType.COMMIT_TX);
        message.setTxSessionId(dtxId);
        message.setTxId(dtxId);
        try {
            return pool.request(NodeAddress.parse(address), message, clusterConfig.replicationAckTimeoutMs())
                    .getType() == ClusterMessageType.COMMIT_TX_ACK;
        } catch (Exception e) {
            return false;
        }
    }

    private void warnLongInDoubt() {
        final var threshold = clusterConfig.deadTimeoutMs();
        final var now = System.currentTimeMillis();
        for (final var dtxId : Tx2pcLog.preparedDtxIds()) {
            try {
                final var marker = Tx2pcLog.readParticipantMarker(dtxId);
                if (marker != null && marker.preparedAt() > 0 && now - marker.preparedAt() > threshold) {
                    logger.warning("Transaction " + dtxId + " has been in-doubt for " + (now - marker.preparedAt())
                            + "ms; use RESOLVE_TRANSACTION to force a decision if its coordinator is gone");
                }
            } catch (Exception e) {
                logger.warning("Failed to inspect in-doubt transaction " + dtxId + ": " + e.getMessage());
            }
        }
    }

    private boolean isSelf(String address) {
        final var self = membershipService.getSelf();
        return self != null && self.address().toString().equals(address);
    }
}
