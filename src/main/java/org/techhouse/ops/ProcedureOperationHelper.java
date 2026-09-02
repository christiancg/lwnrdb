package org.techhouse.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.ListProceduresRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.DeleteProcedureResponse;
import org.techhouse.ops.resp.ListProceduresResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveProcedureResponse;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.exceptions.UnterminatedCommentException;
import org.techhouse.simplejs.exceptions.UnterminatedRegexException;
import org.techhouse.simplejs.exceptions.UnterminatedStringException;
import org.techhouse.simplejs.exceptions.UnterminatedTemplateException;

/**
 * Persists and removes a database's stored procedures. A procedure lives with its database
 * ({@code {db}/.procedures/{name}.json}) rather than in an admin collection, following the per-collection
 * JSON Schema, so a DROP_DATABASE removes it with the data and no PK index or page metadata is involved.
 */
public final class ProcedureOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final Configuration configuration = Configuration.getInstance();

    private ProcedureOperationHelper() {
    }

    public static OperationResponse executeSave(SaveProcedureRequest request, String actingUser)
            throws IOException, InterruptedException {
        if (!configuration.isScriptsEnabled()) {
            return new OperationResponse(OperationType.SAVE_PROCEDURE, ErrorCode.SCRIPTS_DISABLED);
        }
        final var dbName = request.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) == null) {
            return new OperationResponse(OperationType.SAVE_PROCEDURE, "Database '" + dbName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        final var source = request.getScript();
        if (source.getBytes(StandardCharsets.UTF_8).length > configuration.getScriptMaxSourceBytes()) {
            return new OperationResponse(OperationType.SAVE_PROCEDURE, ErrorCode.SCRIPT_TOO_LARGE);
        }
        // Parsing here is the point of a stored procedure over a client-side string: a broken body is
        // refused now rather than on somebody else's first call.
        try {
            var _ = simpleJs.compile(source, false);
        } catch (SimpleJsRuntimeException | UnexpectedTokenException | UnexpectedEndOfInputException
                | UnexpectedCharacterException | UnterminatedStringException | UnterminatedTemplateException
                | UnterminatedCommentException | UnterminatedRegexException parseFailure) {
            return new OperationResponse(OperationType.SAVE_PROCEDURE,
                    ErrorCode.INVALID_PROCEDURE.getDefaultMessage() + ": " + parseFailure.getMessage(),
                    ErrorCode.INVALID_PROCEDURE);
        }
        locks.lock(dbName, Globals.PROCEDURES_FOLDER);
        try {
            final var existing = cache.getProcedure(dbName, request.getName());
            if (request.getIfVersion() != null
                    && request.getIfVersion() != (existing == null ? 0L : existing.getVersion())) {
                return new OperationResponse(OperationType.SAVE_PROCEDURE, ErrorCode.PROCEDURE_VERSION_CONFLICT);
            }
            final var definition = stampedDefinition(request, existing, actingUser);
            fs.writeProcedure(dbName, definition.getName(), eJson.toJson(definition.toJsonObject()));
            cache.putProcedure(dbName, definition);
            return new SaveProcedureResponse("Procedure saved successfully", definition.getVersion());
        } finally {
            locks.release(dbName, Globals.PROCEDURES_FOLDER);
        }
    }

    // The version, timestamp and author are computed once and written back onto the request, so a peer
    // re-executing this same request under REPLICATE_ADMIN produces a byte-identical file instead of
    // stamping its own System.currentTimeMillis() and diverging.
    private static ProcedureDefinition stampedDefinition(SaveProcedureRequest request, ProcedureDefinition existing,
            String actingUser) {
        final var version = existing == null ? 1L : existing.getVersion() + 1;
        final var createdAt = existing == null ? System.currentTimeMillis() : existing.getCreatedAt();
        final var alreadyStamped = request.getStampedVersion() > 0;
        final var effectiveVersion = alreadyStamped ? request.getStampedVersion() : version;
        final var effectiveUpdatedAt = alreadyStamped ? request.getStampedUpdatedAt() : System.currentTimeMillis();
        final var effectiveUpdatedBy = alreadyStamped ? request.getStampedUpdatedBy() : actingUser;
        request.setStampedVersion(effectiveVersion);
        request.setStampedUpdatedAt(effectiveUpdatedAt);
        request.setStampedUpdatedBy(effectiveUpdatedBy);
        return new ProcedureDefinition(request.getName(), request.getScript(), effectiveVersion,
                request.getDescription(), request.isEnabled(), createdAt, effectiveUpdatedAt, effectiveUpdatedBy);
    }

    // Idempotent: succeeds whether the procedure existed, so cluster re-execution on a peer that is
    // already procedure-less does not fail replication.
    public static OperationResponse executeDelete(DeleteProcedureRequest request)
            throws IOException, InterruptedException {
        final var dbName = request.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) == null) {
            return new OperationResponse(OperationType.DELETE_PROCEDURE, "Database '" + dbName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        locks.lock(dbName, Globals.PROCEDURES_FOLDER);
        try {
            final var referencing = TriggerOperationHelper.triggerReferencing(dbName, request.getName());
            if (referencing != null) {
                return new OperationResponse(OperationType.DELETE_PROCEDURE,
                        "Procedure '" + request.getName() + "' is still referenced by trigger '" + referencing + "'",
                        ErrorCode.INVALID_TRIGGER);
            }
            final var scheduled = ScheduleOperationHelper.scheduleReferencing(dbName, request.getName());
            if (scheduled != null) {
                return new OperationResponse(OperationType.DELETE_PROCEDURE,
                        "Procedure '" + request.getName() + "' is still referenced by schedule '" + scheduled + "'",
                        ErrorCode.INVALID_SCHEDULE);
            }
            fs.deleteProcedure(dbName, request.getName());
            cache.removeProcedure(dbName, request.getName());
            // Version-keying alone is not enough here: a delete resets the version, so re-creating the
            // same name would otherwise be served the deleted procedure's compiled program at version 1.
            compiledProcedures.invalidateProcedure(dbName, request.getName());
            return new DeleteProcedureResponse("Procedure deleted successfully");
        } finally {
            locks.release(dbName, Globals.PROCEDURES_FOLDER);
        }
    }

    public static OperationResponse executeList(ListProceduresRequest request) {
        final var dbName = request.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) == null) {
            return new OperationResponse(OperationType.LIST_PROCEDURES, "Database '" + dbName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        final var result = new ArrayList<org.techhouse.ejson.elements.JsonObject>();
        for (final var name : fs.listProcedureNames(dbName)) {
            final var definition = cache.getProcedure(dbName, name);
            if (definition != null) {
                result.add(request.isIncludeSource() ? definition.toJsonObject() : definition.toSummaryJson());
            }
        }
        return new ListProceduresResponse("Ok", result);
    }
}
