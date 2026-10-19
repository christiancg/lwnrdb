package org.techhouse.ops;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.InvalidCronException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminStamp;
import org.techhouse.ops.admin.StoredDefinitions;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.ListSchedulesRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.resp.ListSchedulesResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveScheduleResponse;
import org.techhouse.ops.schedule.CronExpression;

public final class ScheduleOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final ReentrantLock definerQuota = new ReentrantLock();

    private ScheduleOperationHelper() {
    }

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
            return databaseNotFound(dbName);
        }
        final var timingError = validateTiming(request);
        if (timingError != null) {
            return timingError;
        }
        if (cache.getProcedure(dbName, request.getProcedureName()) == null) {
            return procedureNotFound(request);
        }
        return OperationLocks.withDatabaseShared(dbName, OperationType.SAVE_SCHEDULE, ErrorCode.ERROR_SAVING_SCHEDULE,
                () -> saveUnderDatabaseBarrier(request, actingUser));
    }

    private static OperationResponse saveUnderDatabaseBarrier(SaveScheduleRequest request, String actingUser)
            throws IOException, InterruptedException {
        final var dbName = request.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) == null) {
            return databaseNotFound(dbName);
        }
        locks.lock(dbName, Globals.SCHEDULES_FOLDER);
        locks.lock(dbName, Globals.PROCEDURES_FOLDER);
        try {
            if (cache.getProcedure(dbName, request.getProcedureName()) == null) {
                return procedureNotFound(request);
            }
            final var existing = cache.getSchedule(dbName, request.getName());
            if (request.conflictsWith(existing == null ? 0L : existing.getVersion())) {
                return new OperationResponse(OperationType.SAVE_SCHEDULE, ErrorCode.PROCEDURE_VERSION_CONFLICT);
            }
            final var foreign = existing == null
                    ? null
                    : ProcedureBindingGuard.foreignDefinitionRefusal(OperationType.SAVE_SCHEDULE, dbName,
                            existing.getDefiner(), actingUser);
            if (foreign != null) {
                return foreign;
            }
            final var scheduleNames = fs.listScheduleNames(dbName);
            if (existing == null && scheduleNames.size() >= configuration.getScheduleMaxPerDatabase()) {
                return new OperationResponse(OperationType.SAVE_SCHEDULE, ErrorCode.TOO_MANY_SCHEDULES);
            }
            final var colliding = OnDiskNameRegistry.collidingDefinition(scheduleNames, request.getName());
            if (colliding != null) {
                return new OperationResponse(OperationType.SAVE_SCHEDULE, ErrorCode.NAME_COLLIDES_ON_DISK, colliding);
            }
            definerQuota.lock();
            try {
                if (takesANewDefinerSlot(existing, actingUser)
                        && registry.countDefinedBy(actingUser) >= maxSchedulesPerUser()) {
                    return new OperationResponse(OperationType.SAVE_SCHEDULE,
                            ErrorCode.TOO_MANY_SCHEDULES_FOR_USER.getDefaultMessage() + " (" + maxSchedulesPerUser()
                                    + ")",
                            ErrorCode.TOO_MANY_SCHEDULES_FOR_USER);
                }
                final var definition = newDefinition(request, existing, actingUser);
                StoredDefinitions.writeSchedule(dbName, definition.getName(), definition.toJsonObject());
                cache.putSchedule(dbName, definition);
                registry.reload(dbName);
                return new SaveScheduleResponse("Schedule saved successfully", definition.getVersion());
            } finally {
                definerQuota.unlock();
            }
        } finally {
            locks.release(dbName, Globals.PROCEDURES_FOLDER);
            locks.release(dbName, Globals.SCHEDULES_FOLDER);
        }
    }

    private static boolean takesANewDefinerSlot(ScheduleDefinition existing, String actingUser) {
        return actingUser != null && (existing == null || !actingUser.equals(existing.getDefiner()));
    }

    private static int maxSchedulesPerUser() {
        return configuration.intValue(ConfigKey.SCHEDULE_MAX_TOTAL_PER_USER);
    }

    private static OperationResponse procedureNotFound(SaveScheduleRequest request) {
        return new OperationResponse(OperationType.SAVE_SCHEDULE, "Procedure '" + request.getProcedureName()
                + "' not found in database '" + request.getDatabaseName() + "'", ErrorCode.PROCEDURE_NOT_FOUND);
    }

    private static OperationResponse databaseNotFound(String dbName) {
        return new OperationResponse(OperationType.SAVE_SCHEDULE, "Database '" + dbName + "' not found",
                ErrorCode.DATABASE_NOT_FOUND);
    }

    private static OperationResponse validateTiming(SaveScheduleRequest request) {
        final var hasCron = request.getCron() != null && !request.getCron().isBlank();
        final var hasInterval = request.getIntervalMs() > 0;
        if (hasCron == hasInterval) {
            return invalid("exactly one of 'cron' and 'intervalMs' is required");
        }
        final var minInterval = configuration.longValue(ConfigKey.SCHEDULE_MIN_INTERVAL_MS);
        if (hasInterval && request.getIntervalMs() < minInterval) {
            return intervalTooSmall(minInterval);
        }
        if (request.getTimeoutMs() < 0) {
            return invalid("timeoutMs must not be negative");
        }
        final var maxTimeout = configuration.longValue(ConfigKey.SCHEDULE_MAX_TIMEOUT_MS);
        if (request.getTimeoutMs() > maxTimeout) {
            return new OperationResponse(OperationType.SAVE_SCHEDULE,
                    ErrorCode.SCHEDULE_TIMEOUT_TOO_LARGE.getDefaultMessage() + " (" + maxTimeout + " ms)",
                    ErrorCode.SCHEDULE_TIMEOUT_TOO_LARGE);
        }
        if (hasCron) {
            final CronExpression expression;
            try {
                expression = CronExpression.parse(request.getCron());
            } catch (InvalidCronException e) {
                return invalid(e.getMessage());
            }
            final var now = ZonedDateTime.now(ZoneId.of(configuration.getScriptTimeZone()));
            if (expression.nextAfter(now) == null) {
                return invalid("cron '" + request.getCron() + "' has no occurrence within the search horizon");
            }
            if (expression.shortestGapMillis(now) < minInterval) {
                return intervalTooSmall(minInterval);
            }
        }
        return null;
    }

    private static OperationResponse intervalTooSmall(long minInterval) {
        return new OperationResponse(OperationType.SAVE_SCHEDULE,
                ErrorCode.SCHEDULE_INTERVAL_TOO_SMALL.getDefaultMessage() + " (" + minInterval + " ms)",
                ErrorCode.SCHEDULE_INTERVAL_TOO_SMALL);
    }

    private static OperationResponse invalid(String reason) {
        return new OperationResponse(OperationType.SAVE_SCHEDULE,
                ErrorCode.INVALID_SCHEDULE.getDefaultMessage() + ": " + reason, ErrorCode.INVALID_SCHEDULE);
    }

    private static ScheduleDefinition newDefinition(SaveScheduleRequest request, ScheduleDefinition existing,
            String actingUser) {
        final var version = existing == null ? 1L : existing.getVersion() + 1;
        final var updatedAt = System.currentTimeMillis();
        final var createdAt = existing == null ? updatedAt : existing.getCreatedAt();
        return new ScheduleDefinition(request.getName(), request.getProcedureName(), request.getCron(),
                request.getIntervalMs(), request.getArgs(), request.getTimeoutMs(), request.isEnabled(), actingUser,
                request.getDescription(), version, createdAt, updatedAt, actingUser);
    }

    public static OperationResponse executeDelete(DeleteScheduleRequest request, String actingUser)
            throws IOException, InterruptedException {
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
            final var existing = cache.getSchedule(dbName, request.getName());
            final var foreign = existing == null
                    ? null
                    : ProcedureBindingGuard.foreignDefinitionRefusal(OperationType.DELETE_SCHEDULE, dbName,
                            existing.getDefiner(), actingUser);
            if (foreign != null) {
                return foreign;
            }
            if (fs.deleteSchedule(dbName, request.getName())) {
                AdminStamp.tombstone(AdminRecordKey.schedule(dbName, request.getName()));
            }
            cache.removeSchedule(dbName, request.getName());
            registry.reload(dbName);
            return OperationResponse.ok(OperationType.DELETE_SCHEDULE, "Schedule deleted successfully");
        } finally {
            locks.release(dbName, Globals.SCHEDULES_FOLDER);
        }
    }

    public static OperationResponse executeList(ListSchedulesRequest request) {
        return OperationResponse.respondOrError(OperationType.LIST_SCHEDULES, ErrorCode.ERROR_RETRIEVING,
                () -> listSchedules(request));
    }

    private static OperationResponse listSchedules(ListSchedulesRequest request) throws IOException {
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
                result.add(summaryOf(dbName, definition, request.isIncludeArgs()));
            }
        }
        return new ListSchedulesResponse("Ok", result);
    }

    private static JsonObject summaryOf(String dbName, ScheduleDefinition definition, boolean includeArgs) {
        final var json = includeArgs ? definition.toJsonObject() : definition.toSummaryJson();
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

    public static boolean disable(String dbName, ScheduleDefinition dispatched, String reason)
            throws IOException, InterruptedException {
        final var budget = OperationLocks.lockBudgetMillis(false);
        if (!locks.tryLockDatabaseShared(dbName, budget)) {
            return false;
        }
        try {
            if (!locks.tryLockWrite(dbName, Globals.SCHEDULES_FOLDER, budget)) {
                return false;
            }
            try {
                final var existing = cache.getSchedule(dbName, dispatched.getName());
                if (existing == null || !existing.isEnabled() || existing.getVersion() != dispatched.getVersion()) {
                    return false;
                }
                final var disabled = existing.disabledCopy(reason, System.currentTimeMillis());
                StoredDefinitions.writeSchedule(dbName, disabled.getName(), disabled.toJsonObject());
                cache.putSchedule(dbName, disabled);
                registry.reload(dbName);
                return true;
            } finally {
                locks.release(dbName, Globals.SCHEDULES_FOLDER);
            }
        } finally {
            locks.releaseDatabaseShared(dbName);
        }
    }

    public static List<ScheduleDefinition> schedulesIn(String dbName) throws IOException {
        final var result = new ArrayList<ScheduleDefinition>();
        for (final var name : fs.listScheduleNames(dbName)) {
            final var definition = cache.getSchedule(dbName, name);
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    public static String scheduleReferencing(String dbName, String procedureName) throws IOException {
        for (final var definition : schedulesIn(dbName)) {
            if (procedureName.equals(definition.getProcedureName())) {
                return definition.getName();
            }
        }
        return null;
    }
}
