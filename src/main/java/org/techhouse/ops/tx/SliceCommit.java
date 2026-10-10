package org.techhouse.ops.tx;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;

public final class SliceCommit {
    public enum Result {
        COMMITTED, REPLICATION_TIMEOUT, HALF_APPLIED
    }

    private static final Logger logger = Logger.logFor(SliceCommit.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);

    private SliceCommit() {
    }

    public static void begin(Transaction transaction) throws Exception {
        final var txId = transaction.getTransactionId().toString();
        if (!TxCommitLog.isLocallyCommitted(txId)) {
            TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(),
                    new ArrayList<>(transaction.getHeldLocks()));
        }
        if (Tx2pcLog.isPrepared(txId)) {
            Tx2pcLog.deleteParticipantMarker(txId);
        }
    }

    public static void beginFromDurable(String txId) throws Exception {
        if (TxCommitLog.isLocallyCommitted(txId)) {
            return;
        }
        final var marker = Tx2pcLog.readParticipantMarker(txId);
        TxCommitLog.recordLocalCommit(txId, Tx2pcLog.sliceOpIds(txId),
                marker == null ? List.of() : marker.collections());
        Tx2pcLog.deleteParticipantMarker(txId);
    }

    public static Result finish(Transaction transaction, List<AdminTransactionEntry> ops, String actingUser,
            boolean recordsOutcome) throws Exception {
        final var txId = transaction.getTransactionId().toString();
        coordinator.reserveTransactionTombstones(transaction);
        final var applier = new VersionedApply();
        final boolean applied;
        listenManager.deferNotifications(transaction.getHeldLocks());
        try {
            applied = TransactionRecovery.applyAllWithRetry(ops, txId, applier);
        } finally {
            listenManager.flushDeferredNotifications();
        }
        if (!applied) {
            return Result.HALF_APPLIED;
        }
        final var stagedTriggers = CommittedOpTriggers.stage(ops, actingUser, transaction.getTriggerDepth(),
                transaction, applier.outcomes(), txId);
        if (recordsOutcome) {
            Tx2pcLog.recordOutcome(txId, true, null);
        }
        TxCommitLog.clearLocalCommit(txId);
        TransactionRecovery.discardAppliedOps(txId, ops.stream().map(AdminTransactionEntry::get_id).toList());
        stagedTriggers.submitAll();
        if (coordinator.replicateTransaction(transaction) != ReplicationOutcome.TIMEOUT) {
            return Result.COMMITTED;
        }
        if (recordsOutcome) {
            recordReplicationTimeout(txId);
        }
        return Result.REPLICATION_TIMEOUT;
    }

    private static void recordReplicationTimeout(String txId) {
        try {
            Tx2pcLog.recordOutcome(txId, true, ErrorCode.REPLICATION_TIMEOUT.getCode());
        } catch (Exception e) {
            logger.warning("Transaction " + txId + " committed, but its replication timeout was not recorded; a"
                    + " re-sent COMMIT will answer OK: " + e.getMessage());
        }
    }
}
