package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.DbEntry;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ResultHasher;
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
import org.techhouse.ops.req.ListTriggersRequest;
import org.techhouse.ops.req.ListUsersRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.ResolveTransactionRequest;
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
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.CancelScriptResponse;
import org.techhouse.ops.resp.CloseConnectionResponse;
import org.techhouse.ops.resp.CreateCollectionResponse;
import org.techhouse.ops.resp.CreateDatabaseResponse;
import org.techhouse.ops.resp.CreateIndexResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.DropCollectionResponse;
import org.techhouse.ops.resp.DropDatabaseResponse;
import org.techhouse.ops.resp.DropIndexResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.ListCollectionsResponse;
import org.techhouse.ops.resp.ListDatabasesResponse;
import org.techhouse.ops.resp.ListScriptsResponse;
import org.techhouse.ops.resp.ListTransactionsResponse;
import org.techhouse.ops.resp.ListUsersResponse;
import org.techhouse.ops.resp.ListenResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ReindexResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.ops.resp.SetDatabaseOwnersResponse;
import org.techhouse.ops.resp.StopListenResponse;
import org.techhouse.simplejs.exceptions.ScriptCallableException;

public class OperationProcessor {
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);
    private final org.techhouse.cluster.Tx2pcCoordinator tx2pcCoordinator = IocContainer
            .get(org.techhouse.cluster.Tx2pcCoordinator.class);
    private final org.techhouse.cluster.Tx2pcDirectory tx2pcDirectory = IocContainer
            .get(org.techhouse.cluster.Tx2pcDirectory.class);
    private final org.techhouse.cluster.ScriptRunDirectory scriptRunDirectory = IocContainer
            .get(org.techhouse.cluster.ScriptRunDirectory.class);

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
        };
        return ClusterAdminHelper.afterAdminOp(operationRequest, actingUser, response);
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

    private OperationResponse processFindByIdOperation(FindByIdRequest findbyIdRequest, Transaction activeTransaction) {
        final var dbName = findbyIdRequest.getDatabaseName();
        final var collName = findbyIdRequest.getCollectionName();
        final var id = findbyIdRequest.get_id();
        // Read-your-writes: if the caller's open transaction has buffered a write for this id, serve it
        // (a buffered save returns the buffered document; a buffered delete reads as not-found).
        if (activeTransaction != null) {
            final var overlay = activeTransaction.overlayFor(Cache.getCollectionIdentifier(dbName, collName));
            if (overlay != null && overlay.containsKey(id)) {
                final var buffered = overlay.get(id);
                if (Transaction.isTombstone(buffered)) {
                    return new OperationResponse(OperationType.FIND_BY_ID, ErrorCode.ENTRY_NOT_FOUND);
                }
                return new FindByIdResponse("Ok", buffered);
            }
        }
        List<String> readLocks = List.of();
        try {
            readLocks = locks.acquireReadLocks(findbyIdRequest.isDirtyRead(),
                    List.of(Cache.getCollectionIdentifier(dbName, collName)));
            final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            final var foundIndexEntry = Collections.binarySearch(primaryKeyIndex, id);
            if (foundIndexEntry >= 0) {
                final var primaryKeyIndexEntry = primaryKeyIndex.get(foundIndexEntry);
                final var entry = cache.getById(dbName, collName, primaryKeyIndexEntry);
                CollectionAccessHelper.recordPkIndexAccess(dbName, collName);
                CollectionAccessHelper.recordCollectionAccess(dbName, collName);
                return new FindByIdResponse("Ok", entry.getData());
            } else {
                return new OperationResponse(OperationType.FIND_BY_ID, ErrorCode.ENTRY_NOT_FOUND);
            }
        } catch (Exception exception) {
            return new OperationResponse(OperationType.FIND_BY_ID, ErrorCode.ERROR_RETRIEVING);
        } finally {
            locks.releaseReadLocks(readLocks);
        }
    }

    private OperationResponse processAggregateOperation(AggregateRequest aggregateRequest,
            Transaction activeTransaction) {
        List<String> readLocks = List.of();
        final var analyzeContext = aggregateRequest.isAnalyze() ? new AnalyzeContext() : null;
        if (analyzeContext != null) {
            AnalyzeContext.set(analyzeContext);
        }
        final var dbName = aggregateRequest.getDatabaseName();
        final var collName = aggregateRequest.getCollectionName();
        final var overlay = activeTransaction != null
                ? activeTransaction.overlayFor(Cache.getCollectionIdentifier(dbName, collName))
                : null;
        try {
            readLocks = locks.acquireReadLocks(aggregateRequest.isDirtyRead(),
                    AggregationOperationHelper.aggregateLockSet(aggregateRequest));
            if (analyzeContext != null) {
                readLocks.forEach(analyzeContext::addLock);
            }
            final List<org.techhouse.ejson.elements.JsonObject> results;
            if (overlay != null && !overlay.isEmpty()) {
                // Read-your-writes: run the pipeline over the committed documents with the transaction's
                // buffered mutations applied at the source. Passing a prepared source stream also disables
                // the index-backed source fast-paths, so the overlaid documents are honoured exactly.
                final var committed = cache.initializeStreamIfNecessary(null, dbName, collName);
                final var source = TransactionOperationHelper.applyOverlayToStream(activeTransaction,
                        Cache.getCollectionIdentifier(dbName, collName), committed);
                results = AggregationOperationHelper.processAggregation(aggregateRequest, source);
            } else {
                results = AggregationOperationHelper.processAggregation(aggregateRequest);
            }
            CollectionAccessHelper.recordCollectionAccess(aggregateRequest.getDatabaseName(),
                    aggregateRequest.getCollectionName());
            if (analyzeContext != null) {
                // In analyze mode the diagnostic is always returned (even with no results), so the empty
                // result returns an AggregateAnalyzeResponse instead of the NO_RESULTS error response.
                return new AggregateAnalyzeResponse("Ok", results,
                        AnalyzeHelper.build(aggregateRequest, analyzeContext));
            }
            return results.isEmpty()
                    ? new OperationResponse(OperationType.AGGREGATE, ErrorCode.NO_RESULTS)
                    : new AggregateResponse("Ok", results);
        } catch (ScriptCallableException scriptFailure) {
            return new OperationResponse(OperationType.AGGREGATE, scriptFailure.getMessage(),
                    ScriptOperationHelper.errorCodeFor(scriptFailure.getErrorName()));
        } catch (Exception e) {
            return new OperationResponse(OperationType.AGGREGATE, ErrorCode.ERROR_AGGREGATING);
        } finally {
            locks.releaseReadLocks(readLocks);
            if (analyzeContext != null) {
                AnalyzeContext.clear();
            }
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

    private OperationResponse processCreateDatabaseOperation(CreateDatabaseRequest createDatabaseRequest,
            UUID clientId) {
        try {
            final var dbName = createDatabaseRequest.getDatabaseName();
            // Guard against re-creating an existing database: createDatabaseFolder returns true for an
            // already-present folder, so without this check a duplicate CREATE_DATABASE would overwrite
            // the existing admin entry (wiping its collection list and owners) and wrongly report success.
            if (cache.getAdminDbEntry(dbName) != null) {
                return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.DATABASE_ALREADY_EXISTS);
            }
            final var result = fs.createDatabaseFolder(dbName);
            if (result) {
                final var username = clientTracker.getAuthenticatedUsername(clientId);
                final var owners = username != null ? List.of(username) : List.<String>of();
                final var newEntry = new AdminDbEntry(dbName, new java.util.ArrayList<>(),
                        new java.util.ArrayList<>(owners));
                AdminOperationHelper.saveDatabaseEntry(newEntry);
                return new CreateDatabaseResponse("Database created successfully");
            }
            return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.DATABASE_ALREADY_EXISTS);
        } catch (Exception exception) {
            return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.ERROR_CREATING_DATABASE);
        }
    }

    private OperationResponse processSetDatabaseOwners(SetDatabaseOwnersRequest request) {
        try {
            final var dbName = request.getDatabaseName();
            if (cache.getAdminDbEntry(dbName) == null) {
                return new OperationResponse(OperationType.SET_DATABASE_OWNERS, "Database '" + dbName + "' not found",
                        ErrorCode.DATABASE_NOT_FOUND);
            }
            AdminOperationHelper.updateDatabaseOwners(dbName, request.getOwners());
            return new SetDatabaseOwnersResponse("Database owners updated successfully");
        } catch (Exception e) {
            return new OperationResponse(OperationType.SET_DATABASE_OWNERS, ErrorCode.ERROR_UPDATING_DATABASE_OWNERS);
        }
    }

    private OperationResponse processDropDatabaseOperation(DropDatabaseRequest dropDatabaseRequest) {
        final var dbName = dropDatabaseRequest.getDatabaseName();
        // Lock every collection of the database (in a stable order to avoid deadlock with other
        // multi-collection acquisitions) so a concurrent save/delete/read or a background index update
        // on any of them cannot race the file deletion and cache eviction below.
        final var dbEntry = cache.getAdminDbEntry(dbName);
        final var collNames = dbEntry != null ? new ArrayList<>(dbEntry.getCollections()) : new ArrayList<String>();
        Collections.sort(collNames);
        final var lockedColls = new ArrayList<String>();
        try {
            for (final var collName : collNames) {
                locks.lock(dbName, collName);
                lockedColls.add(collName);
            }
            final var result = fs.deleteDatabase(dbName);
            if (result) {
                cache.evictDatabase(dbName);
                for (final var collName : lockedColls) {
                    locks.removeLock(dbName, collName);
                }
                // Remove the database's admin metadata synchronously (mirroring synchronous creation and
                // collection drop). Doing this in the background previously left the admin entry briefly
                // present after the drop returned OK, so an immediate CREATE_DATABASE of the same name hit
                // the duplicate guard and wrongly returned DATABASE_ALREADY_EXISTS (or was unregistered
                // when the queued delete event later ran).
                AdminOperationHelper.deleteDatabaseEntry(dbName);
                // The procedure files went with the folder; drop their compiled programs too, since a
                // re-created database would restart its procedure versions at 1.
                compiledProcedures.invalidateDatabase(dbName);
                // The schedule files went with the folder too; drop the registry entries now rather than
                // letting the periodic refresh notice, so nothing keeps firing against a gone database.
                scheduleRegistry.removeDatabase(dbName);
                listenManager.unregisterAllForDatabase(dbName);
                return new DropDatabaseResponse("Database dropped successfully");
            }
            return new OperationResponse(OperationType.DROP_DATABASE, ErrorCode.ERROR_DROPPING_DATABASE);
        } catch (Exception exception) {
            return new OperationResponse(OperationType.DROP_DATABASE, ErrorCode.ERROR_DROPPING_DATABASE);
        } finally {
            for (final var collName : lockedColls) {
                locks.release(dbName, collName);
            }
        }
    }

    private OperationResponse processListDatabasesOperation() {
        try {
            final var names = cache.getUserDatabaseNames();
            return new ListDatabasesResponse("Ok", names);
        } catch (Exception e) {
            return new OperationResponse(OperationType.LIST_DATABASES, ErrorCode.ERROR_LISTING_DATABASES);
        }
    }

    private OperationResponse processCreateCollectionOperation(CreateCollectionRequest createCollectionRequest) {
        try {
            final var dbName = createCollectionRequest.getDatabaseName();
            final var collName = createCollectionRequest.getCollectionName();
            final var result = fs.createCollectionFile(dbName, collName);
            if (result) {
                // Register the collection's admin metadata (page collections + admin entry with its PK
                // index entry) synchronously, so a subsequent CREATE_INDEX/SAVE observes it immediately.
                // Doing it here rather than in a background task closes a race where the registration
                // lagged, letting CREATE_INDEX run first, find no admin PK entry
                // (getPkIndexAdminCollEntry == null) and silently skip registering the index while still
                // returning OK, leaving the index built but unregistered. Collection creation and
                // deletion are both fully synchronous.
                if (AdminOperationHelper.getCollectionEntry(dbName, collName) == null) {
                    AdminOperationHelper.createPageCollections(dbName, collName);
                    AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(dbName, collName));
                }
                return new CreateCollectionResponse("Collection created successfully");
            }
            return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.ERROR_CREATING_COLLECTION);
        } catch (Exception e) {
            return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.ERROR_CREATING_COLLECTION);
        }
    }

    private OperationResponse processDropCollectionOperation(DropCollectionRequest dropCollectionRequest) {
        final var dbName = dropCollectionRequest.getDatabaseName();
        final var collName = dropCollectionRequest.getCollectionName();
        boolean dropSucceeded = false;
        try {
            locks.lock(dbName, collName);
            final var result = fs.deleteCollectionFiles(dbName, collName);
            if (result) {
                cache.evictCollection(dbName, collName);
                // Remove the collection's admin metadata synchronously (mirroring synchronous creation).
                // Doing this in the background previously left the admin entry briefly present after the
                // drop returned OK, so an immediate CREATE_COLLECTION of the same name saw the stale entry,
                // skipped registration, and was then unregistered when the queued delete event ran.
                AdminOperationHelper.deleteCollectionEntry(dbName, collName);
                AdminOperationHelper.deletePageCollections(dbName, collName);
                listenManager.unregisterAllForCollection(dbName, collName);
                dropSucceeded = true;
                return new DropCollectionResponse("Collection dropped successfully");
            }
            return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
        } catch (Exception e) {
            return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
        } finally {
            locks.release(dbName, collName);
            if (dropSucceeded) {
                locks.removeLock(dbName, collName);
            }
        }
    }

    private OperationResponse processListCollectionsOperation(ListCollectionsRequest request) {
        List<String> readLocks = List.of();
        try {
            final var dbName = request.getDatabaseName();
            if (dbName == null || dbName.isBlank()) {
                return new OperationResponse(OperationType.LIST_COLLECTIONS, "Database name is required",
                        ErrorCode.VALIDATION_ERROR);
            }
            if (Globals.ADMIN_DB_NAME.equals(dbName)) {
                return new ListCollectionsResponse("Ok", List.of());
            }
            readLocks = locks.acquireReadLocks(request.isDirtyRead(), List.of(
                    Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME)));
            if (cache.getAdminDbEntry(dbName) == null) {
                return new OperationResponse(OperationType.LIST_COLLECTIONS, "Database " + dbName + " not found",
                        ErrorCode.DATABASE_NOT_FOUND);
            }
            final var names = cache.getCollectionNamesForDatabase(dbName);
            return new ListCollectionsResponse("Ok", names);
        } catch (Exception e) {
            return new OperationResponse(OperationType.LIST_COLLECTIONS, ErrorCode.ERROR_LISTING_COLLECTIONS);
        } finally {
            locks.releaseReadLocks(readLocks);
        }
    }

    private OperationResponse processCreateIndex(CreateIndexRequest createIndexRequest) {
        final var dbName = createIndexRequest.getDatabaseName();
        final var collName = createIndexRequest.getCollectionName();
        final var fieldName = createIndexRequest.getFieldName();
        try {
            // Hold the collection write lock so no save can commit a document between the index build
            // and its registration. Building the index files and registering the field as a known
            // index happen atomically here (synchronously), so any concurrent save is serialized: it
            // either commits before (and is captured by the whole-collection read) or after (and its
            // background index update sees the field already registered and indexes it).
            locks.lock(dbName, collName);
            IndexHelper.createIndex(dbName, collName, fieldName);
            AdminOperationHelper.saveNewIndex(dbName, collName, fieldName);
            return new CreateIndexResponse("Created index for field: " + fieldName);
        } catch (Exception e) {
            return new OperationResponse(OperationType.CREATE_INDEX, ErrorCode.ERROR_CREATING_INDEX);
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

    private OperationResponse processDropIndex(DropIndexRequest dropIndexRequest) {
        final var dbName = dropIndexRequest.getDatabaseName();
        final var collName = dropIndexRequest.getCollectionName();
        final var fieldName = dropIndexRequest.getFieldName();
        try {
            // Hold the collection write lock so the index files are deleted and the field is
            // unregistered atomically with respect to saves and the background indexer. Unregister
            // first so no read or background index update can use the field after this returns.
            locks.lock(dbName, collName);
            final var result = IndexHelper.dropIndex(dbName, collName, fieldName);
            if (result) {
                AdminOperationHelper.deleteIndex(dbName, collName, fieldName);
                return new DropIndexResponse("Successfully dropped index: " + fieldName);
            } else {
                return new OperationResponse(OperationType.DROP_INDEX, ErrorCode.INDEX_NOT_FOUND, fieldName);
            }
        } catch (Exception e) {
            return new OperationResponse(OperationType.DROP_INDEX, ErrorCode.ERROR_DROPPING_INDEX);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private OperationResponse processReindex(ReindexRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        try {
            // Hold the collection write lock for the entire rebuild so no concurrent save can commit
            // between field rebuilds. Admin metadata is not changed — the indexes already exist.
            // If createIndex throws mid-loop (e.g. disk full), the catch returns ERROR; the operator
            // can retry once the underlying issue is resolved.
            locks.lock(dbName, collName);
            final var registeredIndexes = cache.getIndexesForCollection(dbName, collName);
            final List<String> targets;
            if (request.getFieldNames().isEmpty()) {
                targets = new ArrayList<>(registeredIndexes);
            } else {
                for (var fieldName : request.getFieldNames()) {
                    if (!registeredIndexes.contains(fieldName)) {
                        return new OperationResponse(OperationType.REINDEX, ErrorCode.INDEX_NOT_FOUND, fieldName);
                    }
                }
                targets = request.getFieldNames();
            }
            if (targets.isEmpty()) {
                return new ReindexResponse("No indexes to rebuild", List.of());
            }
            for (var fieldName : targets) {
                IndexHelper.dropIndex(dbName, collName, fieldName);
                IndexHelper.createIndex(dbName, collName, fieldName);
            }
            return new ReindexResponse("Rebuilt " + targets.size() + " index(es)", targets);
        } catch (Exception e) {
            return new OperationResponse(OperationType.REINDEX, ErrorCode.ERROR_REINDEXING);
        } finally {
            locks.release(dbName, collName);
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

    private OperationResponse processListenOperation(ListenRequest listenRequest, UUID clientId) {
        List<String> readLocks = List.of();
        try {
            final var dbName = listenRequest.getDatabaseName();
            final var collName = listenRequest.getCollectionName();
            // Build an AggregateRequest so we can use the existing aggregation infrastructure.
            final var aggReq = new AggregateRequest(dbName, collName);
            aggReq.setAggregationSteps(listenRequest.getAggregationSteps());
            readLocks = locks.acquireReadLocks(false, AggregationOperationHelper.aggregateLockSet(aggReq));
            final var results = AggregationOperationHelper.processAggregation(aggReq);
            final var initialHash = ResultHasher.hash(results);
            // The re-run request uses dirty reads: timeliness matters more than strict consistency
            // for push notifications, and per-file locks still ensure valid data.
            final var dirtyReq = new AggregateRequest(dbName, collName);
            dirtyReq.setAggregationSteps(listenRequest.getAggregationSteps());
            dirtyReq.setDirtyRead(true);
            final var listenId = listenManager.register(clientId, dirtyReq, initialHash);
            CollectionAccessHelper.recordCollectionAccess(dbName, collName);
            return new ListenResponse(listenId.toString(), results, initialHash, false);
        } catch (Exception e) {
            return new OperationResponse(OperationType.LISTEN, ErrorCode.ERROR_LISTEN);
        } finally {
            locks.releaseReadLocks(readLocks);
        }
    }

    private OperationResponse processResolveTransaction(ResolveTransactionRequest request) {
        final var commit = ResolveTransactionRequest.DECISION_COMMIT.equals(request.getDecision());
        return tx2pcCoordinator.forceResolve(request.getDtxId(), commit);
    }

    private OperationResponse processListScripts() {
        try {
            return new ListScriptsResponse("Ok", scriptRunDirectory.listClusterWide());
        } catch (Exception e) {
            return new OperationResponse(OperationType.LIST_SCRIPTS, ErrorCode.SCRIPT_FAILED);
        }
    }

    private OperationResponse processCancelScript(CancelScriptRequest request) {
        try {
            return new CancelScriptResponse("Ok", scriptRunDirectory.cancelClusterWide(request.getRunId()));
        } catch (Exception e) {
            return new OperationResponse(OperationType.CANCEL_SCRIPT, ErrorCode.SCRIPT_FAILED);
        }
    }

    private OperationResponse processListTransactions() {
        try {
            return new ListTransactionsResponse("Ok", tx2pcDirectory.listInDoubtClusterWide());
        } catch (Exception e) {
            return new OperationResponse(OperationType.LIST_TRANSACTIONS, ErrorCode.ERROR_TRANSACTION);
        }
    }

    private OperationResponse processStopListenOperation(StopListenRequest request) {
        try {
            final var listenId = java.util.UUID.fromString(request.getListenId());
            final var unregistered = listenManager.unregister(listenId);
            if (!unregistered) {
                return new OperationResponse(OperationType.STOP_LISTEN, ErrorCode.LISTEN_NOT_FOUND);
            }
            return new StopListenResponse();
        } catch (Exception e) {
            return new OperationResponse(OperationType.STOP_LISTEN, ErrorCode.ERROR_LISTEN);
        }
    }
}
