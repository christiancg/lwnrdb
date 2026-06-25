package org.techhouse.ops;

public enum ErrorCode {
    // ── 400 Bad Request (client / validation errors) ──────────────────────
    VALIDATION_ERROR("400-1", null, OperationStatus.ERROR), ENTRY_TOO_LARGE("400-2",
            "Entry size exceeds maximum allowed size", OperationStatus.ERROR), DUPLICATE_ID("400-3",
                    "Duplicate _id in bulk save request", OperationStatus.ERROR), CANNOT_DELETE_LAST_ADMIN("400-4",
                            "Cannot delete the last admin user", OperationStatus.ERROR), CANNOT_DEMOTE_LAST_ADMIN(
                                    "400-5", "Cannot demote the last admin user",
                                    OperationStatus.ERROR), CURRENT_PASSWORD_INCORRECT("400-6",
                                            "Current password is incorrect", OperationStatus.ERROR),

    // ── 401 Unauthenticated ───────────────────────────────────────────────
    MUST_AUTHENTICATE_FIRST("401-1", "Must authenticate first", OperationStatus.UNAUTHENTICATED), USER_NO_LONGER_EXISTS(
            "401-2", "User no longer exists", OperationStatus.UNAUTHENTICATED), WRONG_CREDENTIALS("401-3",
                    "The user doesn't exist or the wrong credentials have been provided", OperationStatus.ERROR),

    // ── 403 Forbidden ─────────────────────────────────────────────────────
    NO_PERMISSIONS("403-1", "Action is forbidden, no permissions", OperationStatus.FORBIDDEN),

    // ── 404 Not Found ─────────────────────────────────────────────────────
    USER_NOT_FOUND("404-1", "User not found", OperationStatus.NOT_FOUND), ENTRY_NOT_FOUND("404-2", "Entry not found",
            OperationStatus.NOT_FOUND), NO_RESULTS("404-3", "No results",
                    OperationStatus.NOT_FOUND), DATABASE_NOT_FOUND("404-4", "Database not found",
                            OperationStatus.NOT_FOUND), NO_USERS_FOUND("404-5", "No users found",
                                    OperationStatus.NOT_FOUND), INDEX_NOT_FOUND("404-6",
                                            "No index registered for the specified field", OperationStatus.NOT_FOUND),

    // ── 409 Conflict ──────────────────────────────────────────────────────
    USER_ALREADY_EXISTS("409-1", "User already exists", OperationStatus.ERROR), DATABASE_ALREADY_EXISTS("409-2",
            "Database already exists", OperationStatus.ERROR),

    // ── 500 Internal Server Error ─────────────────────────────────────────
    AUTHENTICATION_ERROR("500-1", "Error during authentication", OperationStatus.ERROR), ERROR_CREATING_USER("500-2",
            "Error creating user", OperationStatus.ERROR), ERROR_DELETING_USER("500-3", "Error deleting user",
                    OperationStatus.ERROR), ERROR_CHANGING_PASSWORD("500-4", "Error changing password",
                            OperationStatus.ERROR), ERROR_CHANGING_PERMISSIONS("500-5", "Error changing permissions",
                                    OperationStatus.ERROR), ERROR_BULK_SAVING("500-6", "Error while saving entries",
                                            OperationStatus.ERROR), ERROR_SAVING("500-7", "Error while saving entry",
                                                    OperationStatus.ERROR), ERROR_RETRIEVING("500-8",
                                                            "Error while retrieving entry",
                                                            OperationStatus.ERROR), ERROR_AGGREGATING("500-9",
                                                                    "Error while processing aggregation",
                                                                    OperationStatus.ERROR), ERROR_DELETING("500-10",
                                                                            "Error while deleting entry",
                                                                            OperationStatus.ERROR), ERROR_CREATING_DATABASE(
                                                                                    "500-11",
                                                                                    "Error while creating database",
                                                                                    OperationStatus.ERROR), ERROR_UPDATING_DATABASE_OWNERS(
                                                                                            "500-12",
                                                                                            "Error updating database owners",
                                                                                            OperationStatus.ERROR), ERROR_DROPPING_DATABASE(
                                                                                                    "500-13",
                                                                                                    "Error while dropping database",
                                                                                                    OperationStatus.ERROR), ERROR_LISTING_DATABASES(
                                                                                                            "500-14",
                                                                                                            "Error while listing databases",
                                                                                                            OperationStatus.ERROR), ERROR_CREATING_COLLECTION(
                                                                                                                    "500-15",
                                                                                                                    "Error while creating collection",
                                                                                                                    OperationStatus.ERROR), ERROR_DROPPING_COLLECTION(
                                                                                                                            "500-16",
                                                                                                                            "Error while dropping collection",
                                                                                                                            OperationStatus.ERROR), ERROR_LISTING_COLLECTIONS(
                                                                                                                                    "500-17",
                                                                                                                                    "Error while listing collections",
                                                                                                                                    OperationStatus.ERROR), ERROR_CREATING_INDEX(
                                                                                                                                            "500-18",
                                                                                                                                            "Error while creating index",
                                                                                                                                            OperationStatus.ERROR), ERROR_LISTING_USERS(
                                                                                                                                                    "500-19",
                                                                                                                                                    "Error listing users",
                                                                                                                                                    OperationStatus.ERROR), ERROR_DROPPING_INDEX(
                                                                                                                                                            "500-20",
                                                                                                                                                            "Error while dropping index",
                                                                                                                                                            OperationStatus.ERROR), ERROR_REINDEXING(
                                                                                                                                                                    "500-21",
                                                                                                                                                                    "Error while reindexing",
                                                                                                                                                                    OperationStatus.ERROR), ERROR_GATHERING_STATS(
                                                                                                                                                                            "500-22",
                                                                                                                                                                            "Error while gathering database stats",
                                                                                                                                                                            OperationStatus.ERROR),

    // ── 503 Service Unavailable ───────────────────────────────────────────
    MAX_CONNECTIONS_REACHED("503-1", "Max number of connections reached", OperationStatus.ERROR);

    private final String code;
    private final String defaultMessage;
    private final OperationStatus status;

    ErrorCode(String code, String defaultMessage, OperationStatus status) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public OperationStatus getStatus() {
        return status;
    }
}
