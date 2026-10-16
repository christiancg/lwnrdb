package org.techhouse.ops;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.InvalidSchemaException;
import org.techhouse.ejson.exceptions.SchemaBudgetExceededException;
import org.techhouse.ejson.validate.SchemaBudget;
import org.techhouse.ejson.validate.SchemaValidationResult;
import org.techhouse.ex.MetadataReadException;
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
    private static final Configuration configuration = Configuration.getInstance();
    private static final Map<String, CheckedSchema> checkedSchemas = new ConcurrentHashMap<>();

    private record CheckedSchema(WeakReference<JsonObject> source, SchemaValidationResult metaResult) {
    }

    private SchemaValidationHelper() {
    }

    public static OperationResponse check(OperationRequest request) {
        try {
            return switch (request.getType()) {
                case SAVE -> checkSave((SaveRequest) request);
                case BULK_SAVE -> checkBulkSave((BulkSaveRequest) request);
                default -> null;
            };
        } catch (MetadataReadException e) {
            logger.error("Refusing the write: cannot read the schema for " + request.getDatabaseName() + "|"
                    + request.getCollectionName(), e);
            return new OperationResponse(request.getType(), ErrorCode.SCHEMA_UNAVAILABLE);
        } catch (InvalidSchemaException e) {
            logger.error("Refusing the write: the stored schema for " + request.getDatabaseName() + "|"
                    + request.getCollectionName() + " does not meta-validate", e);
            return new OperationResponse(request.getType(), ErrorCode.SCHEMA_UNAVAILABLE);
        } catch (SchemaBudgetExceededException e) {
            logger.warning("Refusing the write: validating it against the schema of " + request.getDatabaseName() + "|"
                    + request.getCollectionName() + " exceeded the schema validation budget");
            return new OperationResponse(request.getType(), ErrorCode.SCHEMA_VALIDATION_BUDGET_EXCEEDED);
        } catch (Exception e) {
            logger.error("Refusing the write: the schema check for " + request.getDatabaseName() + "|"
                    + request.getCollectionName() + " failed unexpectedly", e);
            return new OperationResponse(request.getType(), ErrorCode.SCHEMA_UNAVAILABLE);
        }
    }

    private static OperationResponse checkSave(SaveRequest request) {
        final var schema = governingSchema(request.getDatabaseName(), request.getCollectionName());
        final var document = request.getObject();
        if (schema == null || alreadyChecked(request, document, schema)) {
            return null;
        }
        final var budget = newBudget();
        final var errors = errorsAgainst(schema, document, budget);
        if (errors == null) {
            rememberChecked(request, document, schema, budget);
            return null;
        }
        return new OperationResponse(OperationType.SAVE,
                ErrorCode.SCHEMA_VALIDATION_FAILED.getDefaultMessage() + ": " + errors,
                ErrorCode.SCHEMA_VALIDATION_FAILED);
    }

    private static OperationResponse checkBulkSave(BulkSaveRequest request) {
        final var schema = governingSchema(request.getDatabaseName(), request.getCollectionName());
        final var documents = request.getObjects();
        if (schema == null || alreadyChecked(request, documents, schema)) {
            return null;
        }
        final var budget = newBudget();
        for (final var object : documents) {
            final var errors = errorsAgainst(schema, object, budget);
            if (errors != null) {
                return new OperationResponse(
                        OperationType.BULK_SAVE, ErrorCode.SCHEMA_VALIDATION_FAILED.getDefaultMessage()
                                + " for document '" + idOf(object) + "': " + errors,
                        ErrorCode.SCHEMA_VALIDATION_FAILED);
            }
        }
        rememberChecked(request, documents, schema, budget);
        return null;
    }

    // Also re-validates a before-hook's replacement document: the checks above run at the edge, before the hook.
    public static String schemaErrors(String dbName, String collName, JsonObject object, SchemaBudget budget) {
        final var schema = governingSchema(dbName, collName);
        return schema == null ? null : errorsAgainst(schema, object, budget);
    }

    public static SchemaBudget budgetFor(OperationRequest request) {
        final var checked = request.getSchemaCheck();
        return checked != null ? checked.budget() : newBudget();
    }

    private static JsonObject governingSchema(String dbName, String collName) {
        final var schema = cache.getCollectionSchema(dbName, collName);
        if (schema != null) {
            requireValidSchema(dbName, collName, schema);
        }
        return schema;
    }

    public static SchemaBudget newBudget() {
        return new SchemaBudget(configuration.getSchemaValidationBudget());
    }

    private static String errorsAgainst(JsonObject schema, JsonObject object, SchemaBudget budget) {
        final var result = eJson.validateInstance(withoutId(object), schema, budget);
        return result.isValid() ? null : String.join("; ", result.getErrors());
    }

    private static boolean alreadyChecked(OperationRequest request, Object payload, JsonObject schema) {
        final var checked = request.getSchemaCheck();
        return checked != null && checked.payload() == payload && checked.schema() == schema;
    }

    private static void rememberChecked(OperationRequest request, Object payload, JsonObject schema,
            SchemaBudget budget) {
        request.setSchemaCheck(new OperationRequest.SchemaCheck(payload, schema, budget));
    }

    private static void requireValidSchema(String dbName, String collName, JsonObject schema) {
        final var key = Cache.getCollectionIdentifier(dbName, collName);
        final var cached = checkedSchemas.get(key);
        final var checked = cached != null && cached.source().get() == schema
                ? cached
                : new CheckedSchema(new WeakReference<>(schema), eJson.validateSchema(schema));
        if (checked != cached) {
            checkedSchemas.put(key, checked);
        }
        if (!checked.metaResult().isValid()) {
            throw new InvalidSchemaException("Cannot validate against an invalid schema: "
                    + String.join("; ", checked.metaResult().getErrors()));
        }
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
        final var id = object.get(Globals.PK_FIELD);
        return id != null && id.isJsonString() ? id.asJsonString().getValue() : "(no _id)";
    }
}
