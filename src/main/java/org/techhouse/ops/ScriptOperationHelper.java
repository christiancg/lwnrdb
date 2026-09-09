package org.techhouse.ops;

import static org.techhouse.simplejs.host.ScriptErrorNames.CANCELLED;
import static org.techhouse.simplejs.host.ScriptErrorNames.EXHAUSTED_MEMORY_MESSAGE;
import static org.techhouse.simplejs.host.ScriptErrorNames.LIMIT;
import static org.techhouse.simplejs.host.ScriptErrorNames.MEMORY;
import static org.techhouse.simplejs.host.ScriptErrorNames.PENDING_RESULT;
import static org.techhouse.simplejs.host.ScriptErrorNames.RESULT_TOO_LARGE;
import static org.techhouse.simplejs.host.ScriptErrorNames.TIMEOUT;

import java.nio.charset.StandardCharsets;
import java.util.List;
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

public final class ScriptOperationHelper {
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ScriptRunRegistry registry = IocContainer.get(ScriptRunRegistry.class);
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
    private static final CompiledScriptCache compiledScripts = IocContainer.get(CompiledScriptCache.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(ScriptOperationHelper.class);

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
        final var permit = admission.acquire(username, dbName);
        if (permit == null) {
            return concurrencyRefusal(OperationType.RUN_SCRIPT);
        }
        try (permit) {
            final var run = registry.register(ScriptRunKind.RUN_SCRIPT, dbName, null, username, clientId);
            try {
                final var compilation = compiledScripts.get(source);
                final var database = new EnforcingDatabaseAccess(username, clientId, dbName);
                final var host = hostFor(request.getArgs(), database, DatabaseHostBindings.limitsFromConfiguration(),
                        null, run);
                final var start = System.currentTimeMillis();
                final var raw = compilation.failure() == null
                        ? simpleJs.run(compilation.compiled(), host)
                        : simpleJs.failure(compilation.failure());
                final var elapsed = System.currentTimeMillis() - start;
                final var result = raw
                        .withMetrics(raw.getMetrics().withHostCounters(database.operationCount(), elapsed));
                logRun("RUN_SCRIPT user=" + username + " database=" + dbName, run.runId(), elapsed, result);
                ScriptRunHistory.record(historyOf(run.runId(), ScriptRunKind.RUN_SCRIPT, dbName, null, username, start,
                        elapsed, result));
                return toRunScriptResponse(result, run.runId());
            } finally {
                registry.unregister(run.runId());
            }
        }
    }

    static OperationResponse concurrencyRefusal(OperationType type) {
        final var scope = admission.lastRefusalScope();
        return new OperationResponse(type,
                ErrorCode.SCRIPT_CONCURRENCY_LIMIT.getDefaultMessage() + " (" + scope + " limit)",
                ErrorCode.SCRIPT_CONCURRENCY_LIMIT);
    }

    public record RunOutcome(String runId, ScriptResult result) {
    }

    static RunOutcome runProcedure(CompiledScript compiled, JsonObject args, String dbName, String username,
            UUID clientId, String logPrefix, String name) {
        return runCompiled(compiled, args, dbName, username, clientId, logPrefix, ScriptRunKind.CALL_PROCEDURE, name,
                DatabaseHostBindings.limitsFromConfiguration(), null);
    }

    static RunOutcome runCompiled(CompiledScript compiled, JsonObject args, String dbName, String username,
            UUID clientId, String logPrefix, ScriptRunKind kind, String name, ResourceLimits limits,
            Consumer<String> console) {
        final var run = registry.register(kind, dbName, name, username, clientId);
        try {
            final var database = new EnforcingDatabaseAccess(username, clientId, dbName);
            final var host = hostFor(args, database, limits, console, run);
            final var start = System.currentTimeMillis();
            final var raw = simpleJs.run(compiled, host);
            final var elapsed = System.currentTimeMillis() - start;
            final var result = raw.withMetrics(raw.getMetrics().withHostCounters(database.operationCount(), elapsed));
            logRun(logPrefix, run.runId(), elapsed, result);
            if (kind != ScriptRunKind.TRIGGER && kind != ScriptRunKind.SCHEDULE) {
                ScriptRunHistory.record(historyOf(run.runId(), kind, dbName, name, username, start, elapsed, result));
            }
            return new RunOutcome(run.runId(), result);
        } finally {
            registry.unregister(run.runId());
        }
    }

    private static DatabaseHostBindings hostFor(JsonObject args, EnforcingDatabaseAccess database,
            ResourceLimits limits, Consumer<String> console, ScriptRun run) {
        return DatabaseHostBindings.of(args, database, console, limits, run::isCancelled);
    }

    private static void logRun(String logPrefix, String runId, long durationMs, ScriptResult result) {
        final var outcome = result.isError() ? result.getErrorName() + ": " + result.getErrorMessage() : "ok";
        final var metrics = result.getMetrics();
        final var line = logPrefix + " runId=" + runId + " durationMs=" + durationMs + " outcome=" + outcome
                + " instructions=" + metrics.instructions() + " peakMemoryBytes=" + metrics.peakMemoryBytes()
                + " dbOps=" + metrics.dbOperations() + renderStack(result.getErrorStack());
        if (EXHAUSTED_MEMORY_MESSAGE.equals(result.getErrorMessage())) {
            logger.warning(line);
        } else {
            logger.info(line);
        }
    }

    private static OperationResponse toRunScriptResponse(ScriptResult result, String runId) {
        final var response = result.isError()
                ? new RunScriptResponse(result.getErrorName() + ": " + result.getErrorMessage(),
                        errorCodeFor(result.getErrorName()), result.getLogs(), result.isLogsTruncated(), runId,
                        result.getErrorStack())
                : new RunScriptResponse("Script executed successfully", result.getValue(), result.getLogs(),
                        result.isLogsTruncated(), runId);
        response.setMetrics(result.getMetrics().toJson());
        return response;
    }

    static ScriptRunRecord historyOf(String runId, ScriptRunKind kind, String dbName, String name, String username,
            long startedAt, long durationMs, ScriptResult result) {
        return new ScriptRunRecord(runId, kind, dbName, name, name, null, null, username, username, startedAt,
                durationMs, 1, result.isError() ? ScriptRunRecord.OUTCOME_ERROR : ScriptRunRecord.OUTCOME_OK,
                result.getErrorName(), result.getErrorMessage(), result.getErrorStack(), result.getMetrics(),
                result.getLogs(), result.isLogsTruncated());
    }

    public static String renderStack(List<String> stack) {
        return stack == null || stack.isEmpty() ? "" : " stack=[" + String.join(" | ", stack) + "]";
    }

    public static ErrorCode errorCodeFor(String errorName) {
        return switch (errorName) {
            case TIMEOUT -> ErrorCode.SCRIPT_TIMEOUT;
            case CANCELLED -> ErrorCode.SCRIPT_CANCELLED;
            case LIMIT -> ErrorCode.SCRIPT_LIMIT_EXCEEDED;
            case MEMORY -> ErrorCode.SCRIPT_MEMORY_EXCEEDED;
            case RESULT_TOO_LARGE -> ErrorCode.SCRIPT_RESULT_TOO_LARGE;
            case PENDING_RESULT -> ErrorCode.SCRIPT_RESULT_PENDING;
            default -> ErrorCode.SCRIPT_FAILED;
        };
    }
}
