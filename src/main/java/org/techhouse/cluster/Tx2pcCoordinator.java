package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.resp.OperationResponse;

/**
 * The commit decision is recorded durably ({@link Tx2pcLog}) before COMMIT is driven, which is what
 * recovery relies on; any no vote or unreachable participant aborts them all.
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
        final var participants = new ArrayList<>(remotes);
        if (local) {
            participants.add(selfAddress);
        }

        final var yes = prepareAll(clientId, sessionId, dtxId, local, remotes, selfAddress, participants);
        if (!yes) {
            abortAll(clientId, sessionId, dtxId, local, remotes);
            finishEdge(clientId, local);
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.TRANSACTION_ABORTED);
        }
        try {
            Tx2pcLog.recordCoordinatorCommit(dtxId, participants);
        } catch (Exception e) {
            logger.error("Failed to record the 2PC commit decision for " + dtxId, e);
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.ERROR_TRANSACTION);
        }
        var localApplied = true;
        if (local) {
            final var localResult = TransactionOperationHelper.commitPrepared(clientId);
            localApplied = localResult.getStatus() == OperationStatus.OK;
            if (!localApplied) {
                logger.error("The coordinator's own slice of " + dtxId
                        + " failed to apply; keeping the commit marker for recovery to re-drive");
            }
        }
        final var allAcked = sendToAll(remotes, ClusterMessageType.COMMIT_TX, sessionId, dtxId,
                ClusterMessageType.COMMIT_TX_ACK, null);
        if (allAcked && localApplied) {
            deleteCoordinatorMarkerQuietly(dtxId);
        } else {
            logger.warning("Not every participant acknowledged the commit of " + dtxId
                    + "; keeping the coordinator marker so recovery can re-drive it");
        }
        finishEdge(clientId, local);
        return localApplied
                ? OperationResponse.ok(OperationType.COMMIT_TRANSACTION, "Transaction committed")
                : new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.TRANSACTION_INDETERMINATE);
    }

    public OperationResponse forceResolve(String dtxId, boolean commit) {
        try {
            if (commit && !Tx2pcLog.isCommitted(dtxId)) {
                Tx2pcLog.recordCoordinatorCommit(dtxId, List.of());
            }
            TransactionOperationHelper.resolveFromDurable(dtxId, commit);
        } catch (Exception e) {
            logger.error("Failed to force-resolve transaction " + dtxId, e);
            return new OperationResponse(OperationType.RESOLVE_TRANSACTION, ErrorCode.ERROR_TRANSACTION);
        }
        final var self = membershipService.getSelf();
        final var type = commit ? ClusterMessageType.COMMIT_TX : ClusterMessageType.ABORT_TX;
        final var ack = commit ? ClusterMessageType.COMMIT_TX_ACK : ClusterMessageType.ABORT_TX_ACK;
        for (final var member : membershipService.membershipView().peers(self)) {
            send(member.address().toString(), type, dtxId, dtxId, ack, null);
        }
        return OperationResponse.ok(OperationType.RESOLVE_TRANSACTION,
                "Transaction " + (commit ? "committed" : "aborted"));
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
        return OperationResponse.ok(OperationType.ROLLBACK_TRANSACTION, "Transaction rolled back");
    }

    private boolean prepareAll(UUID clientId, String sessionId, String dtxId, boolean local, ArrayList<String> remotes,
            String selfAddress, List<String> participants) {
        if (local && !TransactionOperationHelper.prepare(clientId, selfAddress, participants)) {
            return false;
        }
        return sendToAll(remotes, ClusterMessageType.PREPARE_TX, sessionId, dtxId, ClusterMessageType.PREPARE_TX_ACK,
                participants);
    }

    private boolean sendToAll(List<String> addresses, ClusterMessageType type, String sessionId, String dtxId,
            ClusterMessageType expectedAck, List<String> participants) {
        if (addresses.isEmpty()) {
            return true;
        }
        final var allAcked = new AtomicBoolean(true);
        final var done = new CountDownLatch(addresses.size());
        for (final var address : addresses) {
            Thread.ofVirtual().name("cluster-2pc").start(() -> {
                try {
                    if (!send(address, type, sessionId, dtxId, expectedAck, participants)) {
                        allAcked.set(false);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            if (!done.await(clusterConfig.replicationAckTimeoutMs(), TimeUnit.MILLISECONDS)) {
                allAcked.set(false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            allAcked.set(false);
        }
        return allAcked.get();
    }

    private void abortAll(UUID clientId, String sessionId, String dtxId, boolean local, ArrayList<String> remotes) {
        if (local) {
            TransactionOperationHelper.abort(clientId);
        }
        sendToAll(remotes, ClusterMessageType.ABORT_TX, sessionId, dtxId, ClusterMessageType.ABORT_TX_ACK, null);
    }

    private void finishEdge(UUID clientId, boolean local) {
        if (!local) {
            clientTracker.clearActiveTransaction(clientId);
        }
        clientTracker.clearTransactionState(clientId);
    }

    private boolean send(String address, ClusterMessageType type, String sessionId, String dtxId,
            ClusterMessageType expectedAck, List<String> participants) {
        final var message = new ClusterMessage(null, type, clusterConfig.secret(), membershipService.getSelf(), null);
        message.setTxSessionId(sessionId);
        message.setTxId(dtxId);
        message.setTxParticipants(participants);
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
