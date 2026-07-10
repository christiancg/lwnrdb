package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.UUID;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.resp.CommitTransactionResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.RollbackTransactionResponse;

/**
 * Edge-side two-phase-commit coordinator for a transaction spanning more than one owner (Phase 5b). Runs
 * PREPARE across all participants (the local node if it holds a slice, plus each remote owner), and on a
 * unanimous yes durably records the commit decision ({@link Tx2pcLog}) before driving COMMIT; any no vote or
 * unreachable participant aborts them all. The single-owner case is handled by the 5a fast path in
 * {@link ClusterRouter} and never reaches here.
 */
public class Tx2pcCoordinator {
    private final Logger logger = Logger.logFor(Tx2pcCoordinator.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);

    public OperationResponse commit(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.NO_ACTIVE_TRANSACTION);
        }
        final var dtxId = transaction.getTransactionId().toString();
        final var sessionId = clientId.toString();
        final var local = clientTracker.hasLocalSlice(clientId);
        final var remotes = new ArrayList<>(clientTracker.transactionParticipants(clientId));
        final var selfAddress = membershipService.getSelf().address().toString();

        final var yes = prepareAll(clientId, sessionId, dtxId, local, remotes, selfAddress);
        if (!yes) {
            abortAll(clientId, sessionId, dtxId, local, remotes);
            finishEdge(clientId, local);
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.TRANSACTION_ABORTED);
        }
        final var participants = new ArrayList<>(remotes);
        if (local) {
            participants.add(selfAddress);
        }
        try {
            Tx2pcLog.recordCoordinatorCommit(dtxId, participants);
        } catch (Exception e) {
            logger.error("Failed to record the 2PC commit decision for " + dtxId, e);
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.ERROR_TRANSACTION);
        }
        if (local) {
            TransactionOperationHelper.commitPrepared(clientId);
        }
        for (final var address : remotes) {
            send(address, ClusterMessageType.COMMIT_TX, sessionId, dtxId, ClusterMessageType.COMMIT_TX_ACK);
        }
        deleteCoordinatorMarkerQuietly(dtxId);
        finishEdge(clientId, local);
        return new CommitTransactionResponse("Transaction committed");
    }

    public OperationResponse rollback(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return new OperationResponse(OperationType.ROLLBACK_TRANSACTION, ErrorCode.NO_ACTIVE_TRANSACTION);
        }
        final var dtxId = transaction.getTransactionId().toString();
        final var sessionId = clientId.toString();
        final var local = clientTracker.hasLocalSlice(clientId);
        final var remotes = new ArrayList<>(clientTracker.transactionParticipants(clientId));
        abortAll(clientId, sessionId, dtxId, local, remotes);
        finishEdge(clientId, local);
        return new RollbackTransactionResponse("Transaction rolled back");
    }

    private boolean prepareAll(UUID clientId, String sessionId, String dtxId, boolean local, ArrayList<String> remotes,
            String selfAddress) {
        if (local && !TransactionOperationHelper.prepare(clientId, selfAddress)) {
            return false;
        }
        for (final var address : remotes) {
            if (!send(address, ClusterMessageType.PREPARE_TX, sessionId, dtxId, ClusterMessageType.PREPARE_TX_ACK)) {
                return false;
            }
        }
        return true;
    }

    private void abortAll(UUID clientId, String sessionId, String dtxId, boolean local, ArrayList<String> remotes) {
        if (local) {
            TransactionOperationHelper.abort(clientId);
        }
        for (final var address : remotes) {
            send(address, ClusterMessageType.ABORT_TX, sessionId, dtxId, ClusterMessageType.ABORT_TX_ACK);
        }
    }

    // Clears the edge coordinator's own transaction state once 2PC has finished. For a local participant the
    // commitPrepared/abort call already cleared it; this covers the remote-only case whose in-memory marker
    // transaction would otherwise linger.
    private void finishEdge(UUID clientId, boolean local) {
        if (!local) {
            clientTracker.clearActiveTransaction(clientId);
        }
        clientTracker.clearTransactionState(clientId);
    }

    private boolean send(String address, ClusterMessageType type, String sessionId, String dtxId,
            ClusterMessageType expectedAck) {
        final var message = new ClusterMessage(null, type, clusterConfig.secret(), membershipService.getSelf(), null);
        message.setTxSessionId(sessionId);
        message.setTxId(dtxId);
        try {
            final var response = pool.request(NodeAddress.parse(address), message,
                    clusterConfig.replicationAckTimeoutMs());
            return response.getType() == expectedAck;
        } catch (Exception e) {
            logger.warning("2PC " + type + " to " + address + " failed: " + e.getMessage());
            return false;
        }
    }

    private void deleteCoordinatorMarkerQuietly(String dtxId) {
        try {
            Tx2pcLog.deleteCoordinatorMarker(dtxId);
        } catch (Exception e) {
            logger.warning("Failed to delete the 2PC commit marker for " + dtxId + ": " + e.getMessage());
        }
    }
}
