package org.techhouse.ops;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.RunScriptRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.RunScriptResponse;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.DatabaseHostBindings;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.simplejs.host.ScriptResult;

// Runs a client-supplied script through SimpleJs, scoped to the requested database and bounded by the
// script* configuration keys. Whether the caller may run a script at all is decided earlier, by
// AuthorizationChecker; every operation the script itself issues is authorized again on its own request.
public final class ScriptOperationHelper {
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final Cache cache = IocContainer.get(Cache.class);
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
        final var database = new EnforcingDatabaseAccess(username, clientId, dbName);
        // A null console sink leaves CapturingHostBindings capturing only, so the output travels back on
        // the response instead of into the server log.
        final var host = DatabaseHostBindings.of(request.getArgs(), database, null,
                DatabaseHostBindings.limitsFromConfiguration());
        final var start = System.currentTimeMillis();
        final var result = simpleJs.run(source, host);
        logRun(username, dbName, System.currentTimeMillis() - start, result);
        return toResponse(result);
    }

    private static void logRun(String username, String dbName, long durationMs, ScriptResult result) {
        final var outcome = result.isError() ? result.getErrorName() + ": " + result.getErrorMessage() : "ok";
        logger.info("RUN_SCRIPT user=" + username + " database=" + dbName + " durationMs=" + durationMs + " outcome="
                + outcome);
    }

    private static OperationResponse toResponse(ScriptResult result) {
        if (!result.isError()) {
            return new RunScriptResponse("Script executed successfully", result.getValue(), result.getLogs(),
                    result.isLogsTruncated());
        }
        return new RunScriptResponse(result.getErrorName() + ": " + result.getErrorMessage(),
                errorCodeFor(result.getErrorName()), result.getLogs(), result.isLogsTruncated());
    }

    private static ErrorCode errorCodeFor(String errorName) {
        return switch (errorName) {
            case "ScriptTimeoutError" -> ErrorCode.SCRIPT_TIMEOUT;
            case "ScriptLimitError" -> ErrorCode.SCRIPT_LIMIT_EXCEEDED;
            default -> ErrorCode.SCRIPT_FAILED;
        };
    }
}
