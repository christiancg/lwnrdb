package org.techhouse.cluster;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.ops.Tx2pcLog;

/**
 * Crash recovery for Phase 5b two-phase commit. A prepared participant asks its coordinator for the decision
 * (present coordinator marker ⇒ commit, otherwise presumed-abort); if the coordinator is unreachable it falls
 * back to <b>cooperative termination</b> — polling the other participants and adopting any decision one of
 * them already reached. A coordinator that recorded a commit re-drives COMMIT to its participants. Runs at
 * startup, on every membership change, and on a periodic sweep (which also GCs old outcome markers and warns
 * about long in-doubt transactions). Idempotent.
 */
public class Tx2pcRecovery implements MembershipListener {
    private final Logger logger = Logger.logFor(Tx2pcRecovery.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private ScheduledExecutorService sweeper;

    private enum Decision {
        COMMIT, ABORT, UNKNOWN
    }

    @Override
    public void onMembershipChanged(MembershipView view) {
        recover();
    }

    // Starts the periodic recovery/GC sweep (retries in-doubt transactions without waiting for a membership
    // change, GCs resolved-outcome markers, and warns about long in-doubt transactions).
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
                    case COMMIT -> TransactionOperationHelper.commitPreparedFromDurable(dtxId, marker.collections());
                    case ABORT -> TransactionOperationHelper.abortFromDurable(dtxId);
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
        TransactionOperationHelper.resolveFromDurable(dtxId, true);
    }

    // Determines the outcome for a prepared-but-uncertain transaction. The coordinator is authoritative when
    // reachable (commit marker ⇒ commit, otherwise presumed-abort); only when it is unreachable do we fall back
    // to cooperative termination among the other participants.
    private Decision resolve(String coordinatorAddress, java.util.List<String> participants, String dtxId) {
        final var fromCoordinator = statusFrom(coordinatorAddress, dtxId);
        if (fromCoordinator != null) {
            return fromCoordinator == Tx2pcLog.Status.COMMITTED ? Decision.COMMIT : Decision.ABORT;
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

    // This node's or a peer's knowledge of the transaction, or null when the peer is unreachable.
    private Tx2pcLog.Status statusFrom(String address, String dtxId) {
        if (isSelf(address)) {
            try {
                return Tx2pcLog.status(dtxId);
            } catch (Exception e) {
                return null;
            }
        }
        final var message = new ClusterMessage(null, ClusterMessageType.TX_STATUS, clusterConfig.secret(),
                membershipService.getSelf(), null);
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
        final var message = new ClusterMessage(null, ClusterMessageType.COMMIT_TX, clusterConfig.secret(),
                membershipService.getSelf(), null);
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
