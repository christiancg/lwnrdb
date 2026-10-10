package org.techhouse.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.TxSession;
import org.techhouse.ex.DurableReplayIncompleteException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.tx.TransactionRecovery;

public final class TwoPhaseParticipant {
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final Logger logger = Logger.logFor(TwoPhaseParticipant.class);

    private TwoPhaseParticipant() {
    }

    public static boolean prepare(UUID clientId, String coordinatorAddress, List<String> participants) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null || transaction.isAborted() || coordinator.hasNotTransactionQuorum()
                || TransactionOperationHelper.ownershipMoved(transaction)) {
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
        return TransactionOperationHelper.commitDecided(clientId);
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

    private static boolean resolvedThroughLiveSession(String dtxId, boolean commit, long timeoutMillis)
            throws Exception {
        final var entry = liveSessionFor(dtxId);
        if (entry == null) {
            return false;
        }
        final var session = entry.getValue();
        final var future = session.submit(() -> commit
                ? commitPrepared(session.clientId())
                : TransactionOperationHelper.abort(session.clientId()));
        final var result = timeoutMillis > 0 ? future.get(timeoutMillis, TimeUnit.MILLISECONDS) : future.get();
        if (clientTracker.getActiveTransaction(session.clientId()) == null) {
            clientTracker.removeTxSession(entry.getKey());
        }
        if (!resolved(result)) {
            throw new DurableReplayIncompleteException(dtxId, result.getMessage());
        }
        return true;
    }

    public static boolean resolved(OperationResponse response) {
        return response.getStatus() == OperationStatus.OK
                || ErrorCode.REPLICATION_TIMEOUT.getCode().equals(response.getErrorCode());
    }

    public static void commitPreparedFromDurable(String dtxId, List<String> collections, long timeoutMillis)
            throws Exception {
        if (resolvedThroughLiveSession(dtxId, true, timeoutMillis)) {
            return;
        }
        TransactionRecovery.commitPreparedFromDurable(dtxId, collections, timeoutMillis);
    }

    public static void abortFromDurable(String dtxId, long timeoutMillis) throws Exception {
        if (resolvedThroughLiveSession(dtxId, false, timeoutMillis)) {
            return;
        }
        TransactionRecovery.abortFromDurable(dtxId);
    }

    public static void resolveFromDurable(String dtxId, boolean commit, long timeoutMillis) throws Exception {
        if (resolvedThroughLiveSession(dtxId, commit, timeoutMillis)) {
            return;
        }
        TransactionRecovery.resolveFromDurable(dtxId, commit, timeoutMillis);
    }
}
