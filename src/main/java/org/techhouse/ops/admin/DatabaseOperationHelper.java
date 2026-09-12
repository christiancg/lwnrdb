package org.techhouse.ops.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
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

    private DatabaseOperationHelper() {
    }

    public static OperationResponse processCreateDatabaseOperation(CreateDatabaseRequest createDatabaseRequest,
            UUID clientId) {
        return OperationResponse.respondOrError(OperationType.CREATE_DATABASE, ErrorCode.ERROR_CREATING_DATABASE,
                () -> {
                    final var dbName = createDatabaseRequest.getDatabaseName();
                    // createDatabaseFolder returns true for an already-present folder, so without this guard
                    // a duplicate CREATE_DATABASE would overwrite the existing admin entry and report success.
                    if (cache.getAdminDbEntry(dbName) != null) {
                        return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.DATABASE_ALREADY_EXISTS);
                    }
                    final var result = fs.createDatabaseFolder(dbName);
                    if (result) {
                        final var username = clientTracker.getAuthenticatedUsername(clientId);
                        final var owners = username != null ? List.of(username) : List.<String>of();
                        final var newEntry = new AdminDbEntry(dbName, new java.util.ArrayList<>(),
                                new java.util.ArrayList<>(owners));
                        AdminOperationHelper.saveDatabaseEntry(newEntry);
                        return OperationResponse.ok(OperationType.CREATE_DATABASE, "Database created successfully");
                    }
                    return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.DATABASE_ALREADY_EXISTS);
                });
    }

    public static OperationResponse processSetDatabaseOwners(SetDatabaseOwnersRequest request) {
        return OperationResponse.respondOrError(OperationType.SET_DATABASE_OWNERS,
                ErrorCode.ERROR_UPDATING_DATABASE_OWNERS, () -> {
                    final var dbName = request.getDatabaseName();
                    if (cache.getAdminDbEntry(dbName) == null) {
                        return new OperationResponse(OperationType.SET_DATABASE_OWNERS,
                                "Database '" + dbName + "' not found", ErrorCode.DATABASE_NOT_FOUND);
                    }
                    AdminOperationHelper.updateDatabaseOwners(dbName, request.getOwners());
                    return OperationResponse.ok(OperationType.SET_DATABASE_OWNERS,
                            "Database owners updated successfully");
                });
    }

    public static OperationResponse processDropDatabaseOperation(DropDatabaseRequest dropDatabaseRequest) {
        final var dbName = dropDatabaseRequest.getDatabaseName();
        // Lock every collection in a stable order (deadlock avoidance with other multi-collection
        // acquisitions) so nothing races the file deletion and cache eviction below.
        final var dbEntry = cache.getAdminDbEntry(dbName);
        final var collNames = dbEntry != null ? new ArrayList<>(dbEntry.getCollections()) : new ArrayList<String>();
        Collections.sort(collNames);
        final var lockedColls = new ArrayList<String>();
        try {
            for (final var collName : collNames) {
                locks.lock(dbName, collName);
                lockedColls.add(collName);
            }
            final var result = fs.deleteDatabase(dbName);
            if (result) {
                cache.evictDatabase(dbName);
                for (final var collName : lockedColls) {
                    locks.removeLock(dbName, collName);
                }
                // Synchronous, mirroring creation: a background delete lets an immediate re-CREATE hit the
                // duplicate guard and wrongly return DATABASE_ALREADY_EXISTS.
                AdminOperationHelper.deleteDatabaseEntry(dbName);
                // A re-created database restarts its procedure versions at 1, so compiled programs go too.
                compiledProcedures.invalidateDatabase(dbName);
                scheduleRegistry.removeDatabase(dbName);
                listenManager.unregisterAllForDatabase(dbName);
                return OperationResponse.ok(OperationType.DROP_DATABASE, "Database dropped successfully");
            }
            return new OperationResponse(OperationType.DROP_DATABASE, ErrorCode.ERROR_DROPPING_DATABASE);
        } catch (Exception exception) {
            logger.error(OperationType.DROP_DATABASE + " failed with " + ErrorCode.ERROR_DROPPING_DATABASE.getCode(),
                    exception);
            return new OperationResponse(OperationType.DROP_DATABASE, ErrorCode.ERROR_DROPPING_DATABASE);
        } finally {
            for (final var collName : lockedColls) {
                locks.release(dbName, collName);
            }
        }
    }

    public static OperationResponse processListDatabasesOperation() {
        return OperationResponse.respondOrError(OperationType.LIST_DATABASES, ErrorCode.ERROR_LISTING_DATABASES, () -> {
            final var names = cache.getUserDatabaseNames();
            return new ListDatabasesResponse("Ok", names);
        });
    }
}
