package org.techhouse.ops.tx;

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
    private static final String OBJECTS_FIELD = "objects";
    private static final String OPS_NOT_APPLIED = "its ops did not all apply";

    private TransactionRecovery() {
    }

    public static void commitPreparedFromDurable(String dtxId, List<String> collections, long timeoutMillis)
            throws Exception {
        SliceCommit.beginFromDurable(dtxId);
        if (!replayDurableSlice(dtxId, collections, timeoutMillis)) {
            throw new DurableReplayIncompleteException(dtxId, OPS_NOT_APPLIED);
        }
    }

    private static boolean replayDurableSlice(String txId, List<String> collections, long timeoutMillis)
            throws Exception {
        if (timeoutMillis > 0) {
            locks.acquireWriteLocksNotHeld(collections, timeoutMillis);
        } else {
            locks.acquireWriteLocksNotHeld(collections);
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

    public static void discardAppliedOps(String txId, List<String> opIds) {
        try {
            AdminOperationHelper.deleteTransactionOps(opIds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning(leftoverOpsMessage(txId));
        } catch (Exception e) {
            logger.warning(leftoverOpsMessage(txId) + ": " + e.getMessage());
        }
    }

    private static String leftoverOpsMessage(String txId) {
        return "Transaction " + txId
                + " committed but some of its buffered op records were not deleted; the startup orphan sweep removes them";
    }

    public static void abortFromDurable(String dtxId) throws Exception {
        final var orphanedRuns = FencedTriggerRuns.consumedBySlice(dtxId);
        AdminOperationHelper.deleteTransactionOps(Tx2pcLog.sliceOpIds(dtxId));
        recordAborted(dtxId);
        TriggerRunRecovery.requeueRuns(orphanedRuns);
    }

    public static void resolveFromDurable(String dtxId, boolean commit, long timeoutMillis) throws Exception {
        final var state = SliceStates.stateOf(dtxId, null);
        if (state == SliceStateMachine.State.COMMITTING && !commit) {
            throw new DurableReplayIncompleteException(dtxId, "its commit was already decided");
        }
        if (state == SliceStateMachine.State.COMMITTING) {
            final var marker = TxCommitLog.readLocalCommitMarker(dtxId);
            if (!replayDurableSlice(dtxId, marker == null ? List.of() : marker.collections(), timeoutMillis)) {
                throw new DurableReplayIncompleteException(dtxId, OPS_NOT_APPLIED);
            }
        } else if (state == SliceStateMachine.State.PREPARED && commit) {
            final var marker = Tx2pcLog.readParticipantMarker(dtxId);
            commitPreparedFromDurable(dtxId, marker != null ? marker.collections() : List.of(), timeoutMillis);
        } else if (state == SliceStateMachine.State.PREPARED) {
            abortFromDurable(dtxId);
        }
    }

    public static void recordAborted(String dtxId) throws Exception {
        Tx2pcLog.deleteParticipantMarker(dtxId);
        Tx2pcLog.recordOutcome(dtxId, false, null);
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
            case AdminTransactionEntry.OP_TYPE_SAVE -> transaction.recordSave(collId,
                    op.getPayload().get(Globals.PK_FIELD).asJsonString().getValue(), op.getPayload(), op.versionAt(0));
            case AdminTransactionEntry.OP_TYPE_BULK_SAVE -> {
                final var elements = op.getPayload().get(OBJECTS_FIELD).asJsonArray().asList();
                for (var i = 0; i < elements.size(); i++) {
                    final var obj = elements.get(i).asJsonObject();
                    transaction.recordSave(collId, obj.get(Globals.PK_FIELD).asJsonString().getValue(), obj,
                            op.versionAt(i));
                }
            }
            case AdminTransactionEntry.OP_TYPE_DELETE -> transaction.recordDelete(collId,
                    op.getPayload().get(Globals.PK_FIELD).asJsonString().getValue(), op.versionAt(0));
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
        return replayDurableSlice(txId, marker == null ? List.of() : marker.collections(), 0L);
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
