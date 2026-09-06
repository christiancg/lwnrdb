package org.techhouse.ops;

public enum ErrorCode {
    // ── 400 Bad Request (client / validation errors) ──────────────────────
    VALIDATION_ERROR("400-1", null, OperationStatus.ERROR), ENTRY_TOO_LARGE("400-2",
            "Entry size exceeds maximum allowed size", OperationStatus.ERROR), DUPLICATE_ID("400-3",
                    "Duplicate _id in bulk save request", OperationStatus.ERROR), CANNOT_DELETE_LAST_ADMIN("400-4",
                            "Cannot delete the last admin user", OperationStatus.ERROR), CANNOT_DEMOTE_LAST_ADMIN(
                                    "400-5", "Cannot demote the last admin user",
                                    OperationStatus.ERROR), CURRENT_PASSWORD_INCORRECT("400-6",
                                            "Current password is incorrect",
                                            OperationStatus.ERROR), SCHEMA_VALIDATION_FAILED("400-7",
                                                    "Document does not comply with the collection schema",
                                                    OperationStatus.ERROR), INVALID_SCHEMA("400-8",
                                                            "The provided JSON schema is not valid",
                                                            OperationStatus.ERROR), SCRIPT_FAILED("400-9",
                                                                    "Script execution failed",
                                                                    OperationStatus.ERROR), SCRIPT_TOO_LARGE("400-10",
                                                                            "Script exceeds the maximum allowed size",
                                                                            OperationStatus.ERROR), SCRIPT_LIMIT_EXCEEDED(
                                                                                    "400-11",
                                                                                    "Script exceeded a sandbox limit",
                                                                                    OperationStatus.ERROR), SCRIPT_MEMORY_EXCEEDED(
                                                                                            "400-12",
                                                                                            "Script exceeded its memory budget",
                                                                                            OperationStatus.ERROR), INVALID_PROCEDURE(
                                                                                                    "400-13",
                                                                                                    "The procedure source could not be parsed",
                                                                                                    OperationStatus.ERROR), INVALID_TRIGGER(
                                                                                                            "400-14",
                                                                                                            "The trigger definition is not valid",
                                                                                                            OperationStatus.ERROR), SCRIPT_RESULT_TOO_LARGE(
                                                                                                                    "400-15",
                                                                                                                    "Script result exceeds the maximum allowed size",
                                                                                                                    OperationStatus.ERROR), INVALID_SCHEDULE(
                                                                                                                            "400-16",
                                                                                                                            "The schedule definition is not valid",
                                                                                                                            OperationStatus.ERROR), TOO_MANY_SCHEDULES(
                                                                                                                                    "400-17",
                                                                                                                                    "The database already has the maximum number of schedules",
                                                                                                                                    OperationStatus.ERROR), PROCEDURE_IMPORT_NOT_FOUND(
                                                                                                                                            "400-18",
                                                                                                                                            "The procedure imports a procedure that does not exist",
                                                                                                                                            OperationStatus.ERROR), SCRIPT_NOT_ALLOWED_IN_LISTEN(
                                                                                                                                                    "400-19",
                                                                                                                                                    "A SCRIPT operator is not allowed in a LISTEN pipeline",
                                                                                                                                                    OperationStatus.ERROR), SCRIPT_RESULT_PENDING(
                                                                                                                                                            "400-20",
                                                                                                                                                            "The script's result promise never settled",
                                                                                                                                                            OperationStatus.ERROR), BEFORE_HOOK_REJECTED(
                                                                                                                                                                    "400-21",
                                                                                                                                                                    "A before trigger rejected this write",
                                                                                                                                                                    OperationStatus.ERROR),

    // ── 401 Unauthenticated ───────────────────────────────────
    MUST_AUTHENTICATE_FIRST("401-1", "Must authenticate first", OperationStatus.UNAUTHENTICATED), USER_NO_LONGER_EXISTS(
            "401-2", "User no longer exists", OperationStatus.UNAUTHENTICATED), WRONG_CREDENTIALS("401-3",
                    "The user doesn't exist or the wrong credentials have been provided", OperationStatus.ERROR),

    // ── 403 Forbidden ─────────────────────────────────────────
    NO_PERMISSIONS("403-1", "Action is forbidden, no permissions", OperationStatus.FORBIDDEN), SCRIPTS_DISABLED("403-2",
            "Script execution is disabled on this server", OperationStatus.FORBIDDEN),

    // ── 404 Not Found ─────────────────────────────────────────
    USER_NOT_FOUND("404-1", "User not found", OperationStatus.NOT_FOUND), ENTRY_NOT_FOUND("404-2", "Entry not found",
            OperationStatus.NOT_FOUND), NO_RESULTS("404-3", "No results",
                    OperationStatus.NOT_FOUND), DATABASE_NOT_FOUND("404-4", "Database not found",
                            OperationStatus.NOT_FOUND), NO_USERS_FOUND("404-5", "No users found",
                                    OperationStatus.NOT_FOUND), INDEX_NOT_FOUND("404-6",
                                            "No index registered for the specified field",
                                            OperationStatus.NOT_FOUND), LISTEN_NOT_FOUND("404-7",
                                                    "Listen registration not found",
                                                    OperationStatus.NOT_FOUND), PROCEDURE_NOT_FOUND("404-8",
                                                            "Procedure not found",
                                                            OperationStatus.NOT_FOUND), TRIGGER_NOT_FOUND("404-9",
                                                                    "Trigger not found",
                                                                    OperationStatus.NOT_FOUND), SCHEDULE_NOT_FOUND(
                                                                            "404-10", "Schedule not found",
                                                                            OperationStatus.NOT_FOUND),

