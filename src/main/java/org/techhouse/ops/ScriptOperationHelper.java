package org.techhouse.ops;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.RunScriptRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.RunScriptResponse;
import org.techhouse.simplejs.CompiledScript;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.DatabaseHostBindings;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;

// Runs a client-supplied script through SimpleJs, scoped to the requested database and bounded by the
// script* configuration keys. Whether the caller may run a script at all is decided earlier, by
// AuthorizationChecker; every operation the script itself issues is authorized again on its own request.
// The sandbox and outcome mapping below are shared with ProcedureCallHelper, which differs only in where
// the source comes from.
public final class ScriptOperationHelper {
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ScriptRunRegistry registry = IocContainer.get(ScriptRunRegistry.class);
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(ScriptOperationHelper.class);
    private static final String EXHAUSTED_MESSAGE = "Script exhausted available memory";

    private ScriptOperationHelper() {
    }

    public static OperationResponse execute(RunScriptRequest request, String username, UUID clientId) {
        if (!configuration.isScriptsEnabled()) {
            return new OperationResponse(OperationType.RUN_SCRIPT, ErrorCode.SCRIPTS_DISABLED);
        }
        final var dbName = request.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) == null) {
            return new OperationResponse(OperationType.RUN_SCRIPT, "Database '" + dbName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        final var source = request.getScript();
        if (source.getBytes(StandardCharsets.UTF_8).length > configuration.getScriptMaxSourceBytes()) {
            return new OperationResponse(OperationType.RUN_SCRIPT, ErrorCode.SCRIPT_TOO_LARGE);
        }
        // Admitted only after the checks above, so a request that was never going to run does not consume
        // one of the node's script permits.
        if (!admission.tryAcquire()) {
            return new OperationResponse(OperationType.RUN_SCRIPT, ErrorCode.SCRIPT_CONCURRENCY_LIMIT);
        }
        try {
            final var run = registry.register(ScriptRunKind.RUN_SCRIPT, dbName, null, username, clientId);
            try {
                // Deliberately the source overload, not compile(): a syntax error has to stay a 400-9 response
                // rather than an exception, which is the contract this operation already had.
                final var host = hostFor(request.getArgs(), dbName, username, clientId,
                        DatabaseHostBindings.limitsFromConfiguration(), null, run);
                final var start = System.currentTimeMillis();
                final var result = simpleJs.run(source, host);
                logRun("RUN_SCRIPT user=" + username + " database=" + dbName, run.runId(),
                        System.currentTimeMillis() - start, result);
                return toRunScriptResponse(result, run.runId());
            } finally {
                registry.unregister(run.runId());
            }
        } finally {
            admission.release();
        }
    }

    /** A finished run and the id it was visible under while it ran, so a caller can report both. */
    public record RunOutcome(String runId, ScriptResult result) {
    }

    // The shared body: build the configured sandbox, run an already-parsed program, log the outcome.
    // Callers map the ScriptResult onto their own response subclass. The kind and name come from the caller
    // rather than being inferred here, because CALL_PROCEDURE, a trigger and a schedule all share this body
    // and each is a different thing to see in LIST_SCRIPTS.
    static RunOutcome runCompiled(CompiledScript compiled, JsonObject args, String dbName, String username,
            UUID clientId, String logPrefix, ScriptRunKind kind, String name) {
        return runCompiled(compiled, args, dbName, username, clientId, logPrefix, kind, name,
                DatabaseHostBindings.limitsFromConfiguration(), null);
    }

    // The overload a scheduled run uses: the sandbox is the configured one with its wall clock replaced,
    // and the console is teed to the server log because nobody is waiting on a response to carry it.
    static RunOutcome runCompiled(CompiledScript compiled, JsonObject args, String dbName, String username,
            UUID clientId, String logPrefix, ScriptRunKind kind, String name, ResourceLimits limits,
            Consumer<String> console) {
        final var run = registry.register(kind, dbName, name, username, clientId);
        try {
            final var host = hostFor(args, dbName, username, clientId, limits, console, run);
            final var start = System.currentTimeMillis();
            final var result = simpleJs.run(compiled, host);
            logRun(logPrefix, run.runId(), System.currentTimeMillis() - start, result);
            return new RunOutcome(run.runId(), result);
        } finally {
            registry.unregister(run.runId());
        }
    }

    // A null console sink leaves the capture capturing only, so the output travels back on the response
    // instead of into the server log.
    private static DatabaseHostBindings hostFor(JsonObject args, String dbName, String username, UUID clientId,
            ResourceLimits limits, Consumer<String> console, ScriptRun run) {
        final var database = new EnforcingDatabaseAccess(username, clientId, dbName);
        return DatabaseHostBindings.of(args, database, console, limits, run::isCancelled);
    }

    private static void logRun(String logPrefix, String runId, long durationMs, ScriptResult result) {
        final var outcome = result.isError() ? result.getErrorName() + ": " + result.getErrorMessage() : "ok";
        final var line = logPrefix + " runId=" + runId + " durationMs=" + durationMs + " outcome=" + outcome;
        // An exhausted heap means the allocation budget failed to bound the script, or that the JVM was
        // already under pressure from the cache rather than from this script. Either needs an operator.
        if (EXHAUSTED_MESSAGE.equals(result.getErrorMessage())) {
            logger.warning(line);
        } else {
            logger.info(line);
        }
    }

    private static OperationResponse toRunScriptResponse(ScriptResult result, String runId) {
        if (!result.isError()) {
            return new RunScriptResponse("Script executed successfully", result.getValue(), result.getLogs(),
                    result.isLogsTruncated(), runId);
        }
        return new RunScriptResponse(result.getErrorName() + ": " + result.getErrorMessage(),
                errorCodeFor(result.getErrorName()), result.getLogs(), result.isLogsTruncated(), runId);
    }

    static ErrorCode errorCodeFor(String errorName) {
        return switch (errorName) {
            case "ScriptTimeoutError" -> ErrorCode.SCRIPT_TIMEOUT;
            case "ScriptCancelledError" -> ErrorCode.SCRIPT_CANCELLED;
            case "ScriptLimitError" -> ErrorCode.SCRIPT_LIMIT_EXCEEDED;
            case "ScriptMemoryError" -> ErrorCode.SCRIPT_MEMORY_EXCEEDED;
            case "ScriptResultTooLargeError" -> ErrorCode.SCRIPT_RESULT_TOO_LARGE;
            default -> ErrorCode.SCRIPT_FAILED;
        };
    }
}
