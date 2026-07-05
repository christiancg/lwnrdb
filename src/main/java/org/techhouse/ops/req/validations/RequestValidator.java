package org.techhouse.ops.req.validations;

import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ListUsersRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.req.StopListenRequest;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public class RequestValidator {
    private static final Cache cache = IocContainer.get(Cache.class);
    static final String NAME_PATTERN = "^[a-zA-Z0-9_-]{3,64}$";
    private static final String ID_PATTERN = "^[a-zA-Z0-9_-]{1,64}$";
    private static final String USERNAME_PATTERN = NAME_PATTERN;
    private static final int PASSWORD_MIN_LENGTH = Globals.PASSWORD_MIN_LENGTH;

    public static ValidationResult validate(OperationRequest request) {
        return switch (request.getType()) {
            case SAVE -> validateSave((SaveRequest) request);
            case BULK_SAVE -> validateBulkSave((BulkSaveRequest) request);
            case FIND_BY_ID -> validateFindById((FindByIdRequest) request);
            case DELETE -> validateDelete((DeleteRequest) request);
            case AGGREGATE -> validateAggregate((AggregateRequest) request);
            case CREATE_DATABASE, DROP_DATABASE -> validateDbOnly(request, true);
            case LIST_DATABASES, CLOSE_CONNECTION, GET_DATABASE_STATS -> ValidationResult.ok();
            case CREATE_COLLECTION, DROP_COLLECTION -> validateDbAndColl(request, true);
            case LIST_COLLECTIONS -> validateDbOnly(request, false);
            case CREATE_INDEX -> validateCreateIndex((CreateIndexRequest) request);
            case DROP_INDEX -> validateDropIndex((DropIndexRequest) request);
            case REINDEX -> validateReindex((ReindexRequest) request);
            case AUTHENTICATE -> validateAuthenticate((AuthenticateRequest) request);
            case CREATE_USER -> validateCreateUser((CreateUserRequest) request);
            case DELETE_USER -> validateDeleteUser((DeleteUserRequest) request);
            case CHANGE_PERMISSIONS -> validateChangePermissions((ChangePermissionsRequest) request);
            case SET_DATABASE_OWNERS -> validateSetDatabaseOwners((SetDatabaseOwnersRequest) request);
            case LIST_USERS -> validateListUsers((ListUsersRequest) request);
            case SET_PASSWORD -> validateSetPassword((SetPasswordRequest) request);
            case LISTEN -> validateListen((ListenRequest) request);
            case STOP_LISTEN -> validateStopListen((StopListenRequest) request);
        };
    }

    private static ValidationResult validateDbOnly(OperationRequest request, boolean rejectAdmin) {
        return validateDbName(request.getDatabaseName(), rejectAdmin);
    }

    private static ValidationResult validateDbAndColl(OperationRequest request, boolean rejectAdmin) {
        final var dbResult = validateDbName(request.getDatabaseName(), rejectAdmin);
        if (!dbResult.isValid()) {
            return dbResult;
        }
        return validateCollectionName(request.getCollectionName());
    }

    private static ValidationResult validateSave(SaveRequest request) {
        final var base = validateDbAndColl(request, true);
        if (!base.isValid()) {
            return base;
        }
        if (request.getObject() == null) {
            return ValidationResult.fail("SAVE request requires an object");
        }
        if (request.get_id() != null && !request.get_id().matches(ID_PATTERN)) {
            return ValidationResult.fail("_id must be 1-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateBulkSave(BulkSaveRequest request) {
        final var base = validateDbAndColl(request, true);
        if (!base.isValid()) {
            return base;
        }
        if (request.getObjects() == null || request.getObjects().isEmpty()) {
            return ValidationResult.fail("BULK_SAVE request requires at least one object");
        }
        for (var obj : request.getObjects()) {
            if (obj.has(Globals.PK_FIELD)) {
                final var id = obj.get(Globals.PK_FIELD).asJsonString().getValue();
                if (!id.matches(ID_PATTERN)) {
                    return ValidationResult.fail("_id must be 1-64 alphanumeric characters, underscores, or hyphens");
                }
            }
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateFindById(FindByIdRequest request) {
        final var base = validateDbAndColl(request, false);
        if (!base.isValid()) {
            return base;
        }
        if (request.get_id() == null) {
            return ValidationResult.fail("FIND_BY_ID request requires an _id");
        }
        if (!request.get_id().matches(ID_PATTERN)) {
            return ValidationResult.fail("_id must be 1-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateDelete(DeleteRequest request) {
        final var base = validateDbAndColl(request, true);
        if (!base.isValid()) {
            return base;
        }
        if (request.get_id() == null) {
            return ValidationResult.fail("DELETE request requires an _id");
        }
        if (!request.get_id().matches(ID_PATTERN)) {
            return ValidationResult.fail("_id must be 1-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateAggregate(AggregateRequest request) {
        final var base = validateDbAndColl(request, false);
        if (!base.isValid()) {
            return base;
        }
        if (request.getAggregationSteps() == null) {
            return ValidationResult.fail("AGGREGATE request requires an aggregationSteps array");
        }
        return validateAggregationSteps(request.getAggregationSteps());
    }

    // Validates each step in isolation and enforces the only positional rule: COUNT collapses the
    // stream to a single {count:N} document, so it is only meaningful as the final step. A COUNT
    // anywhere but last (e.g. followed by a FILTER) is rejected.
    private static ValidationResult validateAggregationSteps(List<BaseAggregationStep> steps) {
        for (var i = 0; i < steps.size(); i++) {
            final var step = steps.get(i);
            final var stepResult = AggregationStepValidator.validate(step);
            if (!stepResult.isValid()) {
                return stepResult;
            }
            if (step.getType() == AggregationStepType.COUNT && i < steps.size() - 1) {
                return ValidationResult.fail("COUNT must be the last aggregation step");
            }
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateCreateIndex(CreateIndexRequest request) {
        final var base = validateDbAndColl(request, true);
        if (!base.isValid()) {
            return base;
        }
        if (request.getFieldName() == null || request.getFieldName().isBlank()) {
            return ValidationResult.fail("CREATE_INDEX request requires a non-blank fieldName");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateDropIndex(DropIndexRequest request) {
        final var base = validateDbAndColl(request, true);
        if (!base.isValid()) {
            return base;
        }
        if (request.getFieldName() == null || request.getFieldName().isBlank()) {
            return ValidationResult.fail("DROP_INDEX request requires a non-blank fieldName");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateReindex(ReindexRequest request) {
        final var base = validateDbAndColl(request, true);
        if (!base.isValid()) {
            return base;
        }
        for (var fieldName : request.getFieldNames()) {
            if (fieldName == null || fieldName.isBlank()) {
                return ValidationResult.fail("REINDEX fieldNames must not contain blank entries");
            }
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateDbName(String dbName, boolean rejectAdmin) {
        if (dbName == null || dbName.isBlank()) {
            return ValidationResult.fail("databaseName is required");
        }
        if (!dbName.matches(NAME_PATTERN)) {
            return ValidationResult.fail("databaseName must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        if (rejectAdmin && Globals.ADMIN_DB_NAME.equals(dbName)) {
            return ValidationResult.fail("databaseName '" + Globals.ADMIN_DB_NAME + "' is reserved");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateCollectionName(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return ValidationResult.fail("collectionName is required");
        }
        if (!collectionName.matches(NAME_PATTERN)) {
            return ValidationResult
                    .fail("collectionName must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateAuthenticate(AuthenticateRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            return ValidationResult.fail("password is required");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateCreateUser(CreateUserRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        if (request.getPassword() == null || request.getPassword().length() < PASSWORD_MIN_LENGTH) {
            return ValidationResult.fail("password must be at least " + PASSWORD_MIN_LENGTH + " characters");
        }
        final var dbPermsResult = validateRawPermissionMaps(request.getRawDatabasePermissions(),
                request.getRawCollectionPermissions());
        if (!dbPermsResult.isValid()) {
            return dbPermsResult;
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateDeleteUser(DeleteUserRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateChangePermissions(ChangePermissionsRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        final var dbPermsResult = validateRawPermissionMaps(request.getRawDatabasePermissions(),
                request.getRawCollectionPermissions());
        if (!dbPermsResult.isValid()) {
            return dbPermsResult;
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateSetPassword(SetPasswordRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        if (request.getNewPassword() == null || request.getNewPassword().length() < PASSWORD_MIN_LENGTH) {
            return ValidationResult.fail("newPassword must be at least " + PASSWORD_MIN_LENGTH + " characters");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateListUsers(ListUsersRequest request) {
        return validateAggregationSteps(request.getAggregationSteps());
    }

    private static ValidationResult validateSetDatabaseOwners(SetDatabaseOwnersRequest request) {
        final var dbResult = validateDbName(request.getDatabaseName(), true);
        if (!dbResult.isValid()) {
            return dbResult;
        }
        for (var owner : request.getOwners()) {
            if (!owner.matches(NAME_PATTERN)) {
                return ValidationResult
                        .fail("owner username must be 3-64 alphanumeric characters, underscores, or hyphens");
            }
            if (cache.getAdminUserEntry(owner) == null) {
                return ValidationResult.fail("user '" + owner + "' does not exist");
            }
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateListen(ListenRequest request) {
        final var base = validateDbAndColl(request, false);
        if (!base.isValid()) {
            return base;
        }
        if (request.getAggregationSteps() == null) {
            return ValidationResult.fail("LISTEN request requires an aggregationSteps array");
        }
        return validateAggregationSteps(request.getAggregationSteps());
    }

    private static ValidationResult validateStopListen(StopListenRequest request) {
        if (request.getListenId() == null || request.getListenId().isBlank()) {
            return ValidationResult.fail("STOP_LISTEN request requires a listenId");
        }
        try {
            java.util.UUID.fromString(request.getListenId());
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail("STOP_LISTEN listenId must be a valid UUID");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateRawPermissionMaps(JsonObject databasePermissions,
            JsonObject collectionPermissions) {
        if (databasePermissions != null) {
            for (var entry : databasePermissions.entrySet()) {
                final var dbName = entry.getKey();
                if (Globals.ADMIN_DB_NAME.equals(dbName)) {
                    return ValidationResult.fail("databaseName '" + Globals.ADMIN_DB_NAME + "' is reserved");
                }
                if (!dbName.matches(NAME_PATTERN)) {
                    return ValidationResult.fail(
                            "database name in permissions must be 3-64 alphanumeric characters, underscores, or hyphens");
                }
                try {
                    PermissionLevel.valueOf(entry.getValue().asJsonString().getValue());
                } catch (Exception e) {
                    return ValidationResult.fail("invalid permission level for database '" + dbName + "'");
                }
            }
        }
        if (collectionPermissions != null) {
            for (var entry : collectionPermissions.entrySet()) {
                final var collKey = entry.getKey();
                final var parts = collKey.split("\\|");
                if (parts.length != 2) {
                    return ValidationResult.fail("collection permission key must be in format 'database|collection'");
                }
                final var dbName = parts[0];
                final var collName = parts[1];
                if (Globals.ADMIN_DB_NAME.equals(dbName)) {
                    return ValidationResult.fail("databaseName '" + Globals.ADMIN_DB_NAME + "' is reserved");
                }
                if (!dbName.matches(NAME_PATTERN)) {
                    return ValidationResult.fail(
                            "database name in collection permission must be 3-64 alphanumeric characters, underscores, or hyphens");
                }
                if (!collName.matches(NAME_PATTERN)) {
                    return ValidationResult.fail(
                            "collection name in permission must be 3-64 alphanumeric characters, underscores, or hyphens");
                }
                try {
                    PermissionLevel.valueOf(entry.getValue().asJsonString().getValue());
                } catch (Exception e) {
                    return ValidationResult.fail("invalid permission level for collection '" + collKey + "'");
                }
            }
        }
        return ValidationResult.ok();
    }
}
