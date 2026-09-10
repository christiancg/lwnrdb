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

// The collection-level DDL handlers.
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
                    final var result = fs.createCollectionFile(dbName, collName);
                    if (result) {
                        // Register the collection's admin metadata (page collections + admin entry with its PK
                        // index entry) synchronously, so a subsequent CREATE_INDEX/SAVE observes it immediately.
                        // Doing it here rather than in a background task closes a race where the registration
                        // lagged, letting CREATE_INDEX run first, find no admin PK entry
                        // (getPkIndexAdminCollEntry == null) and silently skip registering the index while still
                        // returning OK, leaving the index built but unregistered. Collection creation and
                        // deletion are both fully synchronous.
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
                // Remove the collection's admin metadata synchronously (mirroring synchronous creation).
                // Doing this in the background previously left the admin entry briefly present after the
                // drop returned OK, so an immediate CREATE_COLLECTION of the same name saw the stale entry,
                // skipped registration, and was then unregistered when the queued delete event ran.
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
