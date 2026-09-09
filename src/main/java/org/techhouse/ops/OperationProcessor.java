package org.techhouse.ops;

import java.util.List;
import java.util.UUID;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.DbEntry;
import org.techhouse.data.Transaction;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.CollectionOperationHelper;
import org.techhouse.ops.admin.DatabaseOperationHelper;
import org.techhouse.ops.admin.ListenOperationHelper;
import org.techhouse.ops.admin.ReadPathHelper;
import org.techhouse.ops.admin.RunDirectoryHelper;
import org.techhouse.ops.index.IndexOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.CancelScriptRequest;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.DeleteSchemaRequest;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ListCollectionsRequest;
import org.techhouse.ops.req.ListProceduresRequest;
import org.techhouse.ops.req.ListSchedulesRequest;
import org.techhouse.ops.req.ListTriggerRunsRequest;
import org.techhouse.ops.req.ListTriggersRequest;
import org.techhouse.ops.req.ListUsersRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.ResolveTransactionRequest;
import org.techhouse.ops.req.ResolveTriggerRunRequest;
import org.techhouse.ops.req.RunScriptRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SaveSchemaRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.req.StopListenRequest;
import org.techhouse.ops.req.TestTriggerRequest;
import org.techhouse.ops.resp.CloseConnectionResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.ListUsersResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;

public class OperationProcessor {
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    public OperationResponse processMessage(OperationRequest operationRequest) {
        return processMessage(operationRequest, null);
    }

