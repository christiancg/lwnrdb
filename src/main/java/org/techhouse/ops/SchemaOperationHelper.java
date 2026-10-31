package org.techhouse.ops;

import java.io.IOException;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.MetadataReadException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminStamp;
import org.techhouse.ops.admin.StoredDefinitions;
import org.techhouse.ops.req.DeleteSchemaRequest;
import org.techhouse.ops.req.GetSchemaRequest;
import org.techhouse.ops.req.SaveSchemaRequest;
import org.techhouse.ops.resp.GetSchemaResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveSchemaResponse;

// Save and delete callers hold the collection write lock over the file write and cache update; get, its read lock.
public final class SchemaOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private SchemaOperationHelper() {
    }

    public static OperationResponse executeSaveSchema(SaveSchemaRequest request) throws IOException {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (cache.getAdminCollectionEntry(dbName, collName) == null) {
            return new OperationResponse(OperationType.SAVE_SCHEMA, "Collection '" + collName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        final var schema = request.getSchema();
        final var capViolation = SchemaPatternCaps.violationOf(schema);
        if (capViolation != null) {
            return new OperationResponse(OperationType.SAVE_SCHEMA, capViolation.message(), capViolation.code());
        }
        final var validation = eJson.validateSchema(schema);
        if (!validation.isValid()) {
            return new OperationResponse(OperationType.SAVE_SCHEMA,
                    ErrorCode.INVALID_SCHEMA.getDefaultMessage() + ": " + String.join("; ", validation.getErrors()),
                    ErrorCode.INVALID_SCHEMA);
        }
        if (eJson.schemaExpandedNodeCount(schema,
                Globals.MAX_SCHEMA_EXPANDED_NODES) > Globals.MAX_SCHEMA_EXPANDED_NODES) {
            return new OperationResponse(OperationType.SAVE_SCHEMA,
                    ErrorCode.SCHEMA_TOO_COMPLEX.getDefaultMessage() + ": it expands to more than "
                            + Globals.MAX_SCHEMA_EXPANDED_NODES + " schema nodes once its references are followed",
                    ErrorCode.SCHEMA_TOO_COMPLEX);
        }
        StoredDefinitions.writeSchema(dbName, collName, schema);
        cache.putCollectionSchema(dbName, collName, schema);
        return new SaveSchemaResponse("Collection schema saved successfully", validation.getWarnings());
    }

    public static OperationResponse executeDeleteSchema(DeleteSchemaRequest request) throws IOException {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (cache.getAdminCollectionEntry(dbName, collName) == null) {
            return new OperationResponse(OperationType.DELETE_SCHEMA, "Collection '" + collName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        if (fs.metadataFiles().deleteCollectionSchema(dbName, collName)) {
            AdminStamp.tombstone(AdminRecordKey.schema(dbName, collName));
        }
        cache.removeCollectionSchema(dbName, collName);
        return OperationResponse.ok(OperationType.DELETE_SCHEMA, "Collection schema deleted successfully");
    }

    public static OperationResponse processGetSchema(GetSchemaRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var collectionId = Cache.getCollectionIdentifier(dbName, collName);
        final var response = OperationLocks.withReadLocks(false, List.of(collectionId), OperationType.GET_SCHEMA,
                ErrorCode.ERROR_RETRIEVING, () -> executeGetSchema(request));
        if (ErrorCode.COLLECTION_NOT_FOUND.getCode().equals(response.getErrorCode())
                && cache.getAdminCollectionEntry(dbName, collName) == null) {
            locks.removeCollectionLock(dbName, collName);
        }
        return response;
    }

    private static OperationResponse executeGetSchema(GetSchemaRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (cache.getAdminCollectionEntry(dbName, collName) == null) {
            return new OperationResponse(OperationType.GET_SCHEMA, "Collection '" + collName + "' not found",
                    ErrorCode.COLLECTION_NOT_FOUND);
        }
        final JsonObject schema;
        try {
            schema = cache.getCollectionSchema(dbName, collName);
        } catch (MetadataReadException e) {
            return new OperationResponse(OperationType.GET_SCHEMA, ErrorCode.SCHEMA_UNAVAILABLE);
        }
        if (schema == null) {
            return new OperationResponse(OperationType.GET_SCHEMA, ErrorCode.SCHEMA_NOT_FOUND);
        }
        return new GetSchemaResponse("Ok", schema.deepCopy());
    }
}
