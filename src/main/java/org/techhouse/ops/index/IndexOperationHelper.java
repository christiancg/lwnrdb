package org.techhouse.ops.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CollectionReadinessGuard;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.OnDiskNameRegistry;
import org.techhouse.ops.OperationLocks;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ReindexResponse;

// Index files are built and the field registered in admin metadata under one held collection write lock,
// which stops a save landing mid-build from being skipped as "not a known index".
public final class IndexOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    private IndexOperationHelper() {
    }

    public static OperationResponse processCreateIndex(CreateIndexRequest createIndexRequest) {
        final var dbName = createIndexRequest.getDatabaseName();
        final var collName = createIndexRequest.getCollectionName();
        final var fieldName = createIndexRequest.getFieldName();
        return OperationLocks.withCollectionLock(dbName, collName, OperationType.CREATE_INDEX,
                ErrorCode.ERROR_CREATING_INDEX, createIndexRequest.isReplicated(), () -> {
                    if (!createIndexRequest.isReplicated()) {
                        final var readinessError = CollectionReadinessGuard.check(OperationType.CREATE_INDEX, dbName,
                                collName);
                        if (readinessError != null) {
                            return readinessError;
                        }
                    }
                    if (!cache.hasNoIndex(dbName, collName, fieldName)) {
                        return OperationResponse.ok(OperationType.CREATE_INDEX,
                                "Index already exists for field: " + fieldName);
                    }
                    final var colliding = createIndexRequest.isReplicated()
                            ? null
                            : OnDiskNameRegistry.collidingIndexField(dbName, collName, fieldName);
                    if (colliding != null) {
                        return new OperationResponse(OperationType.CREATE_INDEX, ErrorCode.NAME_COLLIDES_ON_DISK,
                                colliding);
                    }
                    fs.indexBuildMarkers().mark(dbName, collName, fieldName);
                    IndexHelper.createIndex(dbName, collName, fieldName);
                    AdminOperationHelper.saveNewIndex(dbName, collName, fieldName);
                    fs.indexBuildMarkers().clear(dbName, collName, fieldName);
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
                ErrorCode.ERROR_DROPPING_INDEX, dropIndexRequest.isReplicated(), () -> {
                    if (cache.getIndexesForCollection(dbName, collName).contains(fieldName)) {
                        fs.indexBuildMarkers().mark(dbName, collName, fieldName);
                    }
                    final var result = IndexHelper.dropIndex(dbName, collName, fieldName);
                    if (result) {
                        AdminOperationHelper.deleteIndex(dbName, collName, fieldName);
                        fs.indexBuildMarkers().clear(dbName, collName, fieldName);
                        return OperationResponse.ok(OperationType.DROP_INDEX,
                                "Successfully dropped index: " + fieldName);
                    } else {
                        return new OperationResponse(OperationType.DROP_INDEX, ErrorCode.INDEX_NOT_FOUND, fieldName);
                    }
                });
    }

    public static OperationResponse processReindex(ReindexRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        return OperationLocks.withCollectionLock(dbName, collName, OperationType.REINDEX, ErrorCode.ERROR_REINDEXING,
                request.isReplicated(), () -> {
                    final var registeredIndexes = cache.getIndexesForCollection(dbName, collName);
                    final List<String> targets;
                    if (request.getFieldNames().isEmpty()) {
                        targets = new ArrayList<>(registeredIndexes);
                    } else {
                        for (var fieldName : request.getFieldNames()) {
                            if (!registeredIndexes.contains(fieldName)) {
                                return new OperationResponse(OperationType.REINDEX, ErrorCode.INDEX_NOT_FOUND,
                                        fieldName);
                            }
                        }
                        targets = request.getFieldNames();
                    }
                    for (var fieldName : targets) {
                        fs.indexBuildMarkers().mark(dbName, collName, fieldName);
                        IndexHelper.dropIndex(dbName, collName, fieldName);
                        IndexHelper.createIndex(dbName, collName, fieldName);
                        fs.indexBuildMarkers().clear(dbName, collName, fieldName);
                    }
                    clearDirtyMarkerIfFullyRebuilt(dbName, collName, targets, registeredIndexes);
                    if (targets.isEmpty()) {
                        return new ReindexResponse("No indexes to rebuild", List.of());
                    }
                    return new ReindexResponse("Rebuilt " + targets.size() + " index(es)", targets);
                });
    }

    private static void clearDirtyMarkerIfFullyRebuilt(String dbName, String collName, List<String> rebuiltFields,
            Set<String> registeredIndexes) {
        if (Set.copyOf(rebuiltFields).containsAll(registeredIndexes)) {
            pendingIndexWrites.clearCollection(dbName, collName);
            fs.clearIndexesDirty(dbName, collName);
            fs.retireUncleanStopMarker(dbName, collName);
        }
    }
}