    public OperationResponse processMessage(OperationRequest operationRequest, UUID clientId) {
        final var activeTransaction = clientTracker.getActiveTransaction(clientId);
        // While a transaction is open, only its data operations, its own reads and the control
        // operations are allowed; everything else (DDL/admin/listen) is rejected to keep atomicity
        // reasoning simple (see TransactionOperationHelper.isAllowedDuringTransaction).
        if (activeTransaction != null
                && !TransactionOperationHelper.isAllowedDuringTransaction(operationRequest.getType())) {
            return new OperationResponse(operationRequest.getType(), ErrorCode.OPERATION_NOT_ALLOWED_IN_TRANSACTION);
        }
        final var adminGuard = ClusterAdminHelper.guard(operationRequest);
        if (adminGuard != null) {
            return adminGuard;
        }
        final var actingUser = clientTracker.getAuthenticatedUsername(clientId);
        final var response = switch (operationRequest.getType()) {
            case BULK_SAVE ->
                processBulkSaveOperation((BulkSaveRequest) operationRequest, activeTransaction, actingUser);
            case SAVE -> processSaveOperation((SaveRequest) operationRequest, activeTransaction, actingUser);
            case FIND_BY_ID -> processFindByIdOperation((FindByIdRequest) operationRequest, activeTransaction);
            case AGGREGATE -> processAggregateOperation((AggregateRequest) operationRequest, activeTransaction);
            case DELETE -> processDeleteOperation((DeleteRequest) operationRequest, activeTransaction, actingUser);
            case CREATE_DATABASE -> processCreateDatabaseOperation((CreateDatabaseRequest) operationRequest, clientId);
            case DROP_DATABASE -> processDropDatabaseOperation((DropDatabaseRequest) operationRequest);
            case LIST_DATABASES -> processListDatabasesOperation();
            case CREATE_COLLECTION -> processCreateCollectionOperation((CreateCollectionRequest) operationRequest);
            case DROP_COLLECTION -> processDropCollectionOperation((DropCollectionRequest) operationRequest);
            case LIST_COLLECTIONS -> processListCollectionsOperation((ListCollectionsRequest) operationRequest);
            case CREATE_INDEX -> processCreateIndex((CreateIndexRequest) operationRequest);
            case DROP_INDEX -> processDropIndex((DropIndexRequest) operationRequest);
            case REINDEX -> processReindex((ReindexRequest) operationRequest);
            case SAVE_SCHEMA -> processSaveSchema((SaveSchemaRequest) operationRequest);
            case DELETE_SCHEMA -> processDeleteSchema((DeleteSchemaRequest) operationRequest);
            case CLOSE_CONNECTION -> new CloseConnectionResponse();
            case AUTHENTICATE ->
                UserOperationHelper.processAuthenticate((AuthenticateRequest) operationRequest, clientId);
            case CREATE_USER -> UserOperationHelper.processCreateUser((CreateUserRequest) operationRequest);
            case DELETE_USER -> UserOperationHelper.processDeleteUser((DeleteUserRequest) operationRequest);
            case CHANGE_PERMISSIONS ->
                UserOperationHelper.processChangePermissions((ChangePermissionsRequest) operationRequest);
            case SET_DATABASE_OWNERS -> processSetDatabaseOwners((SetDatabaseOwnersRequest) operationRequest);
            case LIST_USERS -> processListUsers((ListUsersRequest) operationRequest);
            case SET_PASSWORD ->
                UserOperationHelper.processSetPassword((SetPasswordRequest) operationRequest, clientId);
            case GET_DATABASE_STATS -> DatabaseStatsHelper.processGetDatabaseStats();
            case LISTEN -> processListenOperation((ListenRequest) operationRequest, clientId);
            case STOP_LISTEN -> processStopListenOperation((StopListenRequest) operationRequest);
            case START_TRANSACTION ->
                TransactionOperationHelper.start(clientId, UUID.randomUUID(), operationRequest.getTriggerDepth());
            case COMMIT_TRANSACTION -> TransactionOperationHelper.commit(clientId);
            case ROLLBACK_TRANSACTION -> TransactionOperationHelper.rollback(clientId);
            case RESOLVE_TRANSACTION -> processResolveTransaction((ResolveTransactionRequest) operationRequest);
            case LIST_TRANSACTIONS -> processListTransactions();
            case RUN_SCRIPT -> processRunScriptOperation((RunScriptRequest) operationRequest, actingUser, clientId);
            case SAVE_PROCEDURE -> processSaveProcedure((SaveProcedureRequest) operationRequest, actingUser);
            case DELETE_PROCEDURE -> processDeleteProcedure((DeleteProcedureRequest) operationRequest);
            case LIST_PROCEDURES -> processListProcedures((ListProceduresRequest) operationRequest);
            case CALL_PROCEDURE -> processCallProcedure((CallProcedureRequest) operationRequest, actingUser, clientId);
            case SAVE_TRIGGER -> processSaveTrigger((SaveTriggerRequest) operationRequest, actingUser);
            case DELETE_TRIGGER -> processDeleteTrigger((DeleteTriggerRequest) operationRequest);
            case LIST_TRIGGERS -> processListTriggers((ListTriggersRequest) operationRequest);
            case TEST_TRIGGER -> processTestTrigger((TestTriggerRequest) operationRequest, actingUser);
            case SAVE_SCHEDULE -> processSaveSchedule((SaveScheduleRequest) operationRequest, actingUser);
            case DELETE_SCHEDULE -> processDeleteSchedule((DeleteScheduleRequest) operationRequest);
            case LIST_SCHEDULES -> processListSchedules((ListSchedulesRequest) operationRequest);
            case LIST_SCRIPTS -> processListScripts();
            case CANCEL_SCRIPT -> processCancelScript((CancelScriptRequest) operationRequest);
            case LIST_TRIGGER_RUNS -> processListTriggerRuns((ListTriggerRunsRequest) operationRequest);
            case RESOLVE_TRIGGER_RUN -> processResolveTriggerRun((ResolveTriggerRunRequest) operationRequest);
        };
        return ClusterAdminHelper.afterAdminOp(operationRequest, actingUser, response);
    }

    private OperationResponse processCreateIndex(CreateIndexRequest createIndexRequest) {
        return IndexOperationHelper.processCreateIndex(createIndexRequest);
    }

    private OperationResponse processDropIndex(DropIndexRequest dropIndexRequest) {
        return IndexOperationHelper.processDropIndex(dropIndexRequest);
    }

    private OperationResponse processReindex(ReindexRequest request) {
        return IndexOperationHelper.processReindex(request);
    }

