package org.techhouse.ops.index;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
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
import org.techhouse.ops.resp.OperationResponse;

final class IndexCreation {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Configuration configuration = Configuration.getInstance();

    private IndexCreation() {
    }

    static OperationResponse process(CreateIndexRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var targets = request.targetFieldNames();
        return OperationLocks.withCollectionLock(dbName, collName, OperationType.CREATE_INDEX,
                ErrorCode.ERROR_CREATING_INDEX, () -> {
                    final var readinessError = CollectionReadinessGuard.check(OperationType.CREATE_INDEX, dbName,
                            collName);
                    if (readinessError != null) {
                        return readinessError;
                    }
                    final var missing = new ArrayList<String>();
                    for (final var fieldName : targets) {
                        if (cache.hasNoIndex(dbName, collName, fieldName)) {
                            missing.add(fieldName);
                        }
                    }
                    if (missing.isEmpty()) {
                        return OperationResponse.ok(OperationType.CREATE_INDEX,
                                "Index already exists for " + fieldsLabel(targets));
                    }
                    final var refusal = refusalOf(dbName, collName, missing);
                    if (refusal != null) {
                        return refusal;
                    }
                    for (final var fieldName : missing) {
                        fs.indexMarkers().mark(dbName, collName, fieldName);
                    }
                    IndexHelper.createIndexes(dbName, collName, missing);
                    for (final var fieldName : missing) {
                        AdminOperationHelper.saveNewIndex(dbName, collName, fieldName);
                        fs.indexMarkers().clear(dbName, collName, fieldName);
                    }
                    return OperationResponse.ok(OperationType.CREATE_INDEX, createdMessage(targets, missing));
                });
    }

    private static OperationResponse refusalOf(String dbName, String collName, List<String> missing) {
        for (var i = 0; i < missing.size(); i++) {
            final var fieldName = missing.get(i);
            var colliding = OnDiskNameRegistry.collidingIndexField(dbName, collName, fieldName);
            if (colliding == null) {
                colliding = OnDiskNameRegistry.collidingDefinition(missing.subList(0, i), fieldName);
            }
            if (colliding != null) {
                return new OperationResponse(OperationType.CREATE_INDEX, ErrorCode.NAME_COLLIDES_ON_DISK, colliding);
            }
        }
        final var maxIndexes = configuration.intValue(ConfigKey.MAX_INDEXES_PER_COLLECTION);
        if (cache.getIndexesForCollection(dbName, collName).size() + missing.size() > maxIndexes) {
            return new OperationResponse(OperationType.CREATE_INDEX,
                    ErrorCode.TOO_MANY_INDEXES_FOR_COLLECTION.getDefaultMessage() + " (" + maxIndexes + ")",
                    ErrorCode.TOO_MANY_INDEXES_FOR_COLLECTION);
        }
        return null;
    }

    private static String createdMessage(List<String> targets, List<String> missing) {
        final var created = "Created index for " + fieldsLabel(missing);
        if (missing.size() == targets.size()) {
            return created;
        }
        final var existing = new ArrayList<>(targets);
        existing.removeAll(missing);
        return created + "; already existed for " + fieldsLabel(existing);
    }

    private static String fieldsLabel(List<String> fieldNames) {
        return fieldNames.size() == 1 ? "field: " + fieldNames.getFirst() : "fields: " + String.join(", ", fieldNames);
    }
}
