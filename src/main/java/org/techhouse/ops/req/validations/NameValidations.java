package org.techhouse.ops.req.validations;

import org.techhouse.config.Globals;
import org.techhouse.ops.req.OperationRequest;

public final class NameValidations {
    static final String NAME_PATTERN = "^[a-zA-Z0-9_-]{3,64}$";
    static final String ID_PATTERN = "^[a-zA-Z0-9_-]{1,64}$";
    static final String USERNAME_PATTERN = NAME_PATTERN;

    private NameValidations() {
    }

    // Procedure names follow the collection-name rule because the name becomes a path segment in
    // FileSystem.getProcedureFile: no separator, no dot, no '..'.
    static ValidationResult validateProcedureName(String name) {
        if (name == null || name.isBlank()) {
            return ValidationResult.fail("procedure name is required");
        }
        if (!name.matches(NAME_PATTERN)) {
            return ValidationResult
                    .fail("procedure name must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateDbNameOnly(String databaseName) {
        return validateDbName(databaseName, true);
    }

    static ValidationResult validateDbOnly(OperationRequest request, boolean rejectAdmin) {
        return validateDbName(request.getDatabaseName(), rejectAdmin);
    }

    static ValidationResult validateDbAndColl(OperationRequest request, boolean rejectAdmin) {
        return validateDbAndColl(request, rejectAdmin, false);
    }

    // Reads are deliberately not refused: the history collection exists to be queried.
    static ValidationResult validateDbAndColl(OperationRequest request, boolean rejectAdmin, boolean rejectReserved) {
        final var dbResult = validateDbName(request.getDatabaseName(), rejectAdmin);
        if (!dbResult.isValid()) {
            return dbResult;
        }
        final var collResult = validateCollectionName(request.getCollectionName());
        if (!collResult.isValid()) {
            return collResult;
        }
        if (rejectReserved && isReservedCollectionName(request.getCollectionName())) {
            return ValidationResult
                    .fail("collectionName '" + request.getCollectionName() + "' is reserved and cannot be modified");
        }
        return ValidationResult.ok();
    }

    static boolean isReservedCollectionName(String collName) {
        return Globals.SCRIPT_RUNS_COLLECTION_NAME.equals(collName);
    }

    static boolean isReservedDbName(String dbName) {
        return Globals.ADMIN_DB_NAME.equals(dbName) || Globals.ADMIN_PAGES_DB_NAME.equals(dbName);
    }

    static ValidationResult validateDbName(String dbName, boolean rejectAdmin) {
        if (dbName == null || dbName.isBlank()) {
            return ValidationResult.fail("databaseName is required");
        }
        if (!dbName.matches(NAME_PATTERN)) {
            return ValidationResult.fail("databaseName must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        if (rejectAdmin && isReservedDbName(dbName)) {
            return ValidationResult.fail("databaseName '" + dbName + "' is reserved");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateCollectionName(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return ValidationResult.fail("collectionName is required");
        }
        if (!collectionName.matches(NAME_PATTERN)) {
            return ValidationResult
                    .fail("collectionName must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }
}