    private OperationResponse processFindByIdOperation(FindByIdRequest findbyIdRequest, Transaction activeTransaction) {
        return ReadPathHelper.processFindByIdOperation(findbyIdRequest, activeTransaction);
    }

    private OperationResponse processAggregateOperation(AggregateRequest aggregateRequest,
            Transaction activeTransaction) {
        return ReadPathHelper.processAggregateOperation(aggregateRequest, activeTransaction);
    }

    private OperationResponse processResolveTransaction(ResolveTransactionRequest request) {
        return RunDirectoryHelper.processResolveTransaction(request);
    }

    private OperationResponse processListScripts() {
        return RunDirectoryHelper.processListScripts();
    }

    private OperationResponse processCancelScript(CancelScriptRequest request) {
        return RunDirectoryHelper.processCancelScript(request);
    }

    private OperationResponse processListTriggerRuns(ListTriggerRunsRequest request) {
        return RunDirectoryHelper.processListTriggerRuns(request);
    }

    private OperationResponse processResolveTriggerRun(ResolveTriggerRunRequest request) {
        return RunDirectoryHelper.processResolveTriggerRun(request);
    }

    private OperationResponse processListTransactions() {
        return RunDirectoryHelper.processListTransactions();
    }

    private OperationResponse processCreateDatabaseOperation(CreateDatabaseRequest createDatabaseRequest,
            UUID clientId) {
        return DatabaseOperationHelper.processCreateDatabaseOperation(createDatabaseRequest, clientId);
    }

    private OperationResponse processSetDatabaseOwners(SetDatabaseOwnersRequest request) {
        return DatabaseOperationHelper.processSetDatabaseOwners(request);
    }

    private OperationResponse processDropDatabaseOperation(DropDatabaseRequest dropDatabaseRequest) {
        return DatabaseOperationHelper.processDropDatabaseOperation(dropDatabaseRequest);
    }

    private OperationResponse processListDatabasesOperation() {
        return DatabaseOperationHelper.processListDatabasesOperation();
    }

    private OperationResponse processCreateCollectionOperation(CreateCollectionRequest createCollectionRequest) {
        return CollectionOperationHelper.processCreateCollectionOperation(createCollectionRequest);
    }

    private OperationResponse processDropCollectionOperation(DropCollectionRequest dropCollectionRequest) {
        return CollectionOperationHelper.processDropCollectionOperation(dropCollectionRequest);
    }

    private OperationResponse processListCollectionsOperation(ListCollectionsRequest request) {
        return CollectionOperationHelper.processListCollectionsOperation(request);
    }

    private OperationResponse processListenOperation(ListenRequest listenRequest, UUID clientId) {
        return ListenOperationHelper.processListenOperation(listenRequest, clientId);
    }

    private OperationResponse processStopListenOperation(StopListenRequest request) {
        return ListenOperationHelper.processStopListenOperation(request);
    }

    private OperationResponse processRunScriptOperation(RunScriptRequest request, String actingUser, UUID clientId) {
        return ScriptOperationHelper.execute(request, actingUser, clientId);
    }

    private OperationResponse processSaveProcedure(SaveProcedureRequest request, String actingUser) {
        try {
            return ProcedureOperationHelper.executeSave(request, actingUser);
        } catch (Exception e) {
            return new OperationResponse(OperationType.SAVE_PROCEDURE, ErrorCode.ERROR_SAVING_PROCEDURE);
        }
    }

    private OperationResponse processDeleteProcedure(DeleteProcedureRequest request) {
        try {
            return ProcedureOperationHelper.executeDelete(request);
        } catch (Exception e) {
            return new OperationResponse(OperationType.DELETE_PROCEDURE, ErrorCode.ERROR_DELETING_PROCEDURE);
        }
    }

    private OperationResponse processListProcedures(ListProceduresRequest request) {
        return ProcedureOperationHelper.executeList(request);
    }

    private OperationResponse processCallProcedure(CallProcedureRequest request, String actingUser, UUID clientId) {
        return ProcedureCallHelper.execute(request, actingUser, clientId);
    }

