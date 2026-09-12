package org.techhouse.ops.tx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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

public final class TransactionRecovery {
    private static final Logger logger = Logger.logFor(TransactionRecovery.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final String OBJECTS_FIELD = "objects";
    private static final String TRIGGER_RUN_ID_FIELD = "triggerRunId";

    private TransactionRecovery() {
    }

    public static void commitPreparedFromDurable(String dtxId, List<String> collections) throws Exception {
        replayDurableSlice(dtxId, collections, () -> resolveMarkers(dtxId, true));
    }

    private static void replayDurableSlice(String txId, List<String> collections, ThrowingRunnable markerCleanup)
            throws Exception {
        locks.withWriteLocks(collections, () -> {
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
            return null;
        });
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void abortFromDurable(String dtxId) throws Exception {
        AdminOperationHelper.deleteTransactionOps(Tx2pcLog.sliceOpIds(dtxId));
        resolveMarkers(dtxId, false);
    }

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

    // The OUTCOME marker is retained so a peer can still report the decision during another
    // participant's cooperative termination.
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
                final var marker = TxCommitLog.readLocalCommitMarker(txId);
                commitLocalFromDurable(txId, marker == null ? List.of() : marker.collections());
                logger.info("Finished transaction " + txId + " that was interrupted mid-commit at startup");
            } catch (Exception e) {
                logger.error("Failed to finish interrupted transaction " + txId + " at startup", e);
            }
        }
    }

    // Idempotent: buffered ops carry whole values, so re-applying the prefix a crash already applied
    // converges to the same state rather than compounding.
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
