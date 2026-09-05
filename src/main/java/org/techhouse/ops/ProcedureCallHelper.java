package org.techhouse.ops;

import java.util.UUID;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.resp.CallProcedureResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.simplejs.host.ScriptResult;

/**
 * Calls a stored procedure. The sandbox, the enforcing database access and the outcome mapping are the ones
 * RUN_SCRIPT uses; only the source differs, coming from the stored definition rather than the request. The
 * procedure runs with the <em>caller's</em> authority, so the grant to call one never widens what it may do.
 */
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
        // A disabled procedure answers not-found rather than succeeding silently: the caller asked for it
        // to run, and nothing ran.
        if (definition == null || !definition.isEnabled()) {
            return new OperationResponse(OperationType.CALL_PROCEDURE,
                    "Procedure '" + name + "' not found in database '" + dbName + "'", ErrorCode.PROCEDURE_NOT_FOUND);
        }
        // The gate is here rather than in runCompiled, which triggers and schedules share: they are bounded
        // by their own worker pools and must never be refused a run.
        if (!admission.tryAcquire()) {
            return new OperationResponse(OperationType.CALL_PROCEDURE, ErrorCode.SCRIPT_CONCURRENCY_LIMIT);
        }
        try {
            final var compiled = compiledProcedures.get(dbName, name, definition.getVersion(), definition.getSource());
            final var outcome = ScriptOperationHelper.runCompiled(compiled, request.getArgs(), dbName, username,
                    clientId, "CALL_PROCEDURE user=" + username + " database=" + dbName + " procedure=" + name,
                    ScriptRunKind.CALL_PROCEDURE, name);
            return toResponse(outcome.result(), outcome.runId());
        } finally {
            admission.release();
        }
    }

    private static OperationResponse toResponse(ScriptResult result, String runId) {
        if (!result.isError()) {
            return new CallProcedureResponse("Procedure executed successfully", result.getValue(), result.getLogs(),
                    result.isLogsTruncated(), runId);
        }
        return new CallProcedureResponse(result.getErrorName() + ": " + result.getErrorMessage(),
                ScriptOperationHelper.errorCodeFor(result.getErrorName()), result.getLogs(), result.isLogsTruncated(),
                runId, result.getErrorStack());
    }
}
