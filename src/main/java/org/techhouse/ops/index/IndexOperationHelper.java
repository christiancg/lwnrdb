package org.techhouse.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ex.PkRebuildRefusedException;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.PkRebuild;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CollectionReadinessGuard;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.OnDiskNameRegistry;
import org.techhouse.ops.OperationLocks;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.PrimaryKeyReindexResponse;
import org.techhouse.ops.resp.ReindexResponse;

// Index files are built and the field registered in admin metadata under one held collection write lock,
// which stops a save landing mid-build from being skipped as "not a known index".
public final class IndexOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private static final Configuration configuration = Configuration.getInstance();

    private IndexOperationHelper() {
    }

    public static OperationResponse processCreateIndex(CreateIndexRequest createIndexRequest) {
        final var dbName = createIndexRequest.getDatabaseName();
        final var collName = createIndexRequest.getCollectionName();
        final var fieldName = createIndexRequest.getFieldName();
        return OperationLocks.withCollectionLock(dbName, collName, OperationType.CREATE_INDEX,
                ErrorCode.ERROR_CREATING_INDEX, () -> {
                    final var readinessError = CollectionReadinessGuard.check(OperationType.CREATE_INDEX, dbName,
                            collName);
                    if (readinessError != null) {
                        return readinessError;
                    }
                    if (!cache.hasNoIndex(dbName, collName, fieldName)) {
                        return OperationResponse.ok(OperationType.CREATE_INDEX,
                                "Index already exists for field: " + fieldName);
                    }
                    final var colliding = OnDiskNameRegistry.collidingIndexField(dbName, collName, fieldName);
                    if (colliding != null) {
                        return new OperationResponse(OperationType.CREATE_INDEX, ErrorCode.NAME_COLLIDES_ON_DISK,
                                colliding);
                    }
                    final var maxIndexes = configuration.intValue(ConfigKey.MAX_INDEXES_PER_COLLECTION);
                    if (cache.getIndexesForCollection(dbName, collName).size() >= maxIndexes) {
                        return new OperationResponse(OperationType.CREATE_INDEX,
                                ErrorCode.TOO_MANY_INDEXES_FOR_COLLECTION.getDefaultMessage() + " (" + maxIndexes + ")",
                                ErrorCode.TOO_MANY_INDEXES_FOR_COLLECTION);
                    }
                    fs.indexMarkers().mark(dbName, collName, fieldName);
                    IndexHelper.createIndex(dbName, collName, fieldName);
                    AdminOperationHelper.saveNewIndex(dbName, collName, fieldName);
                    fs.indexMarkers().clear(dbName, collName, fieldName);
                    return OperationResponse.ok(OperationType.CREATE_INDEX, "Created index for field: " + fieldName);
                });
    }

    public static OperationResponse processDropIndex(DropIndexRequest dropIndexRequest) {
        final var dbName = dropIndexRequest.getDatabaseName();
        final var collName = dropIndexRequest.getCollectionName();
        final var fieldName = dropIndexRequest.getFieldName();
        // The collection write lock makes the file deletion and the unregistration atomic with respect to
        // saves and the background indexer.
        return OperationLocks.withCollectionLock(dbName, collName, OperationType.DROP_INDEX,
                ErrorCode.ERROR_DROPPING_INDEX, () -> {
                    if (cache.getIndexesForCollection(dbName, collName).contains(fieldName)) {
                        fs.indexMarkers().mark(dbName, collName, fieldName);
                    }
                    final var result = IndexHelper.dropIndex(dbName, collName, fieldName);
                    if (result) {
                        AdminOperationHelper.deleteIndex(dbName, collName, fieldName);
                        fs.indexMarkers().clear(dbName, collName, fieldName);
                        return OperationResponse.ok(OperationType.DROP_INDEX,
                                "Successfully dropped index: " + fieldName);
                    } else {
                        return new OperationResponse(OperationType.DROP_INDEX, ErrorCode.INDEX_NOT_FOUND, fieldName);
                    }
                });
    }

    public static OperationResponse processReindex(ReindexRequest request) {
        return processReindex(request, false);
    }

    public static OperationResponse processReindex(ReindexRequest request, boolean bounded) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        return OperationLocks.withCollectionLock(dbName, collName, OperationType.REINDEX, ErrorCode.ERROR_REINDEXING,
                bounded, () -> {
                    final var registeredIndexes = cache.getIndexesForCollection(dbName, collName);
                    for (var fieldName : request.getFieldNames()) {
                        if (!registeredIndexes.contains(fieldName)) {
                            return new OperationResponse(OperationType.REINDEX, ErrorCode.INDEX_NOT_FOUND, fieldName);
                        }
                    }
                    if (request.isPrimaryKey()) {
                        return reindexWithPrimaryKey(request, registeredIndexes);
                    }
                    final var targets = fieldTargets(request, registeredIndexes, null);
                    rebuildFieldIndexes(dbName, collName, targets, registeredIndexes);
                    if (targets.isEmpty()) {
                        return new ReindexResponse("No indexes to rebuild", List.of());
                    }
                    return new ReindexResponse("Rebuilt " + targets.size() + " index(es)", targets);
                });
    }

    private static OperationResponse reindexWithPrimaryKey(ReindexRequest request, Set<String> registeredIndexes)
            throws Exception {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final PkRebuild rebuild;
        try {
            rebuild = fs.planPkRebuild(dbName, collName);
        } catch (PkRebuildRefusedException refused) {
            return new OperationResponse(OperationType.REINDEX, ErrorCode.PRIMARY_KEY_REBUILD_REFUSED,
                    refused.getMessage());
        }
        final var targets = fieldTargets(request, registeredIndexes, rebuild);
        recordFieldIndexWork(dbName, collName, rebuild, targets);
        fs.applyPkRebuild(dbName, collName, rebuild);
        faultInjector.at(FaultPoint.PK_INDEX_REPLACED, Cache.getCollectionIdentifier(dbName, collName));
        cache.userCache().evictPkIndex(dbName, collName);
        cache.userCache().evictCollectionDocuments(dbName, collName);
        PageOccupancyReconciler.rebuildRows(dbName, collName);
        rebuildFieldIndexes(dbName, collName, targets, registeredIndexes);
        final var rebuiltFields = targets.isEmpty() ? "" : " and " + targets.size() + " index(es)";
        return new PrimaryKeyReindexResponse(
                "Rebuilt the primary-key index (" + rebuild.entries() + " entries)" + rebuiltFields, targets, rebuild);
    }

    private static void recordFieldIndexWork(String dbName, String collName, PkRebuild rebuild, List<String> targets)
            throws IOException {
        for (var fieldName : targets) {
            fs.indexMarkers().mark(dbName, collName, fieldName);
        }
        if (!rebuild.previousReadable()) {
            return;
        }
        if (!rebuild.appeared().isEmpty()) {
            PageOccupancyReconciler.scheduleIndexMaintenanceFor(dbName, collName, rebuild.appeared());
        }
        PageOccupancyReconciler.scheduleIndexCleanupFor(rebuild.vanished());
    }

    private static List<String> fieldTargets(ReindexRequest request, Set<String> registeredIndexes, PkRebuild rebuild) {
        final var everyIndex = new ArrayList<>(registeredIndexes);
        if (rebuild == null) {
            return request.getFieldNames().isEmpty() ? everyIndex : request.getFieldNames();
        }
        return rebuild.previousReadable() ? request.getFieldNames() : everyIndex;
    }

    private static void rebuildFieldIndexes(String dbName, String collName, List<String> targets,
            Set<String> registeredIndexes) throws IOException, InterruptedException {
        for (var fieldName : targets) {
            fs.indexMarkers().mark(dbName, collName, fieldName);
        }
        for (var fieldName : targets) {
            IndexHelper.dropIndex(dbName, collName, fieldName);
        }
        IndexBuild.buildAll(dbName, collName, targets);
        for (var fieldName : targets) {
            fs.indexMarkers().clear(dbName, collName, fieldName);
        }
        clearDirtyMarkerIfFullyRebuilt(dbName, collName, targets, registeredIndexes);
    }

    private static void clearDirtyMarkerIfFullyRebuilt(String dbName, String collName, List<String> rebuiltFields,
            Set<String> registeredIndexes) {
        if (Set.copyOf(rebuiltFields).containsAll(registeredIndexes)) {
            pendingIndexWrites.clearCollection(dbName, collName);
            fs.indexMarkers().retireDirty(dbName, collName);
            fs.indexMarkers().clearUncleanStop(dbName, collName);
        }
    }
}
