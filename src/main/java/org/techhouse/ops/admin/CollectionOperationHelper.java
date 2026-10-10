package org.techhouse.ops.admin;

import java.util.List;
import org.techhouse.bckg_ops.PendingIndexWrites;
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
import org.techhouse.ops.OnDiskNameRegistry;
import org.techhouse.ops.OperationLocks;
import org.techhouse.ops.OperationStatus;
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
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final org.techhouse.cluster.HybridClock hybridClock = IocContainer
            .get(org.techhouse.cluster.HybridClock.class);

    private CollectionOperationHelper() {
    }

    public static OperationResponse processCreateCollectionOperation(CreateCollectionRequest createCollectionRequest) {
        final var dbName = createCollectionRequest.getDatabaseName();
        return OperationLocks.withDatabaseShared(dbName, OperationType.CREATE_COLLECTION,
                ErrorCode.ERROR_CREATING_COLLECTION, () -> createUnderDatabaseBarrier(createCollectionRequest));
    }

    private static OperationResponse createUnderDatabaseBarrier(CreateCollectionRequest createCollectionRequest) {
        final var dbName = createCollectionRequest.getDatabaseName();
        final var collName = createCollectionRequest.getCollectionName();
        return OperationLocks.withCollectionLock(dbName, collName, OperationType.CREATE_COLLECTION,
                ErrorCode.ERROR_CREATING_COLLECTION, () -> {
                    if (cache.getAdminDbEntry(dbName) == null) {
                        return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.DATABASE_NOT_FOUND);
                    }
                    fs.createDatabaseFolder(dbName);
                    return registerUnderNameLock(createCollectionRequest);
                });
    }

    private static OperationResponse registerUnderNameLock(CreateCollectionRequest createCollectionRequest)
            throws Exception {
        final var dbName = createCollectionRequest.getDatabaseName();
        if (!locks.tryLockWrite(dbName, Globals.COLLECTION_NAMES_LOCK, OperationLocks.lockBudgetMillis(false))) {
            return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
        }
        try {
            final var colliding = OnDiskNameRegistry.collidingCollection(dbName,
                    createCollectionRequest.getCollectionName());
            if (colliding != null) {
                return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.NAME_COLLIDES_ON_DISK,
                        colliding);
            }
            return register(createCollectionRequest);
        } finally {
            locks.release(dbName, Globals.COLLECTION_NAMES_LOCK);
        }
    }

    private static OperationResponse register(CreateCollectionRequest createCollectionRequest) throws Exception {
        final var dbName = createCollectionRequest.getDatabaseName();
        final var collName = createCollectionRequest.getCollectionName();
        final var result = LeftoverFolders.moveAsideUnregisteredCollection(dbName, collName)
                && fs.createCollectionFile(dbName, collName);
        if (!result) {
            return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.ERROR_CREATING_COLLECTION);
        }
        if (AdminOperationHelper.getCollectionEntry(dbName, collName) != null) {
            return OperationResponse.ok(OperationType.CREATE_COLLECTION, "Collection created successfully");
        }
        AdminOperationHelper.createPageCollections(dbName, collName);
        final var entry = new AdminCollEntry(dbName, collName);
        entry.setIncarnation(hybridClock.next());
        AdminOperationHelper.saveCollectionEntry(entry);
        return OperationResponse.ok(OperationType.CREATE_COLLECTION, "Collection created successfully");
    }

    private static boolean isNotRegistered(String dbName, String collName) {
        return cache.getAdminCollectionEntry(dbName, collName) == null;
    }

    public static OperationResponse processDropCollectionOperation(DropCollectionRequest dropCollectionRequest) {
        final var dbName = dropCollectionRequest.getDatabaseName();
        final var collName = dropCollectionRequest.getCollectionName();
        final var dropped = dropCollection(dbName, collName, false);
        if (dropped.getStatus() == OperationStatus.OK) {
            return OperationResponse.respondOrError(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION,
                    () -> {
                        AdminTombstone.record(AdminRecordKey.collection(dbName, collName));
                        return dropped;
                    });
        }
        return dropped;
    }

    public static OperationResponse dropCollection(String dbName, String collName, boolean bounded) {
        final var lockBudget = OperationLocks.lockBudgetMillis(bounded);
        boolean dropSucceeded = false;
        boolean namesLocked = false;
        try {
            if (!locks.tryLockWrite(dbName, collName, lockBudget)) {
                return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
            }
            namesLocked = locks.tryLockWrite(dbName, Globals.COLLECTION_NAMES_LOCK, lockBudget);
            if (!namesLocked) {
                return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
            }
            if (isNotRegistered(dbName, collName)) {
                return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
            }
            final var result = fs.deleteCollectionFiles(dbName, collName);
            if (result) {
                cache.evictCollection(dbName, collName);
                pendingIndexWrites.clearCollection(dbName, collName);
                AdminOperationHelper.deleteCollectionEntry(dbName, collName);
                AdminOperationHelper.deletePageCollections(dbName, collName);
                listenManager.endAllForCollection(dbName, collName, ListenManager.COLLECTION_DROPPED);
                GrantPruner.forDroppedCollection(dbName, collName);
                dropSucceeded = true;
                return OperationResponse.ok(OperationType.DROP_COLLECTION, "Collection dropped successfully");
            }
            return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
        } catch (Exception e) {
            logger.error(
                    OperationType.DROP_COLLECTION + " failed with " + ErrorCode.ERROR_DROPPING_COLLECTION.getCode(), e);
            return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
        } finally {
            if (namesLocked) {
                locks.release(dbName, Globals.COLLECTION_NAMES_LOCK);
            }
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
