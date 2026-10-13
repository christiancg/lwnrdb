package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ex.CollectionBusyException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.AdminOperationHelper.RewriteVersion;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OnDiskNameRegistry;
import org.techhouse.ops.OperationLocks;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.ops.resp.ListDatabasesResponse;
import org.techhouse.ops.resp.OperationResponse;

public final class DatabaseOperationHelper {
    private static final Logger logger = Logger.logFor(DatabaseOperationHelper.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);

    private DatabaseOperationHelper() {
    }

    public static OperationResponse processCreateDatabaseOperation(CreateDatabaseRequest createDatabaseRequest,
            UUID clientId) {
        final var dbName = createDatabaseRequest.getDatabaseName();
        return OperationResponse.respondOrError(OperationType.CREATE_DATABASE, ErrorCode.ERROR_CREATING_DATABASE,
                () -> {
                    if (!locks.tryLockDatabaseExclusive(dbName, OperationLocks.lockBudgetMillis(false))) {
                        return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
                    }
                    try {
                        return createUnderDatabaseBarrier(createDatabaseRequest, clientId);
                    } finally {
                        locks.releaseDatabaseExclusive(dbName);
                    }
                });
    }

    private static OperationResponse createUnderDatabaseBarrier(CreateDatabaseRequest createDatabaseRequest,
            UUID clientId) throws Exception {
        final var dbName = createDatabaseRequest.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) != null) {
            return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.DATABASE_ALREADY_EXISTS);
        }
        return registerUnderNameLock(createDatabaseRequest, clientId);
    }

    private static OperationResponse registerUnderNameLock(CreateDatabaseRequest createDatabaseRequest, UUID clientId)
            throws Exception {
        if (!locks.tryLockWrite(Globals.ADMIN_DB_NAME, Globals.DATABASE_NAMES_LOCK,
                OperationLocks.lockBudgetMillis(false))) {
            return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
        }
        try {
            final var colliding = OnDiskNameRegistry.collidingDatabase(createDatabaseRequest.getDatabaseName());
            if (colliding != null) {
                return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.NAME_COLLIDES_ON_DISK, colliding);
            }
            return register(createDatabaseRequest, clientId);
        } finally {
            locks.release(Globals.ADMIN_DB_NAME, Globals.DATABASE_NAMES_LOCK);
        }
    }

    private static OperationResponse register(CreateDatabaseRequest createDatabaseRequest, UUID clientId)
            throws Exception {
        final var dbName = createDatabaseRequest.getDatabaseName();
        if (!LeftoverFolders.moveAsideUnregisteredDatabase(dbName)) {
            return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.ERROR_CREATING_DATABASE);
        }
        if (fs.createDatabaseFolder(dbName)) {
            final var username = clientTracker.getAuthenticatedUsername(clientId);
            return AdminOperationHelper.withUsersLock(() -> saveNewDatabaseEntry(dbName, existingUser(username)));
        }
        return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.DATABASE_ALREADY_EXISTS);
    }

    private static String existingUser(String username) {
        return username != null && cache.getAdminUserEntry(username) != null ? username : null;
    }

    private static OperationResponse saveNewDatabaseEntry(String dbName, String owner)
            throws IOException, InterruptedException {
        final var owners = owner != null ? List.of(owner) : List.<String>of();
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(dbName, new ArrayList<>(), new ArrayList<>(owners)));
        return OperationResponse.ok(OperationType.CREATE_DATABASE, "Database created successfully");
    }

    public static OperationResponse processSetDatabaseOwners(SetDatabaseOwnersRequest request) {
        final var dbName = request.getDatabaseName();
        return OperationResponse.respondOrError(OperationType.SET_DATABASE_OWNERS,
                ErrorCode.ERROR_UPDATING_DATABASE_OWNERS, () -> {
                    if (cache.getAdminDbEntry(dbName) == null) {
                        return databaseNotFound(dbName);
                    }
                    return OperationLocks.withDatabaseShared(dbName, OperationType.SET_DATABASE_OWNERS,
                            ErrorCode.ERROR_UPDATING_DATABASE_OWNERS, () -> AdminOperationHelper
                                    .withUsersLock(() -> updateOwnersOfExistingUsers(dbName, request.getOwners())));
                });
    }

    private static OperationResponse updateOwnersUnderDatabaseBarrier(String dbName, List<String> owners)
            throws IOException, InterruptedException {
        if (!AdminOperationHelper.updateDatabaseOwners(dbName, owners)) {
            return databaseNotFound(dbName);
        }
        return OperationResponse.ok(OperationType.SET_DATABASE_OWNERS, "Database owners updated successfully");
    }

    private static OperationResponse updateOwnersOfExistingUsers(String dbName, List<String> owners)
            throws IOException, InterruptedException {
        if (cache.getAdminDbEntry(dbName) == null) {
            return databaseNotFound(dbName);
        }
        final var missing = owners.stream().filter(owner -> cache.getAdminUserEntry(owner) == null).findFirst();
        if (missing.isPresent()) {
            return new OperationResponse(OperationType.SET_DATABASE_OWNERS,
                    "user '" + missing.get() + "' does not exist", ErrorCode.USER_NOT_FOUND);
        }
        return updateOwnersUnderDatabaseBarrier(dbName, owners);
    }

    private static OperationResponse databaseNotFound(String dbName) {
        return new OperationResponse(OperationType.SET_DATABASE_OWNERS, "Database '" + dbName + "' not found",
                ErrorCode.DATABASE_NOT_FOUND);
    }

    public static OperationResponse processDropDatabaseOperation(DropDatabaseRequest dropDatabaseRequest) {
        final var dbName = dropDatabaseRequest.getDatabaseName();
        final var dropped = dropDatabase(dbName, false, RewriteVersion.RESTAMP);
        if (dropped.getStatus() == OperationStatus.OK) {
            return OperationResponse.respondOrError(OperationType.DROP_DATABASE, ErrorCode.ERROR_DROPPING_DATABASE,
                    () -> {
                        AdminStamp.tombstone(AdminRecordKey.database(dbName));
                        return dropped;
                    });
        }
        return dropped;
    }

    public static OperationResponse dropDatabase(String dbName, boolean bounded, RewriteVersion grantVersion) {
        final var deadline = System.currentTimeMillis() + OperationLocks.lockBudgetMillis(bounded);
        var barrierHeld = false;
        try {
            if (!locks.tryLockDatabaseExclusive(dbName, remainingUntil(deadline))) {
                return new OperationResponse(OperationType.DROP_DATABASE, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
            }
            barrierHeld = true;
            final var dbEntry = cache.getAdminDbEntry(dbName);
            if (dbEntry == null) {
                return new OperationResponse(OperationType.DROP_DATABASE, ErrorCode.ERROR_DROPPING_DATABASE);
            }
            return locks.withRetiringCollectionLocks(dbName, dbEntry.getCollections(), remainingUntil(deadline),
                    () -> deleteDatabaseUnderLocks(dbName, grantVersion));
        } catch (CollectionBusyException busy) {
            return new OperationResponse(OperationType.DROP_DATABASE, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return dropFailed(exception);
        } catch (Exception exception) {
            return dropFailed(exception);
        } finally {
            if (barrierHeld) {
                locks.releaseDatabaseExclusive(dbName);
                locks.removeDatabaseLock(dbName);
            }
        }
    }

    private static OperationResponse deleteDatabaseUnderLocks(String dbName, RewriteVersion grantVersion)
            throws Exception {
        if (!fs.deleteDatabase(dbName)) {
            return new OperationResponse(OperationType.DROP_DATABASE, ErrorCode.ERROR_DROPPING_DATABASE);
        }
        cache.evictDatabase(dbName);
        pendingIndexWrites.clearDatabase(dbName);
        AdminOperationHelper.deleteDatabaseEntry(dbName);
        compiledProcedures.invalidateDatabase(dbName);
        scheduleRegistry.removeDatabase(dbName);
        listenManager.endAllForDatabase(dbName, ListenManager.DATABASE_DROPPED);
        triggerExecutor.discardQueuedFor(dbName, null);
        TriggerRunLog.discardAllFor(dbName, null);
        GrantPruner.forDroppedDatabase(dbName, grantVersion);
        return OperationResponse.ok(OperationType.DROP_DATABASE, "Database dropped successfully");
    }

    private static OperationResponse dropFailed(Exception exception) {
        logger.error(OperationType.DROP_DATABASE + " failed with " + ErrorCode.ERROR_DROPPING_DATABASE.getCode(),
                exception);
        return new OperationResponse(OperationType.DROP_DATABASE, ErrorCode.ERROR_DROPPING_DATABASE);
    }

    private static long remainingUntil(long deadline) {
        return Math.max(0, deadline - System.currentTimeMillis());
    }

    public static OperationResponse processListDatabasesOperation(String actingUser) {
        return OperationResponse.respondOrError(OperationType.LIST_DATABASES, ErrorCode.ERROR_LISTING_DATABASES, () -> {
            final var user = actingUser == null ? null : cache.getAdminUserEntry(actingUser);
            final var names = cache.getUserDatabaseNames().stream()
                    .filter(name -> DatabaseVisibility.isVisible(user, cache.getAdminDbEntry(name))).toList();
            return new ListDatabasesResponse("Ok", names);
        });
    }
}
