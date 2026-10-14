package org.techhouse.ops.req.validations;

import org.techhouse.config.Globals;
import org.techhouse.ops.req.OperationRequest;

public final class NameValidations {
    static final String NAME_PATTERN = "^[a-zA-Z0-9_-]{3,64}$";
    static final String USERNAME_PATTERN = NAME_PATTERN;
    static final String INDEX_FIELD_PATTERN = "^[A-Za-z0-9_.-]{1,64}$";
    private static final int MIN_NAME_LENGTH = 3;
    private static final int MAX_NAME_LENGTH = 64;

    private NameValidations() {
    }

    // Procedure names follow the collection-name rule because the name becomes a path segment in
    // FileSystem.getProcedureFile: no separator, no dot, no '..'.
    static ValidationResult validateProcedureName(String name) {
        if (name == null || name.isBlank()) {
            return ValidationResult.fail("procedure name is required");
        }
        if (!isValidProcedureName(name)) {
            return ValidationResult
                    .fail("procedure name must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    public static boolean isValidProcedureName(String name) {
        return isName(name);
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
        return Globals.SCRIPT_RUNS_COLLECTION_NAME.equalsIgnoreCase(collName);
    }

    public static boolean isReservedDbName(String dbName) {
        return Globals.ADMIN_DB_NAME.equalsIgnoreCase(dbName) || Globals.ADMIN_PAGES_DB_NAME.equalsIgnoreCase(dbName)
                || Globals.CLUSTER_FOLDER.equalsIgnoreCase(dbName);
    }

    public static boolean isName(String value) {
        return matchesCharset(value, MIN_NAME_LENGTH);
    }

    public static boolean isId(String value) {
        return matchesCharset(value, 1);
    }

    public static boolean isUsername(String value) {
        return matchesCharset(value, MIN_NAME_LENGTH);
    }

    private static boolean matchesCharset(String value, int minLength) {
        if (value == null || value.length() < minLength || value.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (var i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            final var allowed = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_'
                    || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    public static boolean isIndexFieldName(String fieldName) {
        return fieldName != null && fieldName.matches(INDEX_FIELD_PATTERN);
    }

    static ValidationResult validateIndexFieldName(String fieldName) {
        if (isIndexFieldName(fieldName)) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail("fieldName must be 1-64 characters from A-Z a-z 0-9 _ . -");
    }

    static ValidationResult validateDbName(String dbName, boolean rejectAdmin) {
        if (dbName == null || dbName.isBlank()) {
            return ValidationResult.fail("databaseName is required");
        }
        if (!isName(dbName)) {
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
        if (!isName(collectionName)) {
            return ValidationResult
                    .fail("collectionName must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }
}
