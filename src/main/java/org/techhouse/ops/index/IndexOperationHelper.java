package org.techhouse.ops.index;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.IndexHelper;
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

    private IndexOperationHelper() {
    }

    public static OperationResponse processCreateIndex(CreateIndexRequest createIndexRequest) {
        final var dbName = createIndexRequest.getDatabaseName();
        final var collName = createIndexRequest.getCollectionName();
        final var fieldName = createIndexRequest.getFieldName();
        return OperationLocks.withCollectionLock(dbName, collName, OperationType.CREATE_INDEX,
                ErrorCode.ERROR_CREATING_INDEX, () -> {
                    IndexHelper.createIndex(dbName, collName, fieldName);
                    AdminOperationHelper.saveNewIndex(dbName, collName, fieldName);
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
                    final var result = IndexHelper.dropIndex(dbName, collName, fieldName);
                    if (result) {
                        AdminOperationHelper.deleteIndex(dbName, collName, fieldName);
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
                () -> {
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
                    if (targets.isEmpty()) {
                        return new ReindexResponse("No indexes to rebuild", List.of());
                    }
                    for (var fieldName : targets) {
                        IndexHelper.dropIndex(dbName, collName, fieldName);
                        IndexHelper.createIndex(dbName, collName, fieldName);
                    }
                    return new ReindexResponse("Rebuilt " + targets.size() + " index(es)", targets);
                });
    }
}
