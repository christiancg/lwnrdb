package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.CommitTransactionResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.RollbackTransactionResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.ops.resp.StartTransactionResponse;

/**
 * Client-scoped transactions. A transaction buffers its data mutations (SAVE / BULK_SAVE / DELETE)
 * instead of applying them: each is persisted as an operation record in {@code admin/transactions}
 * (the durable source of truth replayed at commit) and mirrored into the {@link Transaction}
 * overlay that serves the transaction's own read-your-writes reads. The first write to each
 * collection lazily acquires that collection's exclusive write lock (with a bounded timeout so
 * concurrent transactions cannot deadlock) and holds it — on the connection's own virtual thread —
 * until commit or rollback. Commit replays the buffered operations against the real collections
 * through the shared core write helpers; rollback discards them. Both then delete the buffered
 * operation records and release the held locks.
 */
public final class TransactionOperationHelper {
    private static final String TRIGGER_RUN_ID_FIELD = "triggerRunId";
    private TransactionOperationHelper() {
    }

    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final ClusterRouter clusterRouter = IocContainer.get(ClusterRouter.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(TransactionOperationHelper.class);

    private static final String OBJECTS_FIELD = "objects";
    private static final String DELETED_DOCUMENT_FIELD = "deletedDocument";

    // Operations a client may issue while a transaction is open. Everything else (DDL, admin, LISTEN,
    // ...) is rejected so transactional atomicity reasoning stays simple. START_TRANSACTION is allowed
    // through so start() can report the "already active" conflict rather than a generic rejection.
    public static boolean isAllowedDuringTransaction(OperationType type) {
        return switch (type) {
            case START_TRANSACTION, COMMIT_TRANSACTION, ROLLBACK_TRANSACTION, SAVE, BULK_SAVE, DELETE, FIND_BY_ID,
                    AGGREGATE, CLOSE_CONNECTION ->
                true;
            default -> false;
        };
    }

    public static OperationResponse start(UUID clientId) {
        return start(clientId, UUID.randomUUID(), 0);
    }

    public static OperationResponse start(UUID clientId, UUID transactionId) {
        return start(clientId, transactionId, 0);
    }

    // Starts a transaction with a caller-supplied id. A forwarded 2PC participant uses the coordinator's
    // distributed-tx id so its buffered slice and recovery markers key on the same id everywhere.
    public static OperationResponse start(UUID clientId, UUID transactionId, int triggerDepth) {
        if (clientTracker.getActiveTransaction(clientId) != null) {
            return new OperationResponse(OperationType.START_TRANSACTION, ErrorCode.TRANSACTION_ALREADY_ACTIVE);
        }
        final var transaction = new Transaction(transactionId, clientId);
        transaction.setTriggerDepth(triggerDepth);
        clientTracker.setActiveTransaction(clientId, transaction);
        return new StartTransactionResponse("Transaction started", transactionId.toString());
    }

    // Phase 5b participant vote: durably records this node's PREPARED marker (with the collections whose
    // write locks it holds, for recovery) and votes yes, unless the write quorum has been lost. The locks
    // stay held until commit/abort. Returns true for a yes vote.
    public static boolean prepare(UUID clientId, String coordinatorAddress, List<String> participants) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null || coordinator.hasNotTransactionQuorum()) {
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

    // Phase 5b participant commit of a prepared slice held in memory: replays the buffered ops, replicates
    // the batch, then removes the slice + PREPARED marker and releases the locks.
    public static OperationResponse commitPrepared(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.NO_ACTIVE_TRANSACTION);
        }
        try {
            final var ops = AdminOperationHelper.readTransactionOps(transaction.getBufferedOpIds());
            for (final var op : ops) {
                applyBufferedOp(op);
            }
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
            // After the durable commit, so a trigger never observes a transaction that later rolled back.
            // A rollback fires nothing.
            fireTriggersForCommittedOps(ops, clientTracker.getAuthenticatedUsername(clientId),
                    transaction.getTriggerDepth(), transaction);
            resolveMarkers(transaction.getTransactionId().toString(), true);
            // A replication timeout does not fail the commit — the decision is made and the local commit is
            // durable; anti-entropy reconciles the lagging replicas.
            coordinator.replicateTransaction(transaction);
            return new CommitTransactionResponse("Transaction committed");
        } catch (Exception e) {
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.ERROR_TRANSACTION);
        } finally {
            releaseHeldLocks(transaction);
            clientTracker.clearActiveTransaction(clientId);
            clientTracker.clearTransactionState(clientId);
        }
    }

    // Phase 5b participant abort of an in-memory slice: discards the buffered ops + PREPARED marker and
    // releases the locks.
    public static OperationResponse abort(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return new OperationResponse(OperationType.ROLLBACK_TRANSACTION, ErrorCode.NO_ACTIVE_TRANSACTION);
        }
        try {
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
            resolveMarkers(transaction.getTransactionId().toString(), false);
            return new RollbackTransactionResponse("Transaction aborted");
        } catch (Exception e) {
            return new OperationResponse(OperationType.ROLLBACK_TRANSACTION, ErrorCode.ERROR_TRANSACTION);
        } finally {
            releaseHeldLocks(transaction);
            clientTracker.clearActiveTransaction(clientId);
            clientTracker.clearTransactionState(clientId);
        }
    }

    // Recovery commit of a prepared slice with no in-memory transaction (after a restart): re-acquires the
    // collection write locks (sorted, deadlock-safe), replays the durable slice, replicates, then removes the
    // slice + PREPARED marker.
    public static void commitPreparedFromDurable(String dtxId, List<String> collections) throws Exception {
        final var acquired = new ArrayList<String>();
        try {
            for (final var collId : new java.util.TreeSet<>(collections)) {
                locks.lockWrite(collId);
                acquired.add(collId);
            }
            final var ops = AdminOperationHelper.readTransactionOps(Tx2pcLog.sliceOpIds(dtxId));
            ops.sort(java.util.Comparator.comparingLong(AdminTransactionEntry::getSeq));
            final var reconstructed = new Transaction(UUID.fromString(dtxId), UUID.randomUUID());
            for (final var op : ops) {
                applyBufferedOp(op);
                recordIntoOverlay(reconstructed, op);
            }
            AdminOperationHelper.deleteTransactionOps(Tx2pcLog.sliceOpIds(dtxId));
            resolveMarkers(dtxId, true);
            coordinator.replicateTransaction(reconstructed);
        } finally {
            for (final var collId : acquired) {
                locks.releaseWrite(collId);
            }
        }
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
    private static void resolveMarkers(String dtxId, boolean committed) throws Exception {
        Tx2pcLog.deleteParticipantMarker(dtxId);
        Tx2pcLog.recordOutcome(dtxId, committed);
    }

    private static void recordIntoOverlay(Transaction transaction, AdminTransactionEntry op) {
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

    public static OperationResponse commit(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.NO_ACTIVE_TRANSACTION);
        }
        try {
            // A clustered commit must still hold a write quorum (split-brain protection); abort before
            // applying if it was lost between the first write and commit.
            if (coordinator.hasNotTransactionQuorum()) {
                AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
                return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.NO_QUORUM);
            }
            final var ops = AdminOperationHelper.readTransactionOps(transaction.getBufferedOpIds());
            final var txId = transaction.getTransactionId().toString();
            // The commit point: durable before the first op is applied, so a crash after this is finished by
            // cleanupOrphansAtStartup instead of leaving the transaction half-applied.
            TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(),
                    new ArrayList<>(transaction.getHeldLocks()));
            for (final var op : ops) {
                applyBufferedOp(op);
            }
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
            TxCommitLog.clearLocalCommit(txId);
            // After the durable commit, so a trigger never observes a transaction that later rolled back. The
            // transaction's own depth is used, not zero: a trigger's writes commit through here, and starting
            // the chain over would let allowCascade=true cascade forever.
            fireTriggersForCommittedOps(ops, clientTracker.getAuthenticatedUsername(clientId),
                    transaction.getTriggerDepth(), transaction);
            // Replicate the whole transaction to the quorum as one atomic batch. The local commit stands even
            // on a replication timeout; Phase 4 anti-entropy reconciles the lagging replicas.
            if (coordinator.replicateTransaction(transaction) == ReplicationOutcome.TIMEOUT) {
                return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.REPLICATION_TIMEOUT);
            }
            return new CommitTransactionResponse("Transaction committed");
        } catch (Exception e) {
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.ERROR_TRANSACTION);
        } finally {
            releaseHeldLocks(transaction);
            clientTracker.clearActiveTransaction(clientId);
            clientTracker.clearTransactionState(clientId);
        }
    }

    public static OperationResponse rollback(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return new OperationResponse(OperationType.ROLLBACK_TRANSACTION, ErrorCode.NO_ACTIVE_TRANSACTION);
        }
        try {
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
            return new RollbackTransactionResponse("Transaction rolled back");
        } catch (Exception e) {
            return new OperationResponse(OperationType.ROLLBACK_TRANSACTION, ErrorCode.ERROR_TRANSACTION);
        } finally {
            releaseHeldLocks(transaction);
            clientTracker.clearActiveTransaction(clientId);
            clientTracker.clearTransactionState(clientId);
        }
    }

    // Best-effort teardown when a connection closes with a transaction still open. Runs on the
    // connection's own thread (the only thread allowed to release its write locks). When the transaction was
    // forwarded to a remote owner, tells that owner to roll it back too (releasing the owner's held locks).
    public static void cleanupOnDisconnect(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return;
        }
        // A cross-owner transaction is torn down through the coordinator (aborts remote participants + the
        // local slice, and clears state); only a purely-local transaction falls through to the local cleanup.
        if (clusterRouter.teardownTransaction(clientId)) {
            return;
        }
        try {
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
        } catch (Exception e) {
            logger.warning("Failed to clean up transaction on disconnect: " + e.getMessage());
        } finally {
            releaseHeldLocks(transaction);
            clientTracker.clearActiveTransaction(clientId);
            clientTracker.clearTransactionState(clientId);
        }
    }

    // Owner-side safety net: rolls back and releases forwarded transactions whose originating edge node has
    // left the cluster (absent or DEAD), so an edge-node crash cannot strand the owner's write locks. The
    // rollback runs on each session's own executor thread (the holder of its locks). A session that has
    // already voted yes (has a PREPARED marker) is in-doubt and is left for 2PC recovery to resolve against
    // the coordinator's decision — aborting it here could break atomicity if the coordinator committed.
    /**
     * Rolls back every transaction still open when the node stops, so their collection write locks are
     * released and their buffered slices are discarded rather than left for the startup orphan sweep. A
     * PREPARED 2PC slice is deliberately left alone: its coordinator may already have decided to commit, and
     * only recovery may resolve it.
     */
    public static void rollbackOpenTransactionsAtShutdown() {
        var rolledBack = 0;
        for (final var clientId : clientTracker.clientIdsSnapshot()) {
            final var transaction = clientTracker.getActiveTransaction(clientId);
            if (transaction == null || Tx2pcLog.isPrepared(transaction.getTransactionId().toString())) {
                continue;
            }
            try {
                rollback(clientId);
                rolledBack++;
            } catch (Exception e) {
                logger.warning("Failed to roll back an open transaction during shutdown: " + e.getMessage());
            }
        }
        for (final var entry : clientTracker.txSessionsSnapshot().entrySet()) {
            final var session = entry.getValue();
            final var transaction = clientTracker.getActiveTransaction(session.clientId());
            if (transaction == null || Tx2pcLog.isPrepared(transaction.getTransactionId().toString())) {
                continue;
            }
            try {
                session.submit(() -> rollback(session.clientId())).get();
                rolledBack++;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.warning("Failed to roll back a forwarded transaction during shutdown: " + e.getMessage());
            }
        }
        if (rolledBack > 0) {
            logger.info("Rolled back " + rolledBack + " open transaction(s) during shutdown");
        }
    }

    public static void reapTransactionsForDeparted(MembershipView view) {
        for (final var entry : clientTracker.txSessionsSnapshot().entrySet()) {
            final var session = entry.getValue();
            final var origin = session.edgeNodeId();
            final var node = origin != null ? view.find(origin) : null;
            if (node != null && node.getState() != NodeState.DEAD) {
                continue;
            }
            final var transaction = clientTracker.getActiveTransaction(session.clientId());
            if (transaction != null && Tx2pcLog.isPrepared(transaction.getTransactionId().toString())) {
                continue;
            }
            try {
                session.submit(() -> rollback(session.clientId())).get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                logger.warning("Failed to reap forwarded transaction: " + ex.getMessage());
            }
            clientTracker.removeTxSession(entry.getKey());
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
        final var acquired = new ArrayList<String>();
        try {
            for (final var collId : new java.util.TreeSet<>(collections)) {
                locks.lockWrite(collId);
                acquired.add(collId);
            }
            final var opIds = Tx2pcLog.sliceOpIds(txId);
            final var ops = AdminOperationHelper.readTransactionOps(opIds);
            ops.sort(java.util.Comparator.comparingLong(AdminTransactionEntry::getSeq));
            final var reconstructed = new Transaction(UUID.fromString(txId), UUID.randomUUID());
            for (final var op : ops) {
                applyBufferedOp(op);
                recordIntoOverlay(reconstructed, op);
            }
            AdminOperationHelper.deleteTransactionOps(opIds);
            TxCommitLog.clearLocalCommit(txId);
            coordinator.replicateTransaction(reconstructed);
        } finally {
            for (final var collId : acquired) {
                locks.releaseWrite(collId);
            }
        }
    }

    private static String dtxIdOf(String recordId) {
        final var sep = recordId.lastIndexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
        return sep > 0 ? recordId.substring(0, sep) : recordId;
    }

    // Buffers the op that consumes a pending trigger run, so it commits with the run's effects.
    public static void bufferTriggerRunConsume(Transaction transaction, String runId) throws Exception {
        final var payload = new JsonObject();
        payload.addProperty(TRIGGER_RUN_ID_FIELD, runId);
        bufferOperation(transaction, AdminTransactionEntry.OP_TYPE_DELETE_TRIGGER_RUN, "", "", payload);
    }

    public static OperationResponse bufferSave(SaveRequest request, Transaction transaction) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        try {
            final var object = request.getObject();
            final var entry = DbEntry.fromJsonObject(dbName, collName, object);
            final var maxEntrySize = configuration.getMaxEntrySize();
            if (entry.byteSize() > maxEntrySize) {
                return new OperationResponse(
                        OperationType.SAVE, "Entry size of " + entry.byteSize()
                                + " bytes exceeds the maximum allowed size of " + maxEntrySize + " bytes",
                        ErrorCode.ENTRY_TOO_LARGE);
            }
            final var lockResult = ensureLock(transaction, OperationType.SAVE, dbName, collName);
            if (lockResult != null) {
                return lockResult;
            }
            final var id = ensureId(object, request.get_id());
            final var collId = Cache.getCollectionIdentifier(dbName, collName);
            final var insert = !isVisible(transaction, collId, cache.getPkIndexAndLoadIfNecessary(dbName, collName),
                    id);
            final var seq = bufferOperation(transaction, AdminTransactionEntry.OP_TYPE_SAVE, dbName, collName, object);
            if (insert) {
                transaction.recordInserts(seq, List.of(id));
            }
            transaction.recordSave(collId, id, object);
            return new SaveResponse("Successfully saved", id);
        } catch (Exception e) {
            return new OperationResponse(OperationType.SAVE, ErrorCode.ERROR_TRANSACTION);
        }
    }

    public static OperationResponse bufferBulkSave(BulkSaveRequest request, Transaction transaction) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        try {
            final var maxEntrySize = configuration.getMaxEntrySize();
            final var seenIds = new HashSet<String>();
            for (final var object : request.getObjects()) {
                final var entry = DbEntry.fromJsonObject(dbName, collName, object);
                if (entry.byteSize() > maxEntrySize) {
                    return new OperationResponse(
                            OperationType.BULK_SAVE, "Entry size of " + entry.byteSize()
                                    + " bytes exceeds the maximum allowed size of " + maxEntrySize + " bytes",
                            ErrorCode.ENTRY_TOO_LARGE);
                }
                final var id = ensureId(object, null);
                if (!seenIds.add(id)) {
                    return new OperationResponse(OperationType.BULK_SAVE, "Duplicate _id in bulk save request: " + id,
                            ErrorCode.DUPLICATE_ID);
                }
            }
            final var lockResult = ensureLock(transaction, OperationType.BULK_SAVE, dbName, collName);
            if (lockResult != null) {
                return lockResult;
            }
            final var collId = Cache.getCollectionIdentifier(dbName, collName);
            final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            final var payload = new JsonObject();
            final var array = new JsonArray();
            final var inserted = new ArrayList<String>();
            final var updated = new ArrayList<String>();
            for (final var object : request.getObjects()) {
                array.add(object);
                final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
                if (isVisible(transaction, collId, primaryKeyIndex, id)) {
                    updated.add(id);
                } else {
                    inserted.add(id);
                }
                transaction.recordSave(collId, id, object);
            }
            payload.add(OBJECTS_FIELD, array);
            final var seq = bufferOperation(transaction, AdminTransactionEntry.OP_TYPE_BULK_SAVE, dbName, collName,
                    payload);
            transaction.recordInserts(seq, inserted);
            return new BulkSaveResponse("Successfully saved entries", inserted, updated);
        } catch (Exception e) {
            return new OperationResponse(OperationType.BULK_SAVE, ErrorCode.ERROR_TRANSACTION);
        }
    }

    public static OperationResponse bufferDelete(DeleteRequest request, Transaction transaction) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var id = request.get_id();
        try {
            final var lockResult = ensureLock(transaction, OperationType.DELETE, dbName, collName);
            if (lockResult != null) {
                return lockResult;
            }
            final var collId = Cache.getCollectionIdentifier(dbName, collName);
            final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            if (!isVisible(transaction, collId, primaryKeyIndex, id)) {
                return new OperationResponse(OperationType.DELETE, "Entry with id " + id + " not found",
                        ErrorCode.ENTRY_NOT_FOUND);
            }
            final var payload = new JsonObject();
            payload.addProperty(Globals.PK_FIELD, id);
            final var deletedDocument = documentForDeletedTrigger(transaction, collId, dbName, collName, id);
            if (deletedDocument != null) {
                payload.add(DELETED_DOCUMENT_FIELD, deletedDocument);
            }
            bufferOperation(transaction, AdminTransactionEntry.OP_TYPE_DELETE, dbName, collName, payload);
            transaction.recordDelete(collId, id);
            return new DeleteResponse("Entry with id " + id + " deleted successfully");
        } catch (Exception e) {
            return new OperationResponse(OperationType.DELETE, ErrorCode.ERROR_TRANSACTION);
        }
    }

    // Applies the transaction's buffered mutations to a materialized read of the committed documents,
    // producing the effective document set the transaction sees (read-your-writes) for an AGGREGATE.
    // Buffered updates replace the committed document, deletes drop it, and pure inserts are appended.
    public static Stream<JsonObject> applyOverlayToStream(Transaction transaction, String collId,
            Stream<JsonObject> committed) {
        final var overlay = transaction.overlayFor(collId);
        if (overlay == null || overlay.isEmpty()) {
            return committed;
        }
        final var seen = new HashSet<String>();
        final var result = new ArrayList<JsonObject>();
        committed.forEach(doc -> {
            final var id = doc.has(Globals.PK_FIELD) ? doc.get(Globals.PK_FIELD).asJsonString().getValue() : null;
            if (id != null && overlay.containsKey(id)) {
                seen.add(id);
                final var buffered = overlay.get(id);
                if (!Transaction.isTombstone(buffered)) {
                    result.add(buffered);
                }
            } else {
                result.add(doc);
            }
        });
        for (final var overlayEntry : overlay.entrySet()) {
            if (!Transaction.isTombstone(overlayEntry.getValue()) && !seen.contains(overlayEntry.getKey())) {
                result.add(overlayEntry.getValue());
            }
        }
        return result.stream();
    }

    private static void fireTriggersForCommittedOps(java.util.List<AdminTransactionEntry> ops, String actingUser,
            int triggerDepth, Transaction transaction) {
        for (final var op : ops) {
            final var dbName = op.getTargetDb();
            final var collName = op.getTargetColl();
            // Which of the op's ids it created rather than updated was decided when the write was buffered:
            // the documents all exist by now, so a save can no longer be told apart from an insert here.
            final var inserted = transaction.insertedIdsFor(op.getSeq());
            switch (op.getOpType()) {
                case AdminTransactionEntry.OP_TYPE_SAVE -> {
                    final var id = op.getPayload().get(Globals.PK_FIELD).asJsonString().getValue();
                    TriggerHelper.afterWriteIds(dbName, collName,
                            inserted.contains(id) ? EventType.CREATED : EventType.UPDATED, List.of(id), actingUser,
                            triggerDepth);
                }
                case AdminTransactionEntry.OP_TYPE_BULK_SAVE -> {
                    final var createdIds = new ArrayList<String>();
                    final var updatedIds = new ArrayList<String>();
                    for (final var element : op.getPayload().get(OBJECTS_FIELD).asJsonArray().asList()) {
                        final var object = element.asJsonObject();
                        if (object.has(Globals.PK_FIELD)) {
                            final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
                            (inserted.contains(id) ? createdIds : updatedIds).add(id);
                        }
                    }
                    TriggerHelper.afterWriteIds(dbName, collName, EventType.CREATED, createdIds, actingUser,
                            triggerDepth);
                    TriggerHelper.afterWriteIds(dbName, collName, EventType.UPDATED, updatedIds, actingUser,
                            triggerDepth);
                }
                // The document was captured when the delete was buffered: by the time the commit finishes it
                // is gone, so re-reading it by id the way the arms above do would find nothing and the
                // DELETED trigger would never fire. Absent when no DELETED trigger existed at buffer time.
                case AdminTransactionEntry.OP_TYPE_DELETE -> {
                    final var payload = op.getPayload();
                    if (payload.has(DELETED_DOCUMENT_FIELD)) {
                        TriggerHelper
                                .afterWrite(dbName, collName, EventType.DELETED,
                                        DbEntry.fromJsonObject(dbName, collName,
                                                payload.get(DELETED_DOCUMENT_FIELD).asJsonObject()),
                                        actingUser, triggerDepth);
                    }
                }
                default -> {
                    // Markers (PREPARED/COMMIT/OUTCOME/LOCAL_COMMIT) and the trigger-run consume op are not
                    // writes and fire nothing.
                }
            }
        }
    }

    private static void applyBufferedOp(AdminTransactionEntry op) throws Exception {
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

    // Persists one buffered operation to admin/transactions and records its id on the transaction so it
    // can be replayed (commit) and removed (commit/rollback).
    private static long bufferOperation(Transaction transaction, String opType, String dbName, String collName,
            JsonObject payload) throws Exception {
        final var seq = transaction.nextSeq();
        final var opEntry = new AdminTransactionEntry(transaction.getTransactionId().toString(),
                transaction.getClientId().toString(), seq, opType, dbName, collName, payload);
        AdminOperationHelper.saveTransactionOp(opEntry);
        transaction.addBufferedOpId(opEntry.get_id());
        return seq;
    }

    // Acquires the collection's write lock for the transaction on first touch (holding it until
    // commit/rollback). Returns null on success, or a lock-timeout response after auto-rolling back the
    // transaction when the lock cannot be taken within the bound.
    private static OperationResponse ensureLock(Transaction transaction, OperationType type, String dbName,
            String collName) throws InterruptedException {
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        if (transaction.holdsLock(collId)) {
            return null;
        }
        if (locks.tryLockWrite(dbName, collName, configuration.getTransactionLockTimeoutMs())) {
            transaction.addHeldLock(collId);
            return null;
        }
        rollback(transaction.getClientId());
        return new OperationResponse(type, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
    }

    private static void releaseHeldLocks(Transaction transaction) {
        for (final var collId : transaction.getHeldLocks()) {
            locks.releaseWrite(collId);
        }
        transaction.getHeldLocks().clear();
    }

    // Ensures the object carries an _id (assigning a UUID when absent), returning the effective id.
    private static String ensureId(JsonObject object, String requestId) {
        var id = requestId;
        if (id == null) {
            id = object.has(Globals.PK_FIELD)
                    ? object.get(Globals.PK_FIELD).asJsonString().getValue()
                    : UUID.randomUUID().toString();
        }
        object.addProperty(Globals.PK_FIELD, id);
        return id;
    }

    // Whether the id is currently visible to the transaction: present (non-tombstone) in the overlay,
    // or — absent from the overlay — present in the committed PK index.
    // The document the buffered delete will remove, captured now because the commit that fires the trigger
    // can no longer read it. Null when no DELETED trigger would fire, so an untriggered collection neither
    // pays for the read nor stores a second copy of the document. A document this transaction saved earlier
    // wins over the committed one: the buffered ops replay in order, so that is the version being removed.
    private static JsonObject documentForDeletedTrigger(Transaction transaction, String collId, String dbName,
            String collName, String id) {
        final var depth = transaction.getTriggerDepth();
        if (!TriggerHelper.firesOnDelete(dbName, collName, depth)) {
            return null;
        }
        final var overlay = transaction.overlayFor(collId);
        final var buffered = overlay == null ? null : overlay.get(id);
        if (buffered != null && !Transaction.isTombstone(buffered)) {
            return buffered;
        }
        final var captured = TriggerHelper.captureForDelete(dbName, collName, id, depth);
        return captured == null ? null : captured.getData();
    }

    private static boolean isVisible(Transaction transaction, String collId, List<PkIndexEntry> primaryKeyIndex,
            String id) {
        final var overlay = transaction.overlayFor(collId);
        if (overlay != null && overlay.containsKey(id)) {
            return !Transaction.isTombstone(overlay.get(id));
        }
        return Collections.binarySearch(primaryKeyIndex, id) >= 0;
    }
}
