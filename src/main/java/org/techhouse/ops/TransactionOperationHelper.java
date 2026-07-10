package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.ownership.OwnershipManager;
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
    private TransactionOperationHelper() {
    }

    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final ClusterRouter clusterRouter = IocContainer.get(ClusterRouter.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(TransactionOperationHelper.class);

    private static final String OBJECTS_FIELD = "objects";

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
        if (clientTracker.getActiveTransaction(clientId) != null) {
            return new OperationResponse(OperationType.START_TRANSACTION, ErrorCode.TRANSACTION_ALREADY_ACTIVE);
        }
        final var transactionId = UUID.randomUUID();
        clientTracker.setActiveTransaction(clientId, new Transaction(transactionId, clientId));
        return new StartTransactionResponse("Transaction started", transactionId.toString());
    }

    public static OperationResponse commit(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.NO_ACTIVE_TRANSACTION);
        }
        try {
            // A clustered commit must still hold a write quorum (split-brain protection); abort before
            // applying if it was lost between the first write and commit.
            if (!coordinator.hasTransactionQuorum()) {
                AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
                return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.NO_QUORUM);
            }
            final var ops = AdminOperationHelper.readTransactionOps(transaction.getBufferedOpIds());
            for (final var op : ops) {
                applyBufferedOp(op);
            }
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
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
            clientTracker.clearTransactionBinding(clientId);
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
            clientTracker.clearTransactionBinding(clientId);
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
        try {
            clusterRouter.teardownTransaction(clientId);
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
        } catch (Exception e) {
            logger.warning("Failed to clean up transaction on disconnect: " + e.getMessage());
        } finally {
            releaseHeldLocks(transaction);
            clientTracker.clearActiveTransaction(clientId);
            clientTracker.clearTransactionBinding(clientId);
        }
    }

    // Owner-side safety net: rolls back and releases forwarded transactions whose originating edge node has
    // left the cluster (absent or DEAD), so an edge-node crash cannot strand the owner's write locks. The
    // rollback runs on each session's own executor thread (the holder of its locks).
    public static void reapTransactionsForDeparted(MembershipView view) {
        for (final var entry : clientTracker.txSessionsSnapshot().entrySet()) {
            final var session = entry.getValue();
            final var origin = session.edgeNodeId();
            final var node = origin != null ? view.find(origin) : null;
            if (node != null && node.getState() != NodeState.DEAD) {
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

    // Removes any operation records left in admin/transactions by transactions that were open when the
    // server stopped (their owning connections are gone, so the records are orphans). Called at startup.
    public static void cleanupOrphansAtStartup() throws Exception {
        final var opIds = new ArrayList<>(cache.getTransactionPkIndexes().keySet());
        if (opIds.isEmpty()) {
            return;
        }
        AdminOperationHelper.deleteTransactionOps(opIds);
        logger.info("Removed " + opIds.size() + " orphaned transaction operation(s) at startup");
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
            final var ownerResult = enforceSingleOwner(OperationType.SAVE, dbName, collName);
            if (ownerResult != null) {
                return ownerResult;
            }
            final var lockResult = ensureLock(transaction, OperationType.SAVE, dbName, collName);
            if (lockResult != null) {
                return lockResult;
            }
            final var id = ensureId(object, request.get_id());
            bufferOperation(transaction, AdminTransactionEntry.OP_TYPE_SAVE, dbName, collName, object);
            transaction.recordSave(Cache.getCollectionIdentifier(dbName, collName), id, object);
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
            final var ownerResult = enforceSingleOwner(OperationType.BULK_SAVE, dbName, collName);
            if (ownerResult != null) {
                return ownerResult;
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
            bufferOperation(transaction, AdminTransactionEntry.OP_TYPE_BULK_SAVE, dbName, collName, payload);
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
            final var ownerResult = enforceSingleOwner(OperationType.DELETE, dbName, collName);
            if (ownerResult != null) {
                return ownerResult;
            }
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
            default -> throw new IllegalStateException("Unknown transaction op type: " + op.getOpType());
        }
    }

    // Persists one buffered operation to admin/transactions and records its id on the transaction so it
    // can be replayed (commit) and removed (commit/rollback).
    private static void bufferOperation(Transaction transaction, String opType, String dbName, String collName,
            JsonObject payload) throws Exception {
        final var seq = transaction.nextSeq();
        final var opEntry = new AdminTransactionEntry(transaction.getTransactionId().toString(),
                transaction.getClientId().toString(), seq, opType, dbName, collName, payload);
        AdminOperationHelper.saveTransactionOp(opEntry);
        transaction.addBufferedOpId(opEntry.get_id());
    }

    // Under clustering, a transaction is pinned to the owner of its collections: a write to a collection this
    // node does not own is a cross-owner attempt and is rejected. Returns null when allowed.
    private static OperationResponse enforceSingleOwner(OperationType type, String dbName, String collName) {
        if (clusterConfig.isEnabled() && !ownershipManager.isOwner(dbName, collName)) {
            return new OperationResponse(type, ErrorCode.CROSS_OWNER_TRANSACTION);
        }
        return null;
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
    private static boolean isVisible(Transaction transaction, String collId, List<PkIndexEntry> primaryKeyIndex,
            String id) {
        final var overlay = transaction.overlayFor(collId);
        if (overlay != null && overlay.containsKey(id)) {
            return !Transaction.isTombstone(overlay.get(id));
        }
        return Collections.binarySearch(primaryKeyIndex, id) >= 0;
    }
}