    private OperationResponse processSaveTrigger(SaveTriggerRequest request, String actingUser) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        try {
            // The collection write lock serializes the trigger-file rewrite against a concurrent save to
            // the same collection, mirroring processSaveSchema.
            locks.lock(dbName, collName);
            return TriggerOperationHelper.executeSave(request, actingUser);
        } catch (Exception e) {
            return new OperationResponse(OperationType.SAVE_TRIGGER, ErrorCode.ERROR_SAVING_TRIGGER);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private OperationResponse processDeleteTrigger(DeleteTriggerRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        try {
            locks.lock(dbName, collName);
            return TriggerOperationHelper.executeDelete(request);
        } catch (Exception e) {
            return new OperationResponse(OperationType.DELETE_TRIGGER, ErrorCode.ERROR_DELETING_TRIGGER);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private OperationResponse processListTriggers(ListTriggersRequest request) {
        return TriggerOperationHelper.executeList(request);
    }

    // No lock and no write: the hook is run against the caller's own document, so nothing on disk is
    // touched and nothing needs serializing against a concurrent save.
    private OperationResponse processTestTrigger(TestTriggerRequest request, String actingUser) {
        return TriggerOperationHelper.executeTest(request, actingUser);
    }

    private OperationResponse processSaveSchedule(SaveScheduleRequest request, String actingUser) {
        try {
            return ScheduleOperationHelper.executeSave(request, actingUser);
        } catch (Exception e) {
            return new OperationResponse(OperationType.SAVE_SCHEDULE, ErrorCode.ERROR_SAVING_SCHEDULE);
        }
    }

    private OperationResponse processDeleteSchedule(DeleteScheduleRequest request) {
        try {
            return ScheduleOperationHelper.executeDelete(request);
        } catch (Exception e) {
            return new OperationResponse(OperationType.DELETE_SCHEDULE, ErrorCode.ERROR_DELETING_SCHEDULE);
        }
    }

    private OperationResponse processListSchedules(ListSchedulesRequest request) {
        return ScheduleOperationHelper.executeList(request);
    }

    private OperationResponse processBulkSaveOperation(BulkSaveRequest bulkSaveRequest, Transaction activeTransaction,
            String actingUser) {
        final var dbName = bulkSaveRequest.getDatabaseName();
        final var collName = bulkSaveRequest.getCollectionName();
        if (activeTransaction != null) {
            return TransactionOperationHelper.bufferBulkSave(bulkSaveRequest, activeTransaction);
        }
        final var guardError = ClusterWriteHelper.guard(OperationType.BULK_SAVE, dbName, collName);
        if (guardError != null) {
            return guardError;
        }
        try {
            locks.lock(dbName, collName);
            final var hookError = BeforeHookHelper.beforeBulkSave(bulkSaveRequest, actingUser);
            if (hookError != null) {
                return hookError;
            }
            final var response = ClusterWriteHelper.afterBulkSave(dbName, collName,
                    SaveOperationHelper.executeBulkSave(bulkSaveRequest));
            TriggerHelper.afterBulkSave(dbName, collName, response, actingUser, bulkSaveRequest.getTriggerDepth());
            return response;
        } catch (Exception exception) {
            return new OperationResponse(OperationType.BULK_SAVE, ErrorCode.ERROR_BULK_SAVING);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private OperationResponse processSaveOperation(SaveRequest saveRequest, Transaction activeTransaction,
            String actingUser) {
        final var dbName = saveRequest.getDatabaseName();
        final var collName = saveRequest.getCollectionName();
        if (activeTransaction != null) {
            return TransactionOperationHelper.bufferSave(saveRequest, activeTransaction);
        }
        final var guardError = ClusterWriteHelper.guard(OperationType.SAVE, dbName, collName);
        if (guardError != null) {
            return guardError;
        }
        final var isInsert = saveRequest.get_id() == null || saveRequest.get_id().isBlank();
        try {
            locks.lock(dbName, collName);
            final var hookError = BeforeHookHelper.beforeSave(saveRequest,
                    isInsert ? EventType.CREATED : EventType.UPDATED, actingUser);
            if (hookError != null) {
                return hookError;
            }
            final var response = ClusterWriteHelper.afterSave(dbName, collName,
                    SaveOperationHelper.executeSave(saveRequest));
            if (response instanceof SaveResponse saveResponse) {
                TriggerHelper.afterWriteIds(dbName, collName, isInsert ? EventType.CREATED : EventType.UPDATED,
                        List.of(saveResponse.get_id()), actingUser, saveRequest.getTriggerDepth());
            }
            return response;
        } catch (Exception exception) {
            return new OperationResponse(OperationType.SAVE, ErrorCode.ERROR_SAVING);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private OperationResponse processDeleteOperation(DeleteRequest deleteRequest, Transaction activeTransaction,
            String actingUser) {
        final var dbName = deleteRequest.getDatabaseName();
        final var collName = deleteRequest.getCollectionName();
        if (activeTransaction != null) {
            return TransactionOperationHelper.bufferDelete(deleteRequest, activeTransaction);
        }
        final var guardError = ClusterWriteHelper.guard(OperationType.DELETE, dbName, collName);
        if (guardError != null) {
            return guardError;
        }
        try {
            locks.lock(dbName, collName);
            // Read before the delete: afterWrite needs the document that is about to disappear, and this
            // is a no-op unless a DELETED trigger actually exists on the collection.
            final var deleted = TriggerHelper.captureForDelete(dbName, collName, deleteRequest.get_id(),
                    deleteRequest.getTriggerDepth());
            final var hookError = BeforeHookHelper.beforeDelete(deleteRequest, actingUser);
            if (hookError != null) {
                return hookError;
            }
            final var response = ClusterWriteHelper.afterDelete(dbName, collName, deleteRequest.get_id(),
                    DeleteOperationHelper.executeDelete(deleteRequest));
            if (response instanceof DeleteResponse) {
                TriggerHelper.afterWrite(dbName, collName, EventType.DELETED, deleted, actingUser,
                        deleteRequest.getTriggerDepth());
            }
            return response;
        } catch (Exception exception) {
            return new OperationResponse(OperationType.DELETE, ErrorCode.ERROR_DELETING);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private OperationResponse processListUsers(ListUsersRequest request) {
        List<String> readLocks = List.of();
        try {
            readLocks = locks.acquireReadLocks(request.isDirtyRead(),
                    List.of(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME)));
            final var userStream = cache.getAllAdminUserEntries().stream()
                    .map(user -> user.toResponseJson(cache.getAllAdminDbEntries().stream()
                            .filter(db -> db.isOwner(user.get_id())).map(DbEntry::get_id).toList()));
            final var results = AggregationOperationHelper.processStepsOnStream(request.getAggregationSteps(),
                    userStream);
            return results.isEmpty()
                    ? new OperationResponse(OperationType.LIST_USERS, ErrorCode.NO_USERS_FOUND)
                    : new ListUsersResponse("Ok", results);
        } catch (Exception e) {
            return new OperationResponse(OperationType.LIST_USERS, ErrorCode.ERROR_LISTING_USERS);
        } finally {
            locks.releaseReadLocks(readLocks);
        }
    }

    private OperationResponse processSaveSchema(SaveSchemaRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        try {
            // Hold the collection write lock so the schema file write, cache update and any concurrent
            // save are serialized (mirrors processCreateIndex); the work itself lives in the helper.
            locks.lock(dbName, collName);
            return SchemaOperationHelper.executeSaveSchema(request);
        } catch (Exception e) {
            return new OperationResponse(OperationType.SAVE_SCHEMA, ErrorCode.ERROR_SAVING_SCHEMA);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private OperationResponse processDeleteSchema(DeleteSchemaRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        try {
            locks.lock(dbName, collName);
            return SchemaOperationHelper.executeDeleteSchema(request);
        } catch (Exception e) {
            return new OperationResponse(OperationType.DELETE_SCHEMA, ErrorCode.ERROR_DELETING_SCHEMA);
        } finally {
            locks.release(dbName, collName);
        }
    }

}
