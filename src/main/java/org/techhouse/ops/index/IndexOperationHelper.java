package org.techhouse.ops.index;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ReindexResponse;

// CREATE_INDEX / DROP_INDEX / REINDEX. Index files are built and the field registered in admin
// metadata under one held collection lock, which is what stops a save landing mid-build from being
// skipped as "not a known index" and then cleared from the pending overlay.
public final class IndexOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private IndexOperationHelper() {
    }

    public static OperationResponse processCreateIndex(CreateIndexRequest createIndexRequest) {
        final var dbName = createIndexRequest.getDatabaseName();
        final var collName = createIndexRequest.getCollectionName();
        final var fieldName = createIndexRequest.getFieldName();
        try {
            // Hold the collection write lock so no save can commit a document between the index build
            // and its registration. Building the index files and registering the field as a known
            // index happen atomically here (synchronously), so any concurrent save is serialized: it
            // either commits before (and is captured by the whole-collection read) or after (and its
            // background index update sees the field already registered and indexes it).
            locks.lock(dbName, collName);
            IndexHelper.createIndex(dbName, collName, fieldName);
            AdminOperationHelper.saveNewIndex(dbName, collName, fieldName);
            return OperationResponse.ok(OperationType.CREATE_INDEX, "Created index for field: " + fieldName);
        } catch (Exception e) {
            return new OperationResponse(OperationType.CREATE_INDEX, ErrorCode.ERROR_CREATING_INDEX);
        } finally {
            locks.release(dbName, collName);
        }
    }

    public static OperationResponse processDropIndex(DropIndexRequest dropIndexRequest) {
        final var dbName = dropIndexRequest.getDatabaseName();
        final var collName = dropIndexRequest.getCollectionName();
        final var fieldName = dropIndexRequest.getFieldName();
        try {
            // Hold the collection write lock so the index files are deleted and the field is
            // unregistered atomically with respect to saves and the background indexer. Unregister
            // first so no read or background index update can use the field after this returns.
            locks.lock(dbName, collName);
            final var result = IndexHelper.dropIndex(dbName, collName, fieldName);
            if (result) {
                AdminOperationHelper.deleteIndex(dbName, collName, fieldName);
                return OperationResponse.ok(OperationType.DROP_INDEX, "Successfully dropped index: " + fieldName);
            } else {
                return new OperationResponse(OperationType.DROP_INDEX, ErrorCode.INDEX_NOT_FOUND, fieldName);
            }
        } catch (Exception e) {
            return new OperationResponse(OperationType.DROP_INDEX, ErrorCode.ERROR_DROPPING_INDEX);
        } finally {
            locks.release(dbName, collName);
        }
    }

    public static OperationResponse processReindex(ReindexRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        try {
            // Hold the collection write lock for the entire rebuild so no concurrent save can commit
            // between field rebuilds. Admin metadata is not changed — the indexes already exist.
            // If createIndex throws mid-loop (e.g. disk full), the catch returns ERROR; the operator
            // can retry once the underlying issue is resolved.
            locks.lock(dbName, collName);
            final var registeredIndexes = cache.getIndexesForCollection(dbName, collName);
            final List<String> targets;
            if (request.getFieldNames().isEmpty()) {
                targets = new ArrayList<>(registeredIndexes);
            } else {
                for (var fieldName : request.getFieldNames()) {
                    if (!registeredIndexes.contains(fieldName)) {
                        return new OperationResponse(OperationType.REINDEX, ErrorCode.INDEX_NOT_FOUND, fieldName);
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
        } catch (Exception e) {
            return new OperationResponse(OperationType.REINDEX, ErrorCode.ERROR_REINDEXING);
        } finally {
            locks.release(dbName, collName);
        }
    }
}
