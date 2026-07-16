package org.techhouse.ops;

import java.io.IOException;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.DeleteSchemaRequest;
import org.techhouse.ops.req.SaveSchemaRequest;
import org.techhouse.ops.resp.DeleteSchemaResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveSchemaResponse;

// Persists and removes a collection's single JSON Schema. Callers hold the collection write lock so the
// schema file write, cache update and any concurrent save are serialized (see OperationProcessor).
public final class SchemaOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EJson eJson = IocContainer.get(EJson.class);

    private SchemaOperationHelper() {
    }

    // Validates the schema as a well-formed 2020-12 schema before persisting it; warnings (unrecognized
    // keywords) are surfaced on the response but do not block the save.
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
        fs.writeCollectionSchema(dbName, collName, eJson.toJson(schema));
        cache.putCollectionSchema(dbName, collName, schema);
        return new SaveSchemaResponse("Collection schema saved successfully", validation.getWarnings());
    }

    // Idempotent: succeeds whether or not a schema existed, so cluster re-execution on a peer that is
    // already schema-less does not fail replication.
    public static OperationResponse executeDeleteSchema(DeleteSchemaRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (cache.getAdminCollectionEntry(dbName, collName) == null) {
            return new OperationResponse(OperationType.DELETE_SCHEMA, "Collection '" + collName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        fs.deleteCollectionSchema(dbName, collName);
        cache.removeCollectionSchema(dbName, collName);
        return new DeleteSchemaResponse("Collection schema deleted successfully");
    }
}
