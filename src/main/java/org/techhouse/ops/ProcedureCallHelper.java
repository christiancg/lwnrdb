package org.techhouse.ops;

import java.util.UUID;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.resp.CallProcedureResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.simplejs.host.ScriptResult;

public final class ProcedureCallHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
    private static final Configuration configuration = Configuration.getInstance();

    private ProcedureCallHelper() {
    }

    public static OperationResponse execute(CallProcedureRequest request, String username, UUID clientId) {
        if (!configuration.isScriptsEnabled()) {
            return new OperationResponse(OperationType.CALL_PROCEDURE, ErrorCode.SCRIPTS_DISABLED);
        }
        final var dbName = request.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) == null) {
            return new OperationResponse(OperationType.CALL_PROCEDURE, "Database '" + dbName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        final var name = request.getProcedureName();
        final var definition = cache.getProcedure(dbName, name);
        if (definition == null || !definition.isEnabled()) {
            return new OperationResponse(OperationType.CALL_PROCEDURE,
                    "Procedure '" + name + "' not found in database '" + dbName + "'", ErrorCode.PROCEDURE_NOT_FOUND);
        }
        final var permit = admission.acquire(username, dbName);
        if (permit == null) {
            return ScriptOperationHelper.concurrencyRefusal(OperationType.CALL_PROCEDURE);
        }
        try (permit) {
            final var compiled = compiledProcedures.get(dbName, name, definition.getVersion(), definition.getSource());
            final var outcome = ScriptOperationHelper.runProcedure(compiled, request.getArgs(), dbName, username,
                    clientId, "CALL_PROCEDURE user=" + username + " database=" + dbName + " procedure=" + name, name);
            return toResponse(outcome.result(), outcome.runId());
        }
    }

    private static OperationResponse toResponse(ScriptResult result, String runId) {
        final var response = result.isError()
                ? new CallProcedureResponse(result.getErrorName() + ": " + result.getErrorMessage(),
                        ScriptOperationHelper.errorCodeFor(result.getErrorName()), result.getLogs(),
                        result.isLogsTruncated(), runId, result.getErrorStack())
                : new CallProcedureResponse("Procedure executed successfully", result.getValue(), result.getLogs(),
                        result.isLogsTruncated(), runId);
        response.setMetrics(result.getMetrics().toJson());
        return response;
    }
}
