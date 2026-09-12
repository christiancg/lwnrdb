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
import org.techhouse.ops.EntrySizeGuard;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;

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

    public static void bufferTriggerRunConsume(Transaction transaction, String runId) throws Exception {
        final var payload = new JsonObject();
        payload.addProperty(TRIGGER_RUN_ID_FIELD, runId);
        bufferOperation(transaction, AdminTransactionEntry.OP_TYPE_DELETE_TRIGGER_RUN, "", "", payload);
    }

    public static OperationResponse bufferSave(SaveRequest request, Transaction transaction, Runnable onLockTimeout) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        return OperationResponse.respondOrError(OperationType.SAVE, ErrorCode.ERROR_TRANSACTION, () -> {
            final var object = request.getObject();
            final var entry = DbEntry.fromJsonObject(dbName, collName, object);
            final var entrySizeError = EntrySizeGuard.check(entry, OperationType.SAVE);
            if (entrySizeError != null) {
                return entrySizeError;
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
        });
    }

    public static OperationResponse bufferBulkSave(BulkSaveRequest request, Transaction transaction,
            Runnable onLockTimeout) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        return OperationResponse.respondOrError(OperationType.BULK_SAVE, ErrorCode.ERROR_TRANSACTION, () -> {
            final var seenIds = new HashSet<String>();
            for (final var object : request.getObjects()) {
                final var entry = DbEntry.fromJsonObject(dbName, collName, object);
                final var entrySizeError = EntrySizeGuard.check(entry, OperationType.BULK_SAVE);
                if (entrySizeError != null) {
                    return entrySizeError;
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
        });
    }

    public static OperationResponse bufferDelete(DeleteRequest request, Transaction transaction,
            Runnable onLockTimeout) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var id = request.get_id();
        return OperationResponse.respondOrError(OperationType.DELETE, ErrorCode.ERROR_TRANSACTION, () -> {
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
        });
    }

    private static long bufferOperation(Transaction transaction, String opType, String dbName, String collName,
            JsonObject payload) throws Exception {
        final var seq = transaction.nextSeq();
        final var opEntry = new AdminTransactionEntry(transaction.getTransactionId().toString(),
                transaction.getClientId().toString(), seq, opType, dbName, collName, payload);
        AdminOperationHelper.saveTransactionOp(opEntry);
        transaction.addBufferedOpId(opEntry.get_id());
        return seq;
    }

    // The collection write lock is taken on first touch and held until commit/rollback. Ending the
    // transaction on timeout belongs to the lifecycle, so the caller supplies onTimeout.
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
