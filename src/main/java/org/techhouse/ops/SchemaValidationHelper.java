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

// Enforces a collection's JSON Schema on SAVE / BULK_SAVE before the write reaches OperationProcessor,
// so a non-compliant document can never be committed. Runs at the edge node; because schemas are
// replicated cluster-wide, an edge validates before forwarding a write to the collection's owner.
public final class SchemaValidationHelper {
    private static final Logger logger = Logger.logFor(SchemaValidationHelper.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EJson eJson = IocContainer.get(EJson.class);

    private SchemaValidationHelper() {
    }

    // Returns null when the request complies (or the collection has no schema), otherwise a 400-7 error.
    public static OperationResponse check(OperationRequest request) {
        try {
            return switch (request.getType()) {
                case SAVE -> checkSave((SaveRequest) request);
                case BULK_SAVE -> checkBulkSave((BulkSaveRequest) request);
                default -> null;
            };
        } catch (Exception e) {
            // The cached schema is validated when it is saved, so this is a can't-happen guard: never
            // break the write path over an internal schema issue — log and let the write proceed.
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

    // Also the entry point a before trigger's replacement document is re-validated through: the checks
    // above run at the edge, before the hook, so without this a hook could produce a document the
    // collection's schema forbids. Returns the joined violations, or null when the document complies.
    public static String schemaErrors(String dbName, String collName, JsonObject object) {
        final var schema = cache.getCollectionSchema(dbName, collName);
        if (schema == null) {
            return null;
        }
        final var result = eJson.validateWithSchema(withoutId(object), schema);
        return result.isValid() ? null : String.join("; ", result.getErrors());
    }

    // Validates the user document without the reserved _id field: _id is a system-assigned primary key
    // (already format-checked by the request validator), not user data the schema governs — so a schema
    // with additionalProperties:false does not need to declare it. Returns a shallow copy (children shared)
    // so the original request object is left untouched for the downstream write.
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
