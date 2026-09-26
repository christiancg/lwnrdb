package org.techhouse.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.TxSession;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.tx.TransactionRecovery;

public final class TwoPhaseParticipant {
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final org.techhouse.listen.ListenManager listenManager = IocContainer
            .get(org.techhouse.listen.ListenManager.class);
    private static final Logger logger = Logger.logFor(TwoPhaseParticipant.class);

    private TwoPhaseParticipant() {
    }

    public static boolean prepare(UUID clientId, String coordinatorAddress, List<String> participants) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null || transaction.isAborted() || coordinator.hasNotTransactionQuorum()) {
            return false;
        }
        try {
            Tx2pcLog.recordParticipantPrepared(transaction.getTransactionId().toString(), coordinatorAddress,
                    participants, new ArrayList<>(transaction.getHeldLocks()));
            return true;
        } catch (Exception e) {
            logger.warning("Failed to prepare transaction: " + e.getMessage());
            return false;
        }
    }

    public static OperationResponse commitPrepared(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.NO_ACTIVE_TRANSACTION);
        }
        if (transaction.isAborted()) {
            clientTracker.clearActiveTransaction(clientId);
            clientTracker.clearTransactionState(clientId);
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.TRANSACTION_NOT_USABLE);
        }
        var fenced = false;
        try {
            final var ops = AdminOperationHelper.readTransactionOps(transaction.getBufferedOpIds());
            if (ops.size() != transaction.getBufferedOpIds().size()) {
                logger.error("Transaction " + transaction.getTransactionId() + " lost "
                        + (transaction.getBufferedOpIds().size() - ops.size()) + " buffered op(s) before commit");
                return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.ERROR_TRANSACTION);
            }
            final var reservedTombstones = coordinator.reserveTransactionTombstones(transaction);
            listenManager.deferNotifications();
            final boolean applied;
            try {
                applied = TransactionRecovery.applyAllWithRetry(ops, transaction.getTransactionId().toString());
            } finally {
                listenManager.flushDeferredNotifications();
            }
            if (!applied) {
                fenced = true;
                return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.TRANSACTION_HALF_APPLIED);
            }
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
            // After the durable commit, so a trigger never observes a transaction that later rolled back.
            TransactionOperationHelper.fireTriggersForCommittedOps(ops,
                    clientTracker.getAuthenticatedUsername(clientId), transaction.getTriggerDepth(), transaction);
            TransactionRecovery.resolveMarkers(transaction.getTransactionId().toString(), true);
            // A replication timeout does not fail the commit; anti-entropy reconciles the lagging replicas.
            coordinator.replicateTransaction(transaction, reservedTombstones);
            return OperationResponse.ok(OperationType.COMMIT_TRANSACTION, "Transaction committed");
        } catch (Exception e) {
            logger.error(OperationType.COMMIT_TRANSACTION + " failed with " + ErrorCode.ERROR_TRANSACTION.getCode(), e);
            fenced = true;
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.TRANSACTION_HALF_APPLIED);
        } finally {
            if (!fenced) {
                TransactionOperationHelper.releaseHeldLocks(transaction);
                clientTracker.clearActiveTransaction(clientId);
                clientTracker.clearTransactionState(clientId);
            }
        }
    }

    // The durable replay takes the slice's collection write locks. A prepared session that never died still
    // holds them on its own thread, so resolving through the session is both the only way to release them and
    // the only way to avoid blocking the recovery worker on them for the life of the process.
    private static Map.Entry<String, TxSession> liveSessionFor(String dtxId) {
        for (final var entry : clientTracker.txSessionsSnapshot().entrySet()) {
            final var transaction = clientTracker.getActiveTransaction(entry.getValue().clientId());
            if (transaction != null && transaction.getTransactionId().toString().equals(dtxId)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean resolvedThroughLiveSession(String dtxId, boolean commit) throws Exception {
        final var entry = liveSessionFor(dtxId);
        if (entry == null) {
            return false;
        }
        final var session = entry.getValue();
        final var result = session.submit(() -> commit
                ? commitPrepared(session.clientId())
                : TransactionOperationHelper.abort(session.clientId())).get();
        if (releasedItsLocks(result)) {
            clientTracker.removeTxSession(entry.getKey());
        }
        return true;
    }

    private static boolean releasedItsLocks(OperationResponse response) {
        return response == null || !ErrorCode.TRANSACTION_HALF_APPLIED.getCode().equals(response.getErrorCode());
    }

    public static void commitPreparedFromDurable(String dtxId, List<String> collections) throws Exception {
        commitPreparedFromDurable(dtxId, collections, 0L);
    }

    public static void commitPreparedFromDurable(String dtxId, List<String> collections, long timeoutMillis)
            throws Exception {
        if (resolvedThroughLiveSession(dtxId, true)) {
            return;
        }
        TransactionRecovery.commitPreparedFromDurable(dtxId, collections, timeoutMillis);
    }

    public static void abortFromDurable(String dtxId) throws Exception {
        if (resolvedThroughLiveSession(dtxId, false)) {
            return;
        }
        TransactionRecovery.abortFromDurable(dtxId);
    }

    public static void resolveFromDurable(String dtxId, boolean commit) throws Exception {
        if (!Tx2pcLog.isPrepared(dtxId)) {
            return;
        }
        if (resolvedThroughLiveSession(dtxId, commit)) {
            return;
        }
        TransactionRecovery.resolveFromDurable(dtxId, commit);
    }
}
