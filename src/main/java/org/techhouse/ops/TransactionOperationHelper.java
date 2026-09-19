package org.techhouse.ops;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.TxSession;
import org.techhouse.data.DbEntry;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.StartTransactionResponse;
import org.techhouse.ops.tx.TransactionBuffer;
import org.techhouse.ops.tx.TransactionRecovery;

// A transaction holds its collection write locks on the connection's own virtual thread until commit or rollback.
public final class TransactionOperationHelper {
    private TransactionOperationHelper() {
    }

    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final org.techhouse.listen.ListenManager listenManager = IocContainer
            .get(org.techhouse.listen.ListenManager.class);
    private static final ClusterRouter clusterRouter = IocContainer.get(ClusterRouter.class);
    private static final Logger logger = Logger.logFor(TransactionOperationHelper.class);

    private static final String OBJECTS_FIELD = "objects";
    private static final String DELETED_DOCUMENT_FIELD = "deletedDocument";

    // START_TRANSACTION is allowed through so start() reports the "already active" conflict, not a generic one.
    public static boolean isAllowedDuringTransaction(OperationType type) {
        return switch (type) {
            case START_TRANSACTION, COMMIT_TRANSACTION, ROLLBACK_TRANSACTION, SAVE, BULK_SAVE, DELETE, FIND_BY_ID,
                    AGGREGATE, CLOSE_CONNECTION ->
                true;
            default -> false;
        };
    }

    public static boolean isAllowedOnAbortedTransaction(OperationType type) {
        return switch (type) {
            case ROLLBACK_TRANSACTION, COMMIT_TRANSACTION, CLOSE_CONNECTION -> true;
            default -> false;
        };
    }

    public static OperationResponse start(UUID clientId) {
        return start(clientId, UUID.randomUUID(), 0);
    }

    // A forwarded 2PC participant passes the coordinator's tx id, so slice and recovery markers key on the same id.
    public static OperationResponse start(UUID clientId, UUID transactionId, int triggerDepth) {
        if (clientTracker.getActiveTransaction(clientId) != null) {
            return new OperationResponse(OperationType.START_TRANSACTION, ErrorCode.TRANSACTION_ALREADY_ACTIVE);
        }
        final var transaction = new Transaction(transactionId, clientId);
        transaction.setTriggerDepth(triggerDepth);
        clientTracker.setActiveTransaction(clientId, transaction);
        return new StartTransactionResponse("Transaction started", transactionId.toString());
    }

