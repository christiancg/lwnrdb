package org.techhouse.ops.admin;

import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationLocks;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.ListCollectionsRequest;
import org.techhouse.ops.resp.ListCollectionsResponse;
import org.techhouse.ops.resp.OperationResponse;

public final class CollectionOperationHelper {
    private static final Logger logger = Logger.logFor(CollectionOperationHelper.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);

    private CollectionOperationHelper() {
    }

    public static OperationResponse processCreateCollectionOperation(CreateCollectionRequest createCollectionRequest) {
        return OperationResponse.respondOrError(OperationType.CREATE_COLLECTION, ErrorCode.ERROR_CREATING_COLLECTION,
                () -> {
                    final var dbName = createCollectionRequest.getDatabaseName();
                    final var collName = createCollectionRequest.getCollectionName();
                    // A node can hold the database's admin entry without its folder (a replicated
                    // CREATE_DATABASE returns early), and createCollectionFile only mkdirs one level.
                    if (cache.getAdminDbEntry(dbName) != null) {
                        fs.createDatabaseFolder(dbName);
                    }
                    final var result = fs.createCollectionFile(dbName, collName);
                    if (result) {
                        // Registration must be synchronous: a lagging background task lets CREATE_INDEX run
                        // first, find no admin PK entry and silently skip registering the index.
                        if (AdminOperationHelper.getCollectionEntry(dbName, collName) == null) {
                            AdminOperationHelper.createPageCollections(dbName, collName);
                            AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(dbName, collName));
                        }
                        return OperationResponse.ok(OperationType.CREATE_COLLECTION, "Collection created successfully");
                    }
                    return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.ERROR_CREATING_COLLECTION);
                });
    }

    public static OperationResponse processDropCollectionOperation(DropCollectionRequest dropCollectionRequest) {
        final var dbName = dropCollectionRequest.getDatabaseName();
        final var collName = dropCollectionRequest.getCollectionName();
        boolean dropSucceeded = false;
        try {
            locks.lock(dbName, collName);
            final var result = fs.deleteCollectionFiles(dbName, collName);
            if (result) {
                cache.evictCollection(dbName, collName);
                // Synchronous, mirroring creation: a background delete leaves the admin entry briefly
                // present, so an immediate re-CREATE sees it stale and skips registration.
                AdminOperationHelper.deleteCollectionEntry(dbName, collName);
                AdminOperationHelper.deletePageCollections(dbName, collName);
                listenManager.unregisterAllForCollection(dbName, collName);
                dropSucceeded = true;
                return OperationResponse.ok(OperationType.DROP_COLLECTION, "Collection dropped successfully");
            }
            return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
        } catch (Exception e) {
            logger.error(
                    OperationType.DROP_COLLECTION + " failed with " + ErrorCode.ERROR_DROPPING_COLLECTION.getCode(), e);
            return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
        } finally {
            locks.release(dbName, collName);
            if (dropSucceeded) {
                locks.removeLock(dbName, collName);
            }
        }
    }

    public static OperationResponse processListCollectionsOperation(ListCollectionsRequest request) {
        final var dbName = request.getDatabaseName();
        if (dbName == null || dbName.isBlank()) {
            return new OperationResponse(OperationType.LIST_COLLECTIONS, "Database name is required",
                    ErrorCode.VALIDATION_ERROR);
        }
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return new ListCollectionsResponse("Ok", List.of());
        }
        final var lockSet = List
                .of(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME));
        return OperationLocks.withReadLocks(request.isDirtyRead(), lockSet, OperationType.LIST_COLLECTIONS,
                ErrorCode.ERROR_LISTING_COLLECTIONS, () -> {
                    if (cache.getAdminDbEntry(dbName) == null) {
                        return new OperationResponse(OperationType.LIST_COLLECTIONS,
                                "Database " + dbName + " not found", ErrorCode.DATABASE_NOT_FOUND);
                    }
                    return new ListCollectionsResponse("Ok", cache.getCollectionNamesForDatabase(dbName));
                });
    }
}
