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
import org.techhouse.ops.AdminOperationHelper;
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
        begin(transaction, List.of());
    }

    public static void begin(Transaction transaction, List<AdminTransactionEntry> alsoRecord) throws Exception {
        final var txId = transaction.getTransactionId().toString();
        final var inserts = new ArrayList<AdminTransactionEntry>();
        if (!transaction.areOpsDurable()) {
            inserts.addAll(transaction.getPendingOps());
        }
        inserts.addAll(alsoRecord);
        if (!TxCommitLog.isLocallyCommitted(txId)) {
            inserts.add(TxCommitLog.localCommitMarker(txId, transaction.getBufferedOpIds(),
                    new ArrayList<>(transaction.getHeldLocks())));
        }
        AdminOperationHelper.writeTransactionRecords(inserts, List.of(Tx2pcLog.participantMarkerId(txId)));
        transaction.markOpsDurable();
    }

    public static void beginFromDurable(String txId) throws Exception {
        if (TxCommitLog.isLocallyCommitted(txId)) {
            return;
        }
        final var marker = Tx2pcLog.readParticipantMarker(txId);
        AdminOperationHelper.writeTransactionRecords(
                List.of(TxCommitLog.localCommitMarker(txId, Tx2pcLog.sliceOpIds(txId),
                        marker == null ? List.of() : marker.collections())),
                List.of(Tx2pcLog.participantMarkerId(txId)));
    }

    public static Result finish(Transaction transaction, List<AdminTransactionEntry> ops, String actingUser,
            boolean recordsOutcome) throws Exception {
        final var txId = transaction.getTransactionId().toString();
        coordinator.reserveTransactionTombstones(transaction);
        final VersionedApply applier;
        final boolean applied;
        try (var batch = listenManager.openBatch(transaction.getHeldLocks())) {
            applier = new VersionedApply(batch);
            applied = TransactionRecovery.applyAllWithRetry(ops, txId, applier);
        }
        if (!applied) {
            return Result.HALF_APPLIED;
        }
        final var stagedTriggers = CommittedOpTriggers.stage(ops, actingUser, transaction.getTriggerDepth(),
                transaction, applier.outcomes(), txId);
        AdminOperationHelper.writeTransactionRecords(
                recordsOutcome ? List.of(Tx2pcLog.outcomeMarker(txId, true, null)) : List.of(), clearedBy(txId, ops));
        stagedTriggers.submitAll();
        if (coordinator.replicateTransaction(transaction) != ReplicationOutcome.TIMEOUT) {
            return Result.COMMITTED;
        }
        if (recordsOutcome) {
            recordReplicationTimeout(txId);
        }
        return Result.REPLICATION_TIMEOUT;
    }

    private static List<String> clearedBy(String txId, List<AdminTransactionEntry> ops) {
        final var cleared = new ArrayList<String>(ops.size() + 2);
        cleared.add(Tx2pcLog.participantMarkerId(txId));
        cleared.add(TxCommitLog.markerId(txId));
        for (final var op : ops) {
            cleared.add(op.get_id());
        }
        return cleared;
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
