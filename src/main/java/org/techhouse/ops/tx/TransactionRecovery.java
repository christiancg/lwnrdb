package org.techhouse.ops.tx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ex.DurableReplayIncompleteException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;

public final class TransactionRecovery {
    private static final int COMMIT_APPLY_ATTEMPTS = 3;
    private static final Logger logger = Logger.logFor(TransactionRecovery.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final String OPS_NOT_APPLIED = "its ops did not all apply";
    private static final String OPS_MISSING = "some of its op records are missing";

    private TransactionRecovery() {
    }

    private static void commitPreparedFromDurable(String dtxId, Tx2pcLog.ParticipantMarker marker, long timeoutMillis)
            throws Exception {
        final var expected = marker == null ? null : marker.opIds();
        if (incomplete(dtxId, expected)) {
            throw new DurableReplayIncompleteException(dtxId, OPS_MISSING);
        }
        SliceCommit.beginFromDurable(dtxId, marker);
        if (!replayDurableSlice(dtxId, marker == null ? List.of() : marker.collections(), expected, timeoutMillis)) {
            throw new DurableReplayIncompleteException(dtxId, OPS_NOT_APPLIED);
        }
    }

    private static boolean incomplete(String txId, List<String> expectedOpIds) {
        if (expectedOpIds == null) {
            return false;
        }
        final var present = new HashSet<>(Tx2pcLog.sliceOpIds(txId));
        final var missing = expectedOpIds.stream().filter(id -> !present.contains(id)).toList();
        if (missing.isEmpty()) {
            return false;
        }
        logger.error("Transaction " + txId + " is missing " + missing.size() + " of its " + expectedOpIds.size()
                + " durable op record(s) (" + missing.getFirst() + (missing.size() > 1 ? ", ..." : "")
                + "); none of its remaining ops is applied and its records are kept for an operator to inspect");
        return true;
    }

    private static boolean replayDurableSlice(String txId, List<String> collections, List<String> expectedOpIds,
            long timeoutMillis) throws Exception {
        locks.acquireWriteLocksNotHeld(collections, timeoutMillis);
        if (incomplete(txId, expectedOpIds)) {
            locks.fence(collections);
            logger.error("Transaction " + txId + " may be partly applied; its collections stay locked until an"
                    + " operator resolves its records");
            return false;
        }
        var finished = false;
        try {
            final var ops = AdminOperationHelper.readTransactionOps(Tx2pcLog.sliceOpIds(txId));
            ops.sort(Comparator.comparingLong(AdminTransactionEntry::getSeq));
            final var reconstructed = new Transaction(UUID.fromString(txId), UUID.randomUUID());
            reconstructed.setTriggerDepth(triggerDepthOf(ops));
            collections.forEach(reconstructed::addHeldLock);
            for (final var op : ops) {
                recordIntoOverlay(reconstructed, op);
            }
            finished = SliceCommit.finish(reconstructed, ops, actingUserOf(ops),
                    clusterConfig.isEnabled()) != SliceCommit.Result.HALF_APPLIED;
            if (!finished) {
                logger.error("Transaction " + txId
                        + " could not finish its durable replay; its collections stay locked pending a retry");
            }
            return finished;
        } catch (Exception e) {
            logger.error("Transaction " + txId
                    + " could not finish its durable replay; its collections stay locked pending a retry", e);
            throw e;
        } finally {
            if (finished) {
                locks.releaseWriteLocksHeldByCurrentThread(collections);
            }
        }
    }

    private static String actingUserOf(List<AdminTransactionEntry> ops) {
        for (final var op : ops) {
            if (!op.getActingUser().isEmpty()) {
                return op.getActingUser();
            }
        }
        return null;
    }

    private static void abortFromDurable(String dtxId) throws Exception {
        final var orphanedRuns = TriggerRunRecovery.consumedBySlice(dtxId);
        recordAborted(dtxId, Tx2pcLog.sliceOpIds(dtxId));
        TriggerRunRecovery.requeueRuns(orphanedRuns);
    }

    public static void resolveFromDurable(String dtxId, boolean commit, long timeoutMillis) throws Exception {
        final var state = SliceStateMachine.stateOf(dtxId, null);
        if (state == SliceStateMachine.State.COMMITTING && !commit) {
            throw new DurableReplayIncompleteException(dtxId, "its commit was already decided");
        }
        if (state == SliceStateMachine.State.COMMITTING) {
            final var marker = TxCommitLog.readLocalCommitMarker(dtxId);
            if (!replayDurableSlice(dtxId, marker == null ? List.of() : marker.collections(),
                    marker == null ? null : marker.opIds(), timeoutMillis)) {
                throw new DurableReplayIncompleteException(dtxId, OPS_NOT_APPLIED);
            }
        } else if (state == SliceStateMachine.State.PREPARED && commit) {
            commitPreparedFromDurable(dtxId, Tx2pcLog.readParticipantMarker(dtxId), timeoutMillis);
        } else if (state == SliceStateMachine.State.PREPARED) {
            abortFromDurable(dtxId);
        }
    }

    public static void recordAborted(String dtxId, List<String> opIds) throws Exception {
        final var cleared = new ArrayList<String>(opIds.size() + 1);
        cleared.add(Tx2pcLog.participantMarkerId(dtxId));
        cleared.addAll(opIds);
        AdminOperationHelper.writeTransactionRecords(List.of(Tx2pcLog.outcomeMarker(dtxId, false, null)), cleared);
    }

    private static int triggerDepthOf(List<AdminTransactionEntry> ops) {
        var depth = 0;
        for (final var op : ops) {
            depth = Math.max(depth, op.getTriggerDepth());
        }
        return depth;
    }

    public static void recordIntoOverlay(Transaction transaction, AdminTransactionEntry op) {
        final var collId = Cache.getCollectionIdentifier(op.getTargetDb(), op.getTargetColl());
        if (!op.getInsertedIds().isEmpty()) {
            transaction.recordInserts(op.getSeq(), op.getInsertedIds());
        }
        switch (op.getOpType()) {
            case AdminTransactionEntry.OP_TYPE_SAVE, AdminTransactionEntry.OP_TYPE_BULK_SAVE -> {
                final var objects = op.savedObjects();
                for (var i = 0; i < objects.size(); i++) {
                    final var obj = objects.get(i);
                    transaction.recordSave(collId, obj.get(Globals.PK_FIELD).asJsonString().getValue(), obj,
                            op.versionAt(i));
                }
            }
            case AdminTransactionEntry.OP_TYPE_DELETE ->
                transaction.recordDelete(collId, op.targetId(), op.versionAt(0));
            default -> {
                // markers never appear in the slice op id list
            }
        }
    }

    // Records of an in-doubt 2PC transaction (PREPARED or COMMITTED marker) are preserved for recovery.
    public static void cleanupOrphansAtStartup() throws Exception {
        finishLocalCommitsAtStartup();
        final var inDoubt = new HashSet<String>();
        inDoubt.addAll(Tx2pcLog.preparedDtxIds());
        inDoubt.addAll(Tx2pcLog.committedDtxIds());
        // Retained outcome markers (for cooperative termination) must also survive restart cleanup.
        inDoubt.addAll(Tx2pcLog.outcomeDtxIds());
        // A failed local-commit replay keeps its marker and slice so the next restart can retry a commit
        // that was already decided.
        inDoubt.addAll(TxCommitLog.localCommitTxIds());
        final var orphans = cache.getTransactionPkIndexes().keySet().stream()
                .filter(id -> !inDoubt.contains(dtxIdOf(id))).toList();
        if (orphans.isEmpty()) {
            return;
        }
        AdminOperationHelper.deleteTransactionOps(orphans);
        logger.info("Removed " + orphans.size() + " orphaned transaction operation(s) at startup");
    }

    // Runs before the orphan sweep so a decided commit is completed rather than discarded with the
    // undecided ones.
    private static void finishLocalCommitsAtStartup() {
        for (final var txId : TxCommitLog.localCommitTxIds()) {
            try {
                if (finishLocalCommit(txId)) {
                    logger.info("Finished transaction " + txId + " that was interrupted mid-commit at startup");
                } else {
                    logger.warning("Transaction " + txId
                            + " is still stuck mid-commit after startup recovery; its collections stay locked");
                }
            } catch (Throwable failure) {
                logger.error("Failed to finish interrupted transaction " + txId + " at startup", failure);
            }
        }
    }

    public static boolean finishLocalCommit(String txId) throws Exception {
        final var marker = TxCommitLog.readLocalCommitMarker(txId);
        return replayDurableSlice(txId, marker == null ? List.of() : marker.collections(),
                marker == null ? null : marker.opIds(), 0L);
    }

    private static String dtxIdOf(String recordId) {
        final var sep = recordId.lastIndexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
        return sep > 0 ? recordId.substring(0, sep) : recordId;
    }

    public static boolean applyAllWithRetry(List<AdminTransactionEntry> ops, String txId, VersionedApply applier) {
        var next = 0;
        for (var attempt = 1; attempt <= COMMIT_APPLY_ATTEMPTS; attempt++) {
            try {
                while (next < ops.size()) {
                    applier.apply(ops.get(next));
                    next++;
                }
                return true;
            } catch (Exception e) {
                logger.warning("Transaction " + txId + " failed to apply op " + next + " on attempt " + attempt + ": "
                        + e.getMessage());
            }
        }
        return false;
    }
}
