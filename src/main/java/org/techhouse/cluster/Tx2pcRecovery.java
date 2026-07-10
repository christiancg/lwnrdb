package org.techhouse.cluster;

import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;

/**
 * Crash recovery for Phase 5b two-phase commit. Resolves in-doubt transactions left in the durable log by a
 * coordinator or participant crash: a prepared participant asks its coordinator for the decision (commit if
 * the coordinator marker is present, presumed-abort otherwise); a coordinator that recorded a commit re-drives
 * COMMIT to its participants. Runs once at startup and again on every membership change (to retry participants
 * whose coordinator was unreachable). Idempotent.
 */
public class Tx2pcRecovery implements MembershipListener {
    private final Logger logger = Logger.logFor(Tx2pcRecovery.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);

    private enum Decision {
        COMMIT, ABORT, UNKNOWN
    }

    @Override
    public void onMembershipChanged(MembershipView view) {
        recover();
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
                switch (queryDecision(marker.coordinatorAddress(), dtxId)) {
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
        if (Tx2pcLog.isPrepared(dtxId)) {
            final var marker = Tx2pcLog.readParticipantMarker(dtxId);
            TransactionOperationHelper.commitPreparedFromDurable(dtxId,
                    marker != null ? marker.collections() : java.util.List.of());
        }
    }

    private Decision queryDecision(String coordinatorAddress, String dtxId) {
        if (isSelf(coordinatorAddress)) {
            return Tx2pcLog.isCommitted(dtxId) ? Decision.COMMIT : Decision.ABORT;
        }
        final var message = new ClusterMessage(null, ClusterMessageType.TX_STATUS, clusterConfig.secret(),
                membershipService.getSelf(), null);
        message.setTxId(dtxId);
        try {
            final var response = pool.request(NodeAddress.parse(coordinatorAddress), message,
                    clusterConfig.replicationAckTimeoutMs());
            return switch (response.getType()) {
                case COMMIT_TX_ACK -> Decision.COMMIT;
                case ABORT_TX_ACK -> Decision.ABORT;
                default -> Decision.UNKNOWN;
            };
        } catch (Exception e) {
            return Decision.UNKNOWN;
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

    private boolean isSelf(String address) {
        final var self = membershipService.getSelf();
        return self != null && self.address().toString().equals(address);
    }
}
