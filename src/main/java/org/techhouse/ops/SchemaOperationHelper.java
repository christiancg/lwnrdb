package org.techhouse.ops;

import java.io.IOException;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminTombstone;
import org.techhouse.ops.admin.StoredDefinitions;
import org.techhouse.ops.req.DeleteSchemaRequest;
import org.techhouse.ops.req.SaveSchemaRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveSchemaResponse;

// Callers hold the collection write lock, serializing the schema file write, cache update and any save.
public final class SchemaOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EJson eJson = IocContainer.get(EJson.class);

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
        final var validation = eJson.validateSchema(schema);
        if (!validation.isValid()) {
            return new OperationResponse(OperationType.SAVE_SCHEMA,
                    ErrorCode.INVALID_SCHEMA.getDefaultMessage() + ": " + String.join("; ", validation.getErrors()),
                    ErrorCode.INVALID_SCHEMA);
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
        if (fs.deleteCollectionSchema(dbName, collName)) {
            AdminTombstone.record(AdminRecordKey.schema(dbName, collName));
        }
        cache.removeCollectionSchema(dbName, collName);
        return OperationResponse.ok(OperationType.DELETE_SCHEMA, "Collection schema deleted successfully");
    }
}
