package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.InvalidCronException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.ListSchedulesRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.resp.DeleteScheduleResponse;
import org.techhouse.ops.resp.ListSchedulesResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveScheduleResponse;
import org.techhouse.ops.schedule.CronExpression;

/**
 * Persists and removes a database's schedules. A schedule lives with its database
 * ({@code {db}/.schedules/{name}.json}) exactly as a stored procedure does, so a DROP_DATABASE removes it
 * with the data and no admin collection is involved.
 */
public final class ScheduleOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final Configuration configuration = Configuration.getInstance();

    private ScheduleOperationHelper() {
    }

    // The ring key a schedule is hashed onto, so schedules spread across the cluster and hand off on a
    // membership change through the same machinery a collection's ownership uses.
    public static String ringKey(String name) {
        return Globals.SCHEDULES_FOLDER + Globals.COLL_IDENTIFIER_SEPARATOR + name;
    }

    public static OperationResponse executeSave(SaveScheduleRequest request, String actingUser)
            throws IOException, InterruptedException {
        if (!configuration.isSchedulesEnabled()) {
            return new OperationResponse(OperationType.SAVE_SCHEDULE, ErrorCode.SCRIPTS_DISABLED);
        }
        final var dbName = request.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) == null) {
            return new OperationResponse(OperationType.SAVE_SCHEDULE, "Database '" + dbName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        final var timingError = validateTiming(request);
        if (timingError != null) {
            return timingError;
        }
        // A schedule pointing at nothing is a configuration error worth failing loudly, the same rule
        // SAVE_TRIGGER follows.
        final var procedure = cache.getProcedure(dbName, request.getProcedureName());
        if (procedure == null) {
            return new OperationResponse(OperationType.SAVE_SCHEDULE,
                    "Procedure '" + request.getProcedureName() + "' not found in database '" + dbName + "'",
                    ErrorCode.PROCEDURE_NOT_FOUND);
        }
        locks.lock(dbName, Globals.SCHEDULES_FOLDER);
        try {
            final var existing = cache.getSchedule(dbName, request.getName());
            if (request.getIfVersion() != null
                    && request.getIfVersion() != (existing == null ? 0L : existing.getVersion())) {
                return new OperationResponse(OperationType.SAVE_SCHEDULE, ErrorCode.PROCEDURE_VERSION_CONFLICT);
            }
            if (existing == null && fs.listScheduleNames(dbName).size() >= configuration.getScheduleMaxPerDatabase()) {
                return new OperationResponse(OperationType.SAVE_SCHEDULE, ErrorCode.TOO_MANY_SCHEDULES);
            }
            final var definition = stampedDefinition(request, existing, actingUser);
            fs.writeSchedule(dbName, definition.getName(), eJson.toJson(definition.toJsonObject()));
            cache.putSchedule(dbName, definition);
            registry.reload(dbName);
            return new SaveScheduleResponse("Schedule saved successfully", definition.getVersion());
        } finally {
            locks.release(dbName, Globals.SCHEDULES_FOLDER);
        }
    }

    // Exactly one of cron and intervalMs, and a cron that parses. Refused here rather than warned about
    // per tick: a schedule that never resolves to an instant is a configuration error.
    private static OperationResponse validateTiming(SaveScheduleRequest request) {
        final var hasCron = request.getCron() != null && !request.getCron().isBlank();
        final var hasInterval = request.getIntervalMs() > 0;
        if (hasCron == hasInterval) {
            return invalid("exactly one of 'cron' and 'intervalMs' is required");
        }
        if (request.getTimeoutMs() < 0) {
            return invalid("timeoutMs must not be negative");
        }
        if (hasCron) {
            try {
                CronExpression.parse(request.getCron());
            } catch (InvalidCronException e) {
                return invalid(e.getMessage());
            }
        }
        return null;
    }

    private static OperationResponse invalid(String reason) {
        return new OperationResponse(OperationType.SAVE_SCHEDULE,
                ErrorCode.INVALID_SCHEDULE.getDefaultMessage() + ": " + reason, ErrorCode.INVALID_SCHEDULE);
    }

    // Every derived field is computed once, by the coordinator, onto the request, so a peer re-executing it
    // under REPLICATE_ADMIN writes a byte-identical file. Re-saving re-stamps the definer to the saving
    // user, mirroring SAVE_TRIGGER: keeping the original would leave a job running with a previous
    // installer's authority after somebody else edited it.
    private static ScheduleDefinition stampedDefinition(SaveScheduleRequest request, ScheduleDefinition existing,
            String actingUser) {
        final var alreadyStamped = request.getStampedVersion() > 0;
        final var version = alreadyStamped
                ? request.getStampedVersion()
                : (existing == null ? 1L : existing.getVersion() + 1);
        final var updatedAt = alreadyStamped ? request.getStampedUpdatedAt() : System.currentTimeMillis();
        final var updatedBy = alreadyStamped ? request.getStampedUpdatedBy() : actingUser;
        final var definer = alreadyStamped ? request.getStampedDefiner() : actingUser;
        request.setStampedVersion(version);
        request.setStampedUpdatedAt(updatedAt);
        request.setStampedUpdatedBy(updatedBy);
        request.setStampedDefiner(definer);
        final var createdAt = existing == null ? updatedAt : existing.getCreatedAt();
        return new ScheduleDefinition(request.getName(), request.getProcedureName(), request.getCron(),
                request.getIntervalMs(), request.getArgs(), request.getTimeoutMs(), request.isEnabled(), definer,
                request.getDescription(), version, createdAt, updatedAt, updatedBy);
    }

    // Idempotent: succeeds whether the schedule existed, so cluster re-execution on a peer that is already
    // schedule-less does not fail replication.
    public static OperationResponse executeDelete(DeleteScheduleRequest request) throws InterruptedException {
        if (!configuration.isSchedulesEnabled()) {
            return new OperationResponse(OperationType.DELETE_SCHEDULE, ErrorCode.SCRIPTS_DISABLED);
        }
        final var dbName = request.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) == null) {
            return new OperationResponse(OperationType.DELETE_SCHEDULE, "Database '" + dbName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        locks.lock(dbName, Globals.SCHEDULES_FOLDER);
        try {
            fs.deleteSchedule(dbName, request.getName());
            cache.removeSchedule(dbName, request.getName());
            registry.reload(dbName);
            return new DeleteScheduleResponse("Schedule deleted successfully");
        } finally {
            locks.release(dbName, Globals.SCHEDULES_FOLDER);
        }
    }

    public static OperationResponse executeList(ListSchedulesRequest request) {
        if (!configuration.isSchedulesEnabled()) {
            return new OperationResponse(OperationType.LIST_SCHEDULES, ErrorCode.SCRIPTS_DISABLED);
        }
        final var dbName = request.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) == null) {
            return new OperationResponse(OperationType.LIST_SCHEDULES, "Database '" + dbName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        final var result = new ArrayList<JsonObject>();
        for (final var name : fs.listScheduleNames(dbName)) {
            final var definition = cache.getSchedule(dbName, name);
            if (definition != null) {
                result.add(summaryOf(dbName, definition));
            }
        }
        return new ListSchedulesResponse("Ok", result);
    }

    // nextRunAt and owner are computed by the answering node: the registry is in-memory and per-node, so
    // both describe this node's view rather than a cluster-wide fact.
    private static JsonObject summaryOf(String dbName, ScheduleDefinition definition) {
        final var json = definition.toSummaryJson();
        final var entry = registry.get(dbName, definition.getName());
        if (entry != null) {
            json.addProperty("nextRunAt", entry.getNextRunAt());
        }
        final var owner = ownershipManager.ownerFor(dbName, ringKey(definition.getName()));
        if (owner != null) {
            json.addProperty("owner", owner);
        }
        return json;
    }

    // The name of a schedule in the database still pointing at the procedure, or null when none does. Only
    // consulted on a procedure delete, mirroring TriggerOperationHelper.triggerReferencing.
    public static String scheduleReferencing(String dbName, String procedureName) {
        for (final var name : fs.listScheduleNames(dbName)) {
            final var definition = cache.getSchedule(dbName, name);
            if (definition != null && procedureName.equals(definition.getProcedureName())) {
                return definition.getName();
            }
        }
        return null;
    }
}
