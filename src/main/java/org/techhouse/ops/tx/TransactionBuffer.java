package org.techhouse.ops.tx;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.DbEntry;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;

// Appends a transaction's writes to its durable slice and mirrors them into the session overlay,
// so the transaction reads its own writes while nothing is visible to anyone else until commit
// replays them. Ending the transaction on a lock timeout is the caller's to supply.
public final class TransactionBuffer {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final String OBJECTS_FIELD = "objects";
    private static final String DELETED_DOCUMENT_FIELD = "deletedDocument";
    private static final String TRIGGER_RUN_ID_FIELD = "triggerRunId";

    private TransactionBuffer() {
    }

    // Buffers the op that consumes a pending trigger run, so it commits with the run's effects.
    public static void bufferTriggerRunConsume(Transaction transaction, String runId) throws Exception {
        final var payload = new JsonObject();
        payload.addProperty(TRIGGER_RUN_ID_FIELD, runId);
        bufferOperation(transaction, AdminTransactionEntry.OP_TYPE_DELETE_TRIGGER_RUN, "", "", payload);
    }

    public static OperationResponse bufferSave(SaveRequest request, Transaction transaction, Runnable onLockTimeout) {
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
            final var lockResult = ensureLock(transaction, OperationType.SAVE, dbName, collName, onLockTimeout);
            if (lockResult != null) {
                return lockResult;
            }
            final var id = TransactionWrites.ensureId(object, request.get_id());
            final var collId = Cache.getCollectionIdentifier(dbName, collName);
            final var insert = !TransactionWrites.isVisible(transaction, collId,
                    cache.getPkIndexAndLoadIfNecessary(dbName, collName), id);
            final var hooked = TransactionWrites.runBeforeHooks(dbName, collName,
                    insert ? EventType.CREATED : EventType.UPDATED,
                    clientTracker.getAuthenticatedUsername(transaction.getClientId()), object, id, OperationType.SAVE);
            if (hooked.isRejected()) {
                return hooked.rejection();
            }
            final var effective = hooked.document();
            final var sizeError = TransactionWrites.checkEntrySize(dbName, collName, effective, object,
                    OperationType.SAVE);
            if (sizeError != null) {
                return sizeError;
            }
            final var seq = bufferOperation(transaction, AdminTransactionEntry.OP_TYPE_SAVE, dbName, collName,
                    effective);
            if (insert) {
                transaction.recordInserts(seq, List.of(id));
            }
            transaction.recordSave(collId, id, effective);
            return new SaveResponse("Successfully saved", id);
        } catch (Exception e) {
            return new OperationResponse(OperationType.SAVE, ErrorCode.ERROR_TRANSACTION);
        }
    }

    public static OperationResponse bufferBulkSave(BulkSaveRequest request, Transaction transaction,
            Runnable onLockTimeout) {
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
                final var id = TransactionWrites.ensureId(object, null);
                if (!seenIds.add(id)) {
                    return new OperationResponse(OperationType.BULK_SAVE, "Duplicate _id in bulk save request: " + id,
                            ErrorCode.DUPLICATE_ID);
                }
            }
            final var lockResult = ensureLock(transaction, OperationType.BULK_SAVE, dbName, collName, onLockTimeout);
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
                final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
                final var isUpdate = TransactionWrites.isVisible(transaction, collId, primaryKeyIndex, id);
                final var hooked = TransactionWrites.runBeforeHooks(dbName, collName,
                        isUpdate ? EventType.UPDATED : EventType.CREATED,
                        clientTracker.getAuthenticatedUsername(transaction.getClientId()), object, id,
                        OperationType.BULK_SAVE);
                if (hooked.isRejected()) {
                    return hooked.rejection();
                }
                final var effective = hooked.document();
                final var sizeError = TransactionWrites.checkEntrySize(dbName, collName, effective, object,
                        OperationType.BULK_SAVE);
                if (sizeError != null) {
                    return sizeError;
                }
                array.add(effective);
                if (isUpdate) {
                    updated.add(id);
                } else {
                    inserted.add(id);
                }
                transaction.recordSave(collId, id, effective);
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

    public static OperationResponse bufferDelete(DeleteRequest request, Transaction transaction,
            Runnable onLockTimeout) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var id = request.get_id();
        try {
            final var lockResult = ensureLock(transaction, OperationType.DELETE, dbName, collName, onLockTimeout);
            if (lockResult != null) {
                return lockResult;
            }
            final var collId = Cache.getCollectionIdentifier(dbName, collName);
            final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            if (!TransactionWrites.isVisible(transaction, collId, primaryKeyIndex, id)) {
                return new OperationResponse(OperationType.DELETE, "Entry with id " + id + " not found",
                        ErrorCode.ENTRY_NOT_FOUND);
            }
            final var deleteHook = TransactionWrites.runDeleteBeforeHooks(transaction, collId, dbName, collName, id);
            if (deleteHook != null) {
                return deleteHook;
            }
            final var payload = new JsonObject();
            payload.addProperty(Globals.PK_FIELD, id);
            final var deletedDocument = TransactionWrites.documentForDeletedTrigger(transaction, collId, dbName,
                    collName, id);
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
    // A lock timeout ends the transaction, but ending it belongs to the lifecycle rather than to the
    // buffering path, so the caller passes in what to do rather than this reaching back for it.
    public static OperationResponse ensureLock(Transaction transaction, OperationType type, String dbName,
            String collName, Runnable onTimeout) throws InterruptedException {
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        if (transaction.holdsLock(collId)) {
            return null;
        }
        if (locks.tryLockWrite(dbName, collName, configuration.getTransactionLockTimeoutMs())) {
            transaction.addHeldLock(collId);
            return null;
        }
        onTimeout.run();
        return new OperationResponse(type, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
    }
}
