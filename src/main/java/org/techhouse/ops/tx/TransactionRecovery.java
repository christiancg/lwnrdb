package org.techhouse.ops.tx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;

// Resolves transaction slices that outlived the session that created them: a 2PC participant whose
// coordinator has since decided, and slices left durable by a crash. Everything here runs without a
// live client, so it reconstructs the transaction from the durable record rather than the session.
public final class TransactionRecovery {
    private static final Logger logger = Logger.logFor(TransactionRecovery.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final String OBJECTS_FIELD = "objects";
    private static final String TRIGGER_RUN_ID_FIELD = "triggerRunId";

    private TransactionRecovery() {
    }

    // Recovery commit of a prepared slice with no in-memory transaction (after a restart): re-acquires the
    // collection write locks (sorted, deadlock-safe), replays the durable slice, replicates, then removes the
    // slice + PREPARED marker.
    public static void commitPreparedFromDurable(String dtxId, List<String> collections) throws Exception {
        replayDurableSlice(dtxId, collections, () -> resolveMarkers(dtxId, true));
    }

    // Replays a durably-recorded slice under every collection's write lock, acquired in sorted order so
    // two concurrent recoveries cannot deadlock against each other.
    private static void replayDurableSlice(String txId, List<String> collections, ThrowingRunnable markerCleanup)
            throws Exception {
        final var acquired = new ArrayList<String>();
        try {
            for (final var collId : new TreeSet<>(collections)) {
                locks.lockWrite(collId);
                acquired.add(collId);
            }
            final var opIds = Tx2pcLog.sliceOpIds(txId);
            final var ops = AdminOperationHelper.readTransactionOps(opIds);
            ops.sort(Comparator.comparingLong(AdminTransactionEntry::getSeq));
            final var reconstructed = new Transaction(UUID.fromString(txId), UUID.randomUUID());
            for (final var op : ops) {
                applyBufferedOp(op);
                recordIntoOverlay(reconstructed, op);
            }
            AdminOperationHelper.deleteTransactionOps(opIds);
            markerCleanup.run();
            coordinator.replicateTransaction(reconstructed);
        } finally {
            for (final var collId : acquired) {
                locks.releaseWrite(collId);
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // Recovery abort of a prepared slice with no in-memory transaction: discards the durable slice + marker.
    public static void abortFromDurable(String dtxId) throws Exception {
        AdminOperationHelper.deleteTransactionOps(Tx2pcLog.sliceOpIds(dtxId));
        resolveMarkers(dtxId, false);
    }

    // Resolves a prepared slice from the durable log (no in-memory transaction), used by session-less
    // COMMIT_TX/ABORT_TX and by force-resolve. A no-op when this node holds no prepared slice for the id, so
    // a broadcast force-resolve is safely ignored by non-participants and re-drives are idempotent.
    public static void resolveFromDurable(String dtxId, boolean commit) throws Exception {
        if (!Tx2pcLog.isPrepared(dtxId)) {
            return;
        }
        if (commit) {
            final var marker = Tx2pcLog.readParticipantMarker(dtxId);
            commitPreparedFromDurable(dtxId, marker != null ? marker.collections() : List.of());
        } else {
            abortFromDurable(dtxId);
        }
    }

    // Replaces a resolved transaction's PREPARED marker with a retained OUTCOME marker, so a peer can still
    // report the decision during another participant's cooperative termination.
    public static void resolveMarkers(String dtxId, boolean committed) throws Exception {
        Tx2pcLog.deleteParticipantMarker(dtxId);
        Tx2pcLog.recordOutcome(dtxId, committed);
    }

    public static void recordIntoOverlay(Transaction transaction, AdminTransactionEntry op) {
        final var collId = Cache.getCollectionIdentifier(op.getTargetDb(), op.getTargetColl());
        switch (op.getOpType()) {
            case AdminTransactionEntry.OP_TYPE_SAVE -> transaction.recordSave(collId,
                    op.getPayload().get(Globals.PK_FIELD).asJsonString().getValue(), op.getPayload());
            case AdminTransactionEntry.OP_TYPE_BULK_SAVE -> {
                for (final var element : op.getPayload().get(OBJECTS_FIELD).asJsonArray().asList()) {
                    final var obj = element.asJsonObject();
                    transaction.recordSave(collId, obj.get(Globals.PK_FIELD).asJsonString().getValue(), obj);
                }
            }
            case AdminTransactionEntry.OP_TYPE_DELETE ->
                transaction.recordDelete(collId, op.getPayload().get(Globals.PK_FIELD).asJsonString().getValue());
            default -> {
                // markers never appear in the slice op id list
            }
        }
    }

    // Removes operation records left in admin/transactions by transactions that were open when the server
    // stopped (their owning connections are gone). Records belonging to an in-doubt 2PC transaction (one
    // with a PREPARED or COMMITTED marker) are preserved for recovery to resolve.
    public static void cleanupOrphansAtStartup() throws Exception {
        finishLocalCommitsAtStartup();
        final var inDoubt = new HashSet<String>();
        inDoubt.addAll(Tx2pcLog.preparedDtxIds());
        inDoubt.addAll(Tx2pcLog.committedDtxIds());
        // Retained outcome markers (for cooperative termination) must also survive restart cleanup.
        inDoubt.addAll(Tx2pcLog.outcomeDtxIds());
        // A local commit whose replay just failed keeps its marker and slice, so the next restart can retry it
        // instead of the sweep discarding a commit that was already decided.
        inDoubt.addAll(TxCommitLog.localCommitTxIds());
        final var orphans = cache.getTransactionPkIndexes().keySet().stream()
                .filter(id -> !inDoubt.contains(dtxIdOf(id))).toList();
        if (orphans.isEmpty()) {
            return;
        }
        AdminOperationHelper.deleteTransactionOps(orphans);
        logger.info("Removed " + orphans.size() + " orphaned transaction operation(s) at startup");
    }

    // Finishes every single-node commit that had reached its commit point before the process died. Runs before
    // the orphan sweep so a decided commit is completed rather than discarded with the undecided ones.
    private static void finishLocalCommitsAtStartup() {
        for (final var txId : TxCommitLog.localCommitTxIds()) {
            try {
                final var marker = TxCommitLog.readLocalCommitMarker(txId);
                commitLocalFromDurable(txId, marker == null ? List.of() : marker.collections());
                logger.info("Finished transaction " + txId + " that was interrupted mid-commit at startup");
            } catch (Exception e) {
                logger.error("Failed to finish interrupted transaction " + txId + " at startup", e);
            }
        }
    }

    // Replays a decided single-node commit from the durable log. Idempotent: buffered ops carry whole values
    // (a SAVE's full document, a DELETE's id), so re-applying the prefix a crash already applied converges to
    // the same state rather than compounding.
    public static void commitLocalFromDurable(String txId, List<String> collections) throws Exception {
        replayDurableSlice(txId, collections, () -> TxCommitLog.clearLocalCommit(txId));
    }

    private static String dtxIdOf(String recordId) {
        final var sep = recordId.lastIndexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
        return sep > 0 ? recordId.substring(0, sep) : recordId;
    }

    public static void applyBufferedOp(AdminTransactionEntry op) throws Exception {
        final var dbName = op.getTargetDb();
        final var collName = op.getTargetColl();
        switch (op.getOpType()) {
            case AdminTransactionEntry.OP_TYPE_SAVE -> {
                final var saveRequest = new SaveRequest(dbName, collName);
                final var object = op.getPayload();
                saveRequest.setObject(object);
                saveRequest.set_id(object.get(Globals.PK_FIELD).asJsonString().getValue());
                SaveOperationHelper.executeSave(saveRequest);
            }
            case AdminTransactionEntry.OP_TYPE_BULK_SAVE -> {
                final var bulkSaveRequest = new BulkSaveRequest(dbName, collName);
                final var objects = new ArrayList<JsonObject>();
                for (final var element : op.getPayload().get(OBJECTS_FIELD).asJsonArray().asList()) {
                    objects.add(element.asJsonObject());
                }
                bulkSaveRequest.setObjects(objects);
                SaveOperationHelper.executeBulkSave(bulkSaveRequest);
            }
            case AdminTransactionEntry.OP_TYPE_DELETE -> {
                final var deleteRequest = new DeleteRequest(dbName, collName);
                deleteRequest.set_id(op.getPayload().get(Globals.PK_FIELD).asJsonString().getValue());
                DeleteOperationHelper.executeDelete(deleteRequest);
            }
            // Consuming the pending trigger run in the same commit as the run's effects is what makes a
            // trigger exactly-once: the record that would replay it disappears if and only if it landed.
            case AdminTransactionEntry.OP_TYPE_DELETE_TRIGGER_RUN -> {
                final var runId = op.getPayload().get(TRIGGER_RUN_ID_FIELD).asJsonString().getValue();
                AdminOperationHelper.deleteTriggerRuns(TriggerRunLog.recordIdsFor(runId));
            }
            default -> throw new IllegalStateException("Unknown transaction op type: " + op.getOpType());
        }
    }
}
