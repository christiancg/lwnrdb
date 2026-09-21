package org.techhouse.ops.tx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.TransactionOpFailedException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;

public final class TransactionRecovery {
    private static final int COMMIT_APPLY_ATTEMPTS = 3;
    private static final org.techhouse.listen.ListenManager listenManager = org.techhouse.ioc.IocContainer
            .get(org.techhouse.listen.ListenManager.class);
    private static final Logger logger = Logger.logFor(TransactionRecovery.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final String OBJECTS_FIELD = "objects";
    private static final String TRIGGER_RUN_ID_FIELD = "triggerRunId";

    private TransactionRecovery() {
    }

    public static void commitPreparedFromDurable(String dtxId, List<String> collections) throws Exception {
        commitPreparedFromDurable(dtxId, collections, 0L);
    }

    public static void commitPreparedFromDurable(String dtxId, List<String> collections, long timeoutMillis)
            throws Exception {
        final var marker = Tx2pcLog.readParticipantMarker(dtxId);
        replayDurableSlice(dtxId, collections, marker != null ? marker.preparedVersion() : 0L, timeoutMillis,
                () -> resolveMarkers(dtxId, true));
    }

    private static void replayDurableSlice(String txId, List<String> collections, long preparedVersion,
            ThrowingRunnable markerCleanup) throws Exception {
        replayDurableSlice(txId, collections, preparedVersion, 0L, markerCleanup);
    }

    private static void replayDurableSlice(String txId, List<String> collections, long preparedVersion,
            long timeoutMillis, ThrowingRunnable markerCleanup) throws Exception {
        final ResourceLocking.LockedAction<Void> replay = () -> {
            final var opIds = Tx2pcLog.sliceOpIds(txId);
            final var ops = AdminOperationHelper.readTransactionOps(opIds);
            ops.sort(Comparator.comparingLong(AdminTransactionEntry::getSeq));
            final var reconstructed = new Transaction(UUID.fromString(txId), UUID.randomUUID());
            reconstructed.setTriggerDepth(triggerDepthOf(ops));
            for (final var op : ops) {
                recordIntoOverlay(reconstructed, op);
            }
            final var fencedIds = idsWrittenSincePrepare(reconstructed, preparedVersion);
            dropTombstonesWrittenSincePrepare(reconstructed, fencedIds);
            final var reservedTombstones = coordinator.reserveTransactionTombstones(reconstructed);
            listenManager.deferNotifications();
            try {
                for (final var op : ops) {
                    applyBufferedOp(op, fencedIds);
                }
            } finally {
                listenManager.flushDeferredNotifications();
            }
            AdminOperationHelper.deleteTransactionOps(opIds);
            markerCleanup.run();
            // Fired after the durable commit is cleared, exactly as the online commit does. At startup the
            // executor is not running yet, so the submit is a no-op and the durable run record that
            // TriggerRunLog writes first is what TriggerRunRecovery replays once it is.
            org.techhouse.ops.TransactionOperationHelper.fireTriggersForCommittedOps(ops, actingUserOf(ops),
                    reconstructed.getTriggerDepth(), reconstructed);
            coordinator.replicateTransaction(reconstructed, reservedTombstones);
            return null;
        };
        if (timeoutMillis > 0) {
            locks.withWriteLocks(collections, timeoutMillis, replay);
        } else {
            locks.withWriteLocks(collections, replay);
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

    private static Set<String> idsWrittenSincePrepare(Transaction transaction, long preparedVersion)
            throws java.io.IOException {
        final var fenced = new HashSet<String>();
        if (preparedVersion <= 0) {
            return fenced;
        }
        for (final var collId : transaction.touchedCollections()) {
            final var overlay = transaction.overlayFor(collId);
            if (overlay == null) {
                continue;
            }
            final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            for (final var id : overlay.keySet()) {
                if (writtenSincePrepare(parts[0], parts[1], id, preparedVersion)) {
                    fenced.add(fenceKey(parts[0], parts[1], id));
                }
            }
        }
        return fenced;
    }

    private static String fenceKey(String dbName, String collName, String id) {
        return Cache.getCollectionIdentifier(dbName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR + id;
    }

    private static void dropTombstonesWrittenSincePrepare(Transaction transaction, Set<String> fencedIds) {
        if (fencedIds.isEmpty()) {
            return;
        }
        for (final var collId : transaction.touchedCollections()) {
            final var overlay = transaction.overlayFor(collId);
            if (overlay == null) {
                continue;
            }
            final var stale = new ArrayList<String>();
            for (final var overlayEntry : overlay.entrySet()) {
                if (Transaction.isTombstone(overlayEntry.getValue())
                        && fencedIds.contains(collId + Globals.COLL_IDENTIFIER_SEPARATOR + overlayEntry.getKey())) {
                    stale.add(overlayEntry.getKey());
                }
            }
            for (final var id : stale) {
                overlay.remove(id);
            }
        }
    }

    private static boolean writtenSincePrepare(String dbName, String collName, String id, long preparedVersion)
            throws java.io.IOException {
        if (preparedVersion <= 0) {
            return false;
        }
        final var pkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var found = java.util.Collections.binarySearch(pkIndex, id);
        return found >= 0 && pkIndex.get(found).getVersion() > preparedVersion;
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
                replayDurableSlice(txId, marker == null ? List.of() : marker.collections(),
                        marker == null ? 0L : marker.writeVersion(), () -> TxCommitLog.clearLocalCommit(txId));
                logger.info("Finished transaction " + txId + " that was interrupted mid-commit at startup");
            } catch (Exception e) {
                logger.error("Failed to finish interrupted transaction " + txId + " at startup", e);
            }
        }
    }

    // Idempotent: buffered ops carry whole values, so re-applying the prefix a crash already applied
    // converges to the same state rather than compounding.
    public static void commitLocalFromDurable(String txId, List<String> collections) throws Exception {
        replayDurableSlice(txId, collections, 0L, () -> TxCommitLog.clearLocalCommit(txId));
    }

    private static String dtxIdOf(String recordId) {
        final var sep = recordId.lastIndexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
        return sep > 0 ? recordId.substring(0, sep) : recordId;
    }

    private static void requireApplied(String opType, OperationResponse response) {
        if (response == null || response.getStatus() == OperationStatus.OK) {
            return;
        }
        throw new TransactionOpFailedException(opType, response.getErrorCode(), response.getMessage());
    }

    public static boolean applyAllWithRetry(List<AdminTransactionEntry> ops, String txId) {
        var next = 0;
        for (var attempt = 1; attempt <= COMMIT_APPLY_ATTEMPTS; attempt++) {
            try {
                while (next < ops.size()) {
                    TransactionRecovery.applyBufferedOp(ops.get(next));
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

    public static void applyBufferedOp(AdminTransactionEntry op) throws Exception {
        applyBufferedOp(op, Set.of());
    }

    public static void applyBufferedOp(AdminTransactionEntry op, Set<String> fencedIds) throws Exception {
        final var dbName = op.getTargetDb();
        final var collName = op.getTargetColl();
        switch (op.getOpType()) {
            case AdminTransactionEntry.OP_TYPE_SAVE -> {
                final var object = op.getPayload();
                final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
                if (fencedIds.contains(fenceKey(dbName, collName, id))) {
                    logger.warning("Skipping the replay of " + id + " in " + dbName + "|" + collName
                            + ": it was written after the transaction was prepared");
                    return;
                }
                final var saveRequest = new SaveRequest(dbName, collName);
                saveRequest.setObject(object);
                saveRequest.set_id(id);
                requireApplied(AdminTransactionEntry.OP_TYPE_SAVE, SaveOperationHelper.executeSave(saveRequest));
            }
            case AdminTransactionEntry.OP_TYPE_BULK_SAVE -> {
                final var objects = new ArrayList<JsonObject>();
                for (final var element : op.getPayload().get(OBJECTS_FIELD).asJsonArray().asList()) {
                    final var object = element.asJsonObject();
                    final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
                    if (fencedIds.contains(fenceKey(dbName, collName, id))) {
                        logger.warning("Skipping the replay of " + id + " in " + dbName + "|" + collName
                                + ": it was written after the transaction was prepared");
                        continue;
                    }
                    objects.add(object);
                }
                if (objects.isEmpty()) {
                    return;
                }
                final var bulkSaveRequest = new BulkSaveRequest(dbName, collName);
                bulkSaveRequest.setObjects(objects);
                requireApplied(AdminTransactionEntry.OP_TYPE_BULK_SAVE,
                        SaveOperationHelper.executeBulkSave(bulkSaveRequest));
            }
            case AdminTransactionEntry.OP_TYPE_DELETE -> {
                final var id = op.getPayload().get(Globals.PK_FIELD).asJsonString().getValue();
                if (fencedIds.contains(fenceKey(dbName, collName, id))) {
                    logger.warning("Skipping the replayed delete of " + id + " in " + dbName + "|" + collName
                            + ": it was written after the transaction was prepared");
                    return;
                }
                final var deleteRequest = new DeleteRequest(dbName, collName);
                deleteRequest.set_id(id);
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
