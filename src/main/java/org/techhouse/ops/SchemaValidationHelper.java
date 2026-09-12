package org.techhouse.ops;

import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;

public final class SchemaValidationHelper {
    private static final Logger logger = Logger.logFor(SchemaValidationHelper.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EJson eJson = IocContainer.get(EJson.class);

    private SchemaValidationHelper() {
    }

    public static OperationResponse check(OperationRequest request) {
        try {
            return switch (request.getType()) {
                case SAVE -> checkSave((SaveRequest) request);
                case BULK_SAVE -> checkBulkSave((BulkSaveRequest) request);
                default -> null;
            };
        } catch (Exception e) {
            // Cannot happen (the cached schema was validated when saved): never break the write path over it.
            logger.warning("Skipping schema validation for " + request.getDatabaseName() + "|"
                    + request.getCollectionName() + ": " + e.getMessage());
            return null;
        }
    }

    private static OperationResponse checkSave(SaveRequest request) {
        final var errors = schemaErrors(request.getDatabaseName(), request.getCollectionName(), request.getObject());
        if (errors == null) {
            return null;
        }
        return new OperationResponse(OperationType.SAVE,
                ErrorCode.SCHEMA_VALIDATION_FAILED.getDefaultMessage() + ": " + errors,
                ErrorCode.SCHEMA_VALIDATION_FAILED);
    }

    private static OperationResponse checkBulkSave(BulkSaveRequest request) {
        for (final var object : request.getObjects()) {
            final var errors = schemaErrors(request.getDatabaseName(), request.getCollectionName(), object);
            if (errors != null) {
                return new OperationResponse(
                        OperationType.BULK_SAVE, ErrorCode.SCHEMA_VALIDATION_FAILED.getDefaultMessage()
                                + " for document '" + idOf(object) + "': " + errors,
                        ErrorCode.SCHEMA_VALIDATION_FAILED);
            }
        }
        return null;
    }

    // Also re-validates a before-hook's replacement document: the checks above run at the edge, before the hook.
    public static String schemaErrors(String dbName, String collName, JsonObject object) {
        final var schema = cache.getCollectionSchema(dbName, collName);
        if (schema == null) {
            return null;
        }
        final var result = eJson.validateWithSchema(withoutId(object), schema);
        return result.isValid() ? null : String.join("; ", result.getErrors());
    }

    // _id is system-assigned, not user data the schema governs, so additionalProperties:false need not declare it.
    private static JsonObject withoutId(JsonObject object) {
        if (object == null || !object.has(Globals.PK_FIELD)) {
            return object;
        }
        final var copy = new JsonObject();
        for (final var entry : object.entrySet()) {
            if (!Globals.PK_FIELD.equals(entry.getKey())) {
                copy.add(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private static String idOf(JsonObject object) {
        return object.has(Globals.PK_FIELD) ? object.get(Globals.PK_FIELD).asJsonString().getValue() : "(no _id)";
    }
}
