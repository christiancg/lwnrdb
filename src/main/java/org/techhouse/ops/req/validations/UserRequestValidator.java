package org.techhouse.ops.req.validations;

import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.ListUsersRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.ops.req.SetPasswordRequest;

public final class UserRequestValidator {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final int PASSWORD_MIN_LENGTH = Globals.PASSWORD_MIN_LENGTH;

    private UserRequestValidator() {
    }

    static ValidationResult validateAuthenticate(AuthenticateRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(NameValidations.USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            return ValidationResult.fail("password is required");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateCreateUser(CreateUserRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(NameValidations.USERNAME_PATTERN)) {
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
        return validateRawScriptPermissions(request.getRawScriptPermissions());
    }

    static ValidationResult validateDeleteUser(DeleteUserRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(NameValidations.USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateChangePermissions(ChangePermissionsRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(NameValidations.USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        final var dbPermsResult = validateRawPermissionMaps(request.getRawDatabasePermissions(),
                request.getRawCollectionPermissions());
        if (!dbPermsResult.isValid()) {
            return dbPermsResult;
        }
        return validateRawScriptPermissions(request.getRawScriptPermissions());
    }

    static ValidationResult validateSetPassword(SetPasswordRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(NameValidations.USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        if (request.getNewPassword() == null || request.getNewPassword().length() < PASSWORD_MIN_LENGTH) {
            return ValidationResult.fail("newPassword must be at least " + PASSWORD_MIN_LENGTH + " characters");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateListUsers(ListUsersRequest request) {
        return DataRequestValidator.validateAggregationSteps(request.getAggregationSteps());
    }

    static ValidationResult validateSetDatabaseOwners(SetDatabaseOwnersRequest request) {
        final var dbResult = NameValidations.validateDbName(request.getDatabaseName(), true);
        if (!dbResult.isValid()) {
            return dbResult;
        }
        for (var owner : request.getOwners()) {
            if (!owner.matches(NameValidations.NAME_PATTERN)) {
                return ValidationResult
                        .fail("owner username must be 3-64 alphanumeric characters, underscores, or hyphens");
            }
            if (cache.getAdminUserEntry(owner) == null) {
                return ValidationResult.fail("user '" + owner + "' does not exist");
            }
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateRawScriptPermissions(JsonObject scriptPermissions) {
        if (scriptPermissions == null) {
            return ValidationResult.ok();
        }
        for (var entry : scriptPermissions.entrySet()) {
            final var dbName = entry.getKey();
            if (NameValidations.isReservedDbName(dbName)) {
                return ValidationResult.fail("databaseName '" + dbName + "' is reserved");
            }
            if (!dbName.matches(NameValidations.NAME_PATTERN)) {
                return ValidationResult.fail(
                        "database name in script permissions must be 3-64 alphanumeric characters, underscores, or hyphens");
            }
            // The boolean form is still accepted for backward compatibility; a typo'd level name must fail
            // loudly rather than read as NONE.
            final var value = entry.getValue();
            if (value == null || value.getJsonType() == JsonBaseElement.JsonType.BOOLEAN) {
                continue;
            }
            if (value.getJsonType() != JsonBaseElement.JsonType.STRING
                    || !ScriptPermissionLevel.isValidName(value.asJsonString().getValue())) {
                return ValidationResult.fail("script permission for database '" + dbName
                        + "' must be one of NONE, RUN, MANAGE (or the legacy true/false)");
            }
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateRawPermissionMaps(JsonObject databasePermissions,
            JsonObject collectionPermissions) {
        if (databasePermissions != null) {
            for (var entry : databasePermissions.entrySet()) {
                final var dbName = entry.getKey();
                if (NameValidations.isReservedDbName(dbName)) {
                    return ValidationResult.fail("databaseName '" + dbName + "' is reserved");
                }
                if (!dbName.matches(NameValidations.NAME_PATTERN)) {
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
                if (NameValidations.isReservedDbName(dbName)) {
                    return ValidationResult.fail("databaseName '" + dbName + "' is reserved");
                }
                if (!dbName.matches(NameValidations.NAME_PATTERN)) {
                    return ValidationResult.fail(
                            "database name in collection permission must be 3-64 alphanumeric characters, underscores, or hyphens");
                }
                if (!collName.matches(NameValidations.NAME_PATTERN)) {
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