    // ── 408 Request Timeout ───────────────────────────────────
    SCRIPT_TIMEOUT("408-1", "Script exceeded its time budget", OperationStatus.ERROR), SCRIPT_CANCELLED("408-2",
            "Script was cancelled", OperationStatus.ERROR),

    // ── 409 Conflict ──────────────────────────────────────────
    USER_ALREADY_EXISTS("409-1", "User already exists", OperationStatus.ERROR), DATABASE_ALREADY_EXISTS("409-2",
            "Database already exists", OperationStatus.ERROR), TRANSACTION_ALREADY_ACTIVE("409-3",
                    "A transaction is already in progress for this connection",
                    OperationStatus.ERROR), NO_ACTIVE_TRANSACTION("409-4", "No active transaction for this connection",
                            OperationStatus.ERROR), TRANSACTION_LOCK_TIMEOUT("409-5",
                                    "Could not acquire the collection lock in time; transaction aborted",
                                    OperationStatus.ERROR), OPERATION_NOT_ALLOWED_IN_TRANSACTION("409-6",
                                            "Operation not allowed while a transaction is open",
                                            OperationStatus.ERROR), TRANSACTION_ABORTED("409-7",
                                                    "Transaction aborted: a participant could not prepare",
                                                    OperationStatus.ERROR), PROCEDURE_VERSION_CONFLICT("409-8",
                                                            "The procedure or trigger was modified by someone else",
                                                            OperationStatus.ERROR),

    // ── 421 Misdirected (cluster routing) ────────────────────────
    NOT_COLLECTION_OWNER("421-1", "This node is not the owner of the target collection",
            OperationStatus.ERROR), CROSS_OWNER_TRANSACTION("421-2",
                    "A transaction may only touch collections owned by a single node", OperationStatus.ERROR),

    // ── 500 Internal Server Error ─────────────────────────────
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
                                                                                                                                                                            OperationStatus.ERROR), ERROR_LISTEN(
                                                                                                                                                                                    "500-23",
                                                                                                                                                                                    "Error while processing listen operation",
                                                                                                                                                                                    OperationStatus.ERROR), ERROR_TRANSACTION(
                                                                                                                                                                                            "500-24",
                                                                                                                                                                                            "Error while processing transaction operation",
                                                                                                                                                                                            OperationStatus.ERROR), ERROR_SAVING_SCHEMA(
                                                                                                                                                                                                    "500-25",
                                                                                                                                                                                                    "Error while saving collection schema",
                                                                                                                                                                                                    OperationStatus.ERROR), ERROR_DELETING_SCHEMA(
                                                                                                                                                                                                            "500-26",
                                                                                                                                                                                                            "Error while deleting collection schema",
                                                                                                                                                                                                            OperationStatus.ERROR), ERROR_SAVING_PROCEDURE(
                                                                                                                                                                                                                    "500-27",
                                                                                                                                                                                                                    "Error while saving the procedure",
                                                                                                                                                                                                                    OperationStatus.ERROR), ERROR_DELETING_PROCEDURE(
                                                                                                                                                                                                                            "500-28",
                                                                                                                                                                                                                            "Error while deleting the procedure",
                                                                                                                                                                                                                            OperationStatus.ERROR), ERROR_SAVING_TRIGGER(
                                                                                                                                                                                                                                    "500-29",
                                                                                                                                                                                                                                    "Error while saving the trigger",
                                                                                                                                                                                                                                    OperationStatus.ERROR), ERROR_DELETING_TRIGGER(
                                                                                                                                                                                                                                            "500-30",
                                                                                                                                                                                                                                            "Error while deleting the trigger",
                                                                                                                                                                                                                                            OperationStatus.ERROR), ERROR_SAVING_SCHEDULE(
                                                                                                                                                                                                                                                    "500-31",
                                                                                                                                                                                                                                                    "Error while saving the schedule",
                                                                                                                                                                                                                                                    OperationStatus.ERROR), ERROR_DELETING_SCHEDULE(
                                                                                                                                                                                                                                                            "500-32",
                                                                                                                                                                                                                                                            "Error while deleting the schedule",
                                                                                                                                                                                                                                                            OperationStatus.ERROR),

    // ── 503 Service Unavailable ───────────────────────────────
    MAX_CONNECTIONS_REACHED("503-1", "Max number of connections reached", OperationStatus.ERROR), NO_QUORUM("503-2",
            "Cluster does not have a write quorum", OperationStatus.ERROR), REPLICATION_TIMEOUT("503-3",
                    "Timed out waiting for the replication quorum", OperationStatus.ERROR), OWNER_UNREACHABLE("503-4",
                            "The collection's owner node is unreachable", OperationStatus.ERROR), ADMIN_SYNCING("503-5",
                                    "Admin coordinator is synchronizing, retry shortly",
                                    OperationStatus.ERROR), SCRIPT_CONCURRENCY_LIMIT("503-6",
                                            "Too many scripts running, retry shortly", OperationStatus.ERROR);

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

    public static ErrorCode byCode(String code) {
        if (code == null) {
            return null;
        }
        for (final var errorCode : values()) {
            if (errorCode.code.equals(code)) {
                return errorCode;
            }
        }
        return null;
    }
}