    public static OperationResponse abort(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return new OperationResponse(OperationType.ROLLBACK_TRANSACTION, ErrorCode.NO_ACTIVE_TRANSACTION);
        }
        try {
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
            TransactionRecovery.resolveMarkers(transaction.getTransactionId().toString(), false);
            return OperationResponse.ok(OperationType.ROLLBACK_TRANSACTION, "Transaction aborted");
        } catch (Exception e) {
            logger.error(OperationType.ROLLBACK_TRANSACTION + " failed with " + ErrorCode.ERROR_TRANSACTION.getCode(),
                    e);
            return new OperationResponse(OperationType.ROLLBACK_TRANSACTION, ErrorCode.ERROR_TRANSACTION);
        } finally {
            releaseHeldLocks(transaction);
            clientTracker.clearActiveTransaction(clientId);
            clientTracker.clearTransactionState(clientId);
        }
    }

    public static void cleanupOrphansAtStartup() throws Exception {
        TransactionRecovery.cleanupOrphansAtStartup();
    }

    public static void commitLocalFromDurable(String txId, List<String> collections) throws Exception {
        TransactionRecovery.commitLocalFromDurable(txId, collections);
    }

    public static OperationResponse commit(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.NO_ACTIVE_TRANSACTION);
        }
        if (transaction.isAborted()) {
            clientTracker.clearActiveTransaction(clientId);
            clientTracker.clearTransactionState(clientId);
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.TRANSACTION_NOT_USABLE);
        }
        var fenced = false;
        var pastCommitPoint = false;
        try {
            // A clustered commit must still hold a write quorum: abort before applying if it was lost.
            if (coordinator.hasNotTransactionQuorum()) {
                AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
                return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.NO_QUORUM);
            }
            if (ownershipMoved(clientId, transaction)) {
                AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
                return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.NOT_COLLECTION_OWNER);
            }
            final var ops = AdminOperationHelper.readTransactionOps(transaction.getBufferedOpIds());
            final var txId = transaction.getTransactionId().toString();
            // The commit point: durable before the first op is applied, so a crash after this is finished by
            // cleanupOrphansAtStartup instead of leaving the transaction half-applied.
            TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(),
                    new ArrayList<>(transaction.getHeldLocks()));
            pastCommitPoint = true;
            final var reservedTombstones = coordinator.reserveTransactionTombstones(transaction);
            listenManager.deferNotifications();
            final boolean applied;
            try {
                applied = TransactionRecovery.applyAllWithRetry(ops, txId);
            } finally {
                listenManager.flushDeferredNotifications();
            }
            if (!applied) {
                fenced = true;
                return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.TRANSACTION_HALF_APPLIED);
            }
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
            TxCommitLog.clearLocalCommit(txId);
            pastCommitPoint = false;
            // After the durable commit, so a trigger never observes a transaction that later rolled back. The
            // transaction's own depth is used, not zero, or allowCascade=true would cascade forever.
            fireTriggersForCommittedOps(ops, clientTracker.getAuthenticatedUsername(clientId),
                    transaction.getTriggerDepth(), transaction);
            // The local commit stands even on a replication timeout; anti-entropy reconciles the replicas.
            if (coordinator.replicateTransaction(transaction, reservedTombstones) == ReplicationOutcome.TIMEOUT) {
                return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.REPLICATION_TIMEOUT);
            }
            return OperationResponse.ok(OperationType.COMMIT_TRANSACTION, "Transaction committed");
        } catch (Exception e) {
            logger.error(OperationType.COMMIT_TRANSACTION + " failed with " + ErrorCode.ERROR_TRANSACTION.getCode(), e);
            if (pastCommitPoint) {
                fenced = true;
                return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.TRANSACTION_HALF_APPLIED);
            }
            return new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.ERROR_TRANSACTION);
        } catch (Throwable t) {
            fenced = pastCommitPoint;
            throw t;
        } finally {
            if (!fenced) {
                releaseHeldLocks(transaction);
                clientTracker.clearActiveTransaction(clientId);
                clientTracker.clearTransactionState(clientId);
            }
        }
    }

    public static void abortInPlace(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return;
        }
        transaction.markAborted();
        try {
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
        } catch (Exception e) {
            logger.error("Failed to discard the buffered operations of an aborted transaction", e);
        } finally {
            releaseHeldLocks(transaction);
        }
    }

    public static OperationResponse rollback(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return new OperationResponse(OperationType.ROLLBACK_TRANSACTION, ErrorCode.NO_ACTIVE_TRANSACTION);
        }
        try {
            AdminOperationHelper.deleteTransactionOps(transaction.getBufferedOpIds());
            return OperationResponse.ok(OperationType.ROLLBACK_TRANSACTION, "Transaction rolled back");
        } catch (Exception e) {
            logger.error(OperationType.ROLLBACK_TRANSACTION + " failed with " + ErrorCode.ERROR_TRANSACTION.getCode(),
                    e);
            return new OperationResponse(OperationType.ROLLBACK_TRANSACTION, ErrorCode.ERROR_TRANSACTION);
        } finally {
            releaseHeldLocks(transaction);
            clientTracker.clearActiveTransaction(clientId);
            clientTracker.clearTransactionState(clientId);
        }
    }

    // Runs on the connection's own thread - the only thread allowed to release its write locks.
    public static void cleanupOnDisconnect(UUID clientId) {
        try {
            releaseOnDisconnect(clientId);
        } finally {
            ShutdownRollbackWaits.complete(clientId);
        }
    }

    private static void releaseOnDisconnect(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction == null) {
            return;
        }
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

    public static void rollbackOpenTransactionsAtShutdown() {
        var rolledBack = 0;
        final var sessionClientIds = clientTracker.txSessionsSnapshot().values().stream().map(TxSession::clientId)
                .collect(Collectors.toSet());
        final var signalled = new ArrayList<CountDownLatch>();
        for (final var clientId : clientTracker.clientIdsSnapshot()) {
            final var transaction = clientTracker.getActiveTransaction(clientId);
            if (transaction == null || sessionClientIds.contains(clientId)
                    || Tx2pcLog.isPrepared(transaction.getTransactionId().toString())) {
                continue;
            }
            final var wait = ShutdownRollbackWaits.register(clientId);
            if (clientTracker.signalDisconnect(clientId)) {
                signalled.add(wait);
                continue;
            }
            ShutdownRollbackWaits.cancel(clientId);
            try {
                rollback(clientId);
                rolledBack++;
            } catch (Exception e) {
                logger.warning("Failed to roll back an open transaction during shutdown: " + e.getMessage());
            }
        }
        rolledBack += ShutdownRollbackWaits.await(signalled);
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
            abandonSession(entry.getKey());
        }
    }

    public static void abandonSession(String sessionId) {
        final var session = clientTracker.txSession(sessionId);
        if (session == null) {
            return;
        }
        try {
            session.submit(() -> rollback(session.clientId())).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            logger.warning("Failed to reap forwarded transaction: " + ex.getMessage());
        }
        clientTracker.removeTxSession(sessionId);
    }

    public static void bufferTriggerRunConsume(Transaction transaction, String runId) throws Exception {
        TransactionBuffer.bufferTriggerRunConsume(transaction, runId);
    }

    public static OperationResponse bufferSave(SaveRequest request, Transaction transaction) {
        return TransactionBuffer.bufferSave(request, transaction, () -> abortInPlace(transaction.getClientId()));
    }

    public static OperationResponse bufferBulkSave(BulkSaveRequest request, Transaction transaction) {
        return TransactionBuffer.bufferBulkSave(request, transaction, () -> abortInPlace(transaction.getClientId()));
    }

    public static OperationResponse bufferDelete(DeleteRequest request, Transaction transaction) {
        return TransactionBuffer.bufferDelete(request, transaction, () -> abortInPlace(transaction.getClientId()));
    }

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

    public static void fireTriggersForCommittedOps(java.util.List<AdminTransactionEntry> ops, String actingUser,
            int triggerDepth, Transaction transaction) {
        for (final var op : ops) {
            final var dbName = op.getTargetDb();
            final var collName = op.getTargetColl();
            // Which ids the op created rather than updated was decided when the write was buffered; by now all
            // documents exist, so an insert can no longer be told apart from an update here.
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
                // The deleted document was captured when the delete was buffered; re-reading it by id here
                // would find nothing. Absent when no DELETED trigger existed at buffer time.
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
                    // Markers and the trigger-run consume op are not writes and fire nothing.
                }
            }
        }
    }

    private static boolean ownershipMoved(UUID clientId, Transaction transaction) {
        for (final var session : clientTracker.txSessionsSnapshot().values()) {
            if (session.clientId().equals(clientId)) {
                return false;
            }
        }
        for (final var collId : transaction.getHeldLocks()) {
            final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length == 2 && !coordinator.stillOwns(parts[0], parts[1])) {
                return true;
            }
        }
        return false;
    }

    static void releaseHeldLocks(Transaction transaction) {
        final var stranded = new ArrayList<String>();
        for (final var collId : transaction.getHeldLocks()) {
            if (!locks.releaseWrite(collId)) {
                stranded.add(collId);
            }
        }
        transaction.getHeldLocks().clear();
        if (!stranded.isEmpty()) {
            transaction.getHeldLocks().addAll(stranded);
            logger.warning("Could not release " + stranded.size() + " write lock(s) from this thread; they stay "
                    + "recorded on the transaction for their owner to release");
        }
    }

}
