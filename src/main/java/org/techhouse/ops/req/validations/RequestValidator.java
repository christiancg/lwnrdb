package org.techhouse.ops.req.validations;

import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.CancelScriptRequest;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
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

public class RequestValidator {

    public static ValidationResult validate(OperationRequest request) {
        return switch (request.getType()) {
            case SAVE -> DataRequestValidator.validateSave((SaveRequest) request);
            case BULK_SAVE -> DataRequestValidator.validateBulkSave((BulkSaveRequest) request);
            case FIND_BY_ID -> DataRequestValidator.validateFindById((FindByIdRequest) request);
            case DELETE -> DataRequestValidator.validateDelete((DeleteRequest) request);
            case AGGREGATE -> DataRequestValidator.validateAggregate((AggregateRequest) request);
            case CREATE_DATABASE, DROP_DATABASE -> NameValidations.validateDbOnly(request, true);
            case LIST_DATABASES, CLOSE_CONNECTION, GET_DATABASE_STATS -> ValidationResult.ok();
            case CREATE_COLLECTION, DROP_COLLECTION, DELETE_SCHEMA ->
                NameValidations.validateDbAndColl(request, true, true);
            case LIST_COLLECTIONS -> NameValidations.validateDbOnly(request, false);
            case CREATE_INDEX -> DataRequestValidator.validateCreateIndex((CreateIndexRequest) request);
            case DROP_INDEX -> DataRequestValidator.validateDropIndex((DropIndexRequest) request);
            case REINDEX -> DataRequestValidator.validateReindex((ReindexRequest) request);
            case SAVE_SCHEMA -> DataRequestValidator.validateSaveSchema((SaveSchemaRequest) request);
            case AUTHENTICATE -> UserRequestValidator.validateAuthenticate((AuthenticateRequest) request);
            case CREATE_USER -> UserRequestValidator.validateCreateUser((CreateUserRequest) request);
            case DELETE_USER -> UserRequestValidator.validateDeleteUser((DeleteUserRequest) request);
            case CHANGE_PERMISSIONS ->
                UserRequestValidator.validateChangePermissions((ChangePermissionsRequest) request);
            case SET_DATABASE_OWNERS ->
                UserRequestValidator.validateSetDatabaseOwners((SetDatabaseOwnersRequest) request);
            case LIST_USERS -> UserRequestValidator.validateListUsers((ListUsersRequest) request);
            case SET_PASSWORD -> UserRequestValidator.validateSetPassword((SetPasswordRequest) request);
            case LISTEN -> ControlRequestValidator.validateListen((ListenRequest) request);
            case STOP_LISTEN -> ControlRequestValidator.validateStopListen((StopListenRequest) request);
            // Transaction control operations carry no db/coll/payload; authentication is still enforced
            // in MessageProcessor.
            case START_TRANSACTION, COMMIT_TRANSACTION, ROLLBACK_TRANSACTION, LIST_TRANSACTIONS, LIST_SCRIPTS ->
                ValidationResult.ok();
            case RESOLVE_TRANSACTION ->
                ControlRequestValidator.validateResolveTransaction((ResolveTransactionRequest) request);
            case RUN_SCRIPT -> ScriptRequestValidator.validateRunScript((RunScriptRequest) request);
            case SAVE_PROCEDURE -> ScriptRequestValidator.validateSaveProcedure((SaveProcedureRequest) request);
            case DELETE_PROCEDURE -> ScriptRequestValidator.validateDeleteProcedure((DeleteProcedureRequest) request);
            case LIST_PROCEDURES -> NameValidations.validateDbNameOnly(request.getDatabaseName());
            case CALL_PROCEDURE -> ScriptRequestValidator.validateCallProcedure((CallProcedureRequest) request);
            case SAVE_TRIGGER -> ScriptRequestValidator.validateSaveTrigger((SaveTriggerRequest) request);
            case DELETE_TRIGGER -> ScriptRequestValidator.validateDeleteTrigger((DeleteTriggerRequest) request);
            case LIST_TRIGGERS -> ScriptRequestValidator.validateListTriggers((ListTriggersRequest) request);
            case TEST_TRIGGER -> ScriptRequestValidator.validateTestTrigger((TestTriggerRequest) request);
            case SAVE_SCHEDULE -> ScriptRequestValidator.validateSaveSchedule((SaveScheduleRequest) request);
            case DELETE_SCHEDULE -> ScriptRequestValidator.validateDeleteSchedule((DeleteScheduleRequest) request);
            case LIST_SCHEDULES -> NameValidations.validateDbNameOnly(request.getDatabaseName());
            case CANCEL_SCRIPT -> ControlRequestValidator.validateCancelScript((CancelScriptRequest) request);
            case LIST_TRIGGER_RUNS -> ControlRequestValidator.validateListTriggerRuns((ListTriggerRunsRequest) request);
            case RESOLVE_TRIGGER_RUN ->
                ControlRequestValidator.validateResolveTriggerRun((ResolveTriggerRunRequest) request);
        };
    }
}
