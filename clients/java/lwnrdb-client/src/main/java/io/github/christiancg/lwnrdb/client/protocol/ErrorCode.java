package io.github.christiancg.lwnrdb.client.protocol;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum ErrorCode {
    // @formatter:off
    VALIDATION_ERROR("400-1", Status.ERROR, false, null),
    ENTRY_TOO_LARGE("400-2", Status.ERROR, false, "Entry size exceeds maximum allowed size"),
    DUPLICATE_ID("400-3", Status.ERROR, false, "Duplicate _id in bulk save request"),
    CANNOT_DELETE_LAST_ADMIN("400-4", Status.ERROR, false, "Cannot delete the last admin user"),
    CANNOT_DEMOTE_LAST_ADMIN("400-5", Status.ERROR, false, "Cannot demote the last admin user"),
    CURRENT_PASSWORD_INCORRECT("400-6", Status.ERROR, false, "Current password is incorrect"),
    SCHEMA_VALIDATION_FAILED("400-7", Status.ERROR, false, "Document does not comply with the collection schema"),
    INVALID_SCHEMA("400-8", Status.ERROR, false, "The provided JSON schema is not valid"),
    SCRIPT_FAILED("400-9", Status.ERROR, false, "Script execution failed"),
    SCRIPT_TOO_LARGE("400-10", Status.ERROR, false, "Script exceeds the maximum allowed size"),
    SCRIPT_LIMIT_EXCEEDED("400-11", Status.ERROR, false, "Script exceeded a sandbox limit"),
    SCRIPT_MEMORY_EXCEEDED("400-12", Status.ERROR, false, "Script exceeded its memory budget"),
    INVALID_PROCEDURE("400-13", Status.ERROR, false, "The procedure source could not be parsed"),
    INVALID_TRIGGER("400-14", Status.ERROR, false, "The trigger definition is not valid"),
    SCRIPT_RESULT_TOO_LARGE("400-15", Status.ERROR, false, "Script result exceeds the maximum allowed size"),
    INVALID_SCHEDULE("400-16", Status.ERROR, false, "The schedule definition is not valid"),
    TOO_MANY_SCHEDULES("400-17", Status.ERROR, false, "The database already has the maximum number of schedules"),
    PROCEDURE_IMPORT_NOT_FOUND("400-18", Status.ERROR, false, "The procedure imports a procedure that does not exist"),
    SCRIPT_NOT_ALLOWED_IN_LISTEN("400-19", Status.ERROR, false, "A SCRIPT operator is not allowed in a LISTEN pipeline"),
    SCRIPT_RESULT_PENDING("400-20", Status.ERROR, false, "The script's result promise never settled"),
    BEFORE_HOOK_REJECTED("400-21", Status.ERROR, false, "A before trigger rejected this write"),
    REQUEST_TOO_LARGE("400-22", Status.ERROR, false, "The request exceeds the maximum allowed size"),
    SCHEMA_VALIDATION_BUDGET_EXCEEDED("400-23", Status.ERROR, false, "Schema validation exceeded its evaluation budget"),
    SCHEMA_TOO_COMPLEX("400-24", Status.ERROR, false, "The schema expands past the complexity limit"),
    TOO_MANY_LISTENS("400-25", Status.ERROR, false, "Too many listen registrations"),
    TRANSACTION_TOO_LARGE("400-26", Status.ERROR, false, "The transaction would exceed its size limit"),
    FILTER_OPERAND_LIMIT("400-27", Status.ERROR, false, "The filter has more operands than allowed"),
    MAP_RESULT_TOO_LARGE("400-28", Status.ERROR, false, "A MAP result exceeds the maximum entry size"),
    SCHEDULE_TIMEOUT_TOO_LARGE("400-29", Status.ERROR, false, "The schedule timeout exceeds the allowed maximum"),
    SCRIPT_ARRAY_TOO_LARGE("400-30", Status.ERROR, false, "A script array exceeds the maximum length"),
    SCHEMA_PATTERN_TOO_LONG("400-31", Status.ERROR, false, "A schema regular expression exceeds the maximum length"),
    TOO_MANY_TRIGGERS("400-32", Status.ERROR, false, "The collection has the maximum number of triggers"),
    TOO_MANY_SCHEDULES_FOR_USER("400-33", Status.ERROR, false, "The user has the maximum number of schedules"),
    TOO_MANY_PROCEDURES("400-34", Status.ERROR, false, "The database has the maximum number of procedures"),
    SCHEDULE_INTERVAL_TOO_SMALL("400-35", Status.ERROR, false, "The schedule interval is below the allowed minimum"),
    AGGREGATE_RESULT_TOO_LARGE("400-36", Status.ERROR, false, "The aggregation result exceeds the maximum result size"),
    LISTEN_REQUEST_TOO_LARGE("400-37", Status.ERROR, false, "The listen request exceeds the maximum size a listen may retain"),
    TOO_MANY_LISTENS_FOR_USER("400-38", Status.ERROR, false, "The user has the maximum number of listen registrations"),
    SCHEMA_TOO_MANY_PATTERNS("400-39", Status.ERROR, false, "The schema has more regular expressions than allowed"),
    TOO_MANY_COLLECTIONS_FOR_DATABASE("400-40", Status.ERROR, false, "The database has the maximum number of collections"),
    TOO_MANY_DATABASES("400-41", Status.ERROR, false, "The server has the maximum number of databases"),
    TOO_MANY_INDEXES_FOR_COLLECTION("400-42", Status.ERROR, false, "The collection has the maximum number of indexes"),
    INVALID_COMMAND("400-43", Status.ERROR, false, "The command is not valid"),
    DELETE_MATCHES_TOO_MANY("400-44", Status.ERROR, false, "The delete condition matches more documents than allowed"),
    UPDATE_MATCHES_TOO_MANY("400-45", Status.ERROR, false, "The update condition matches more documents than allowed"),
    MUST_AUTHENTICATE_FIRST("401-1", Status.UNAUTHENTICATED, false, "Must authenticate first"),
    USER_NO_LONGER_EXISTS("401-2", Status.UNAUTHENTICATED, false, "User no longer exists"),
    WRONG_CREDENTIALS("401-3", Status.ERROR, false, "The user doesn't exist or the wrong credentials have been provided"),
    TOO_MANY_FAILED_ATTEMPTS("401-4", Status.UNAUTHENTICATED, false, "Too many failed authentication attempts on this connection"),
    NO_PERMISSIONS("403-1", Status.FORBIDDEN, false, "Action is forbidden, no permissions"),
    SCRIPTS_DISABLED("403-2", Status.FORBIDDEN, false, "Script execution is disabled on this server"),
    FAULT_INJECTION_DISABLED("403-3", Status.FORBIDDEN, false, "Fault injection is disabled on this server"),
    PROCEDURE_BOUND_BY_OTHER_DEFINER("403-4", Status.FORBIDDEN, false, "Procedure is bound by a trigger or schedule that runs as another user"),
    DEFINITION_OWNED_BY_OTHER_DEFINER("403-5", Status.FORBIDDEN, false, "The trigger or schedule belongs to another user; only that user, a database owner or an admin may change it"),
    SOURCE_REQUIRES_MANAGE("403-6", Status.FORBIDDEN, false, "Procedure source requires script MANAGE"),
    USER_NOT_FOUND("404-1", Status.NOT_FOUND, false, "User not found"),
    ENTRY_NOT_FOUND("404-2", Status.NOT_FOUND, false, "Entry not found"),
    NO_RESULTS("404-3", Status.NOT_FOUND, false, "No results"),
    DATABASE_NOT_FOUND("404-4", Status.NOT_FOUND, false, "Database not found"),
    NO_USERS_FOUND("404-5", Status.NOT_FOUND, false, "No users found"),
    INDEX_NOT_FOUND("404-6", Status.NOT_FOUND, false, "No index registered for the specified field"),
    LISTEN_NOT_FOUND("404-7", Status.NOT_FOUND, false, "Listen registration not found"),
    PROCEDURE_NOT_FOUND("404-8", Status.NOT_FOUND, false, "Procedure not found"),
    TRIGGER_NOT_FOUND("404-9", Status.NOT_FOUND, false, "Trigger not found"),
    COLLECTION_NOT_FOUND("404-11", Status.NOT_FOUND, false, "Collection not found"),
    SCHEMA_NOT_FOUND("404-12", Status.NOT_FOUND, false, "The collection has no schema"),
    SNAPSHOT_NOT_FOUND("404-13", Status.NOT_FOUND, false, "The snapshot does not exist: it was released or expired, or it belongs to another connection"),
    SCRIPT_TIMEOUT("408-1", Status.ERROR, false, "Script exceeded its time budget"),
    SCRIPT_CANCELLED("408-2", Status.ERROR, false, "Script was cancelled"),
    FAULT_NOT_REACHED("408-3", Status.ERROR, false, "The armed fault point was not reached in time"),
    AUTHENTICATION_TIMEOUT("408-4", Status.ERROR, false, "The connection did not authenticate in time"),
    TRANSACTION_TOO_OLD("408-5", Status.ERROR, false, "The transaction was rolled back after reaching its maximum age"),
    AGGREGATE_TIMEOUT("408-6", Status.ERROR, false, "The aggregation exceeded its time budget"),
    USER_ALREADY_EXISTS("409-1", Status.ERROR, false, "User already exists"),
    DATABASE_ALREADY_EXISTS("409-2", Status.ERROR, false, "Database already exists"),
    TRANSACTION_ALREADY_ACTIVE("409-3", Status.ERROR, false, "A transaction is already in progress for this connection"),
    NO_ACTIVE_TRANSACTION("409-4", Status.ERROR, false, "No active transaction for this connection"),
    TRANSACTION_LOCK_TIMEOUT("409-5", Status.ERROR, false, "Could not acquire the collection lock in time; transaction aborted"),
    OPERATION_NOT_ALLOWED_IN_TRANSACTION("409-6", Status.ERROR, false, "Operation not allowed while a transaction is open"),
    TRANSACTION_ABORTED("409-7", Status.ERROR, false, "Transaction aborted: a participant could not prepare"),
    PROCEDURE_VERSION_CONFLICT("409-8", Status.ERROR, false, "The procedure or trigger was modified by someone else"),
    TRANSACTION_NOT_USABLE("409-9", Status.ERROR, false, "The transaction was aborted and must be rolled back before continuing"),
    TRANSACTION_INDETERMINATE("409-10", Status.ERROR, false, "The transaction's outcome is indeterminate: part of it did not apply and recovery will re-drive it"),
    NAME_COLLIDES_ON_DISK("409-11", Status.ERROR, false, "A name that differs only by case already exists and would share storage with it"),
    TRANSACTION_PREVIOUS_UNRESOLVED("409-12", Status.ERROR, false, "A previous transaction on this connection is still being resolved on a participant; retry once it finishes"),
    TRANSACTION_SLICE_LOST("409-13", Status.ERROR, false, "A participant no longer holds this transaction's buffered writes (it restarted or reaped the session); roll back and retry"),
    TRANSACTION_ALREADY_COMMITTED("409-14", Status.ERROR, false, "The transaction already committed on its owner; it can no longer be rolled back"),
    PRIMARY_KEY_REBUILD_REFUSED("409-15", Status.ERROR, false, "The primary-key index cannot be rebuilt from the page files"),
    TRANSACTION_IDLE_TIMEOUT("409-16", Status.ERROR, false, "The transaction was rolled back after being idle too long"),
    SNAPSHOT_ABORTED("409-17", Status.ERROR, false, "The snapshot was aborted and holds nothing any more; open a new one"),
    DOCUMENT_ALREADY_EXISTS("409-18", Status.ERROR, false, "A document with this _id already exists"),
    LISTEN_ENDED("410-1", Status.NOT_FOUND, false, "The listen ended"),
    OUTBOUND_BACKLOG("413-1", Status.ERROR, false, "The connection's outbound buffer overflowed"),
    NOT_COLLECTION_OWNER("421-1", Status.ERROR, false, "This node is not the owner of the target collection"),
    TRANSACTION_READ_SPANS_NODES("421-3", Status.ERROR, false, "This read joins collections the transaction wrote on different nodes"),
    AUTHENTICATION_ERROR("500-1", Status.ERROR, false, "Error during authentication"),
    ERROR_CREATING_USER("500-2", Status.ERROR, false, "Error creating user"),
    ERROR_DELETING_USER("500-3", Status.ERROR, false, "Error deleting user"),
    ERROR_CHANGING_PASSWORD("500-4", Status.ERROR, false, "Error changing password"),
    ERROR_CHANGING_PERMISSIONS("500-5", Status.ERROR, false, "Error changing permissions"),
    ERROR_BULK_SAVING("500-6", Status.ERROR, false, "Error while saving entries"),
    ERROR_SAVING("500-7", Status.ERROR, false, "Error while saving entry"),
    ERROR_RETRIEVING("500-8", Status.ERROR, false, "Error while retrieving entry"),
    ERROR_AGGREGATING("500-9", Status.ERROR, false, "Error while processing aggregation"),
    ERROR_DELETING("500-10", Status.ERROR, false, "Error while deleting entry"),
    ERROR_CREATING_DATABASE("500-11", Status.ERROR, false, "Error while creating database"),
    ERROR_UPDATING_DATABASE_OWNERS("500-12", Status.ERROR, false, "Error updating database owners"),
    ERROR_DROPPING_DATABASE("500-13", Status.ERROR, false, "Error while dropping database"),
    ERROR_LISTING_DATABASES("500-14", Status.ERROR, false, "Error while listing databases"),
    ERROR_CREATING_COLLECTION("500-15", Status.ERROR, false, "Error while creating collection"),
    ERROR_DROPPING_COLLECTION("500-16", Status.ERROR, false, "Error while dropping collection"),
    ERROR_LISTING_COLLECTIONS("500-17", Status.ERROR, false, "Error while listing collections"),
    ERROR_CREATING_INDEX("500-18", Status.ERROR, false, "Error while creating index"),
    ERROR_LISTING_USERS("500-19", Status.ERROR, false, "Error listing users"),
    ERROR_DROPPING_INDEX("500-20", Status.ERROR, false, "Error while dropping index"),
    ERROR_REINDEXING("500-21", Status.ERROR, false, "Error while reindexing"),
    ERROR_GATHERING_STATS("500-22", Status.ERROR, false, "Error while gathering database stats"),
    ERROR_LISTEN("500-23", Status.ERROR, false, "Error while processing listen operation"),
    ERROR_TRANSACTION("500-24", Status.ERROR, false, "Error while processing transaction operation"),
    ERROR_SAVING_SCHEMA("500-25", Status.ERROR, false, "Error while saving collection schema"),
    ERROR_DELETING_SCHEMA("500-26", Status.ERROR, false, "Error while deleting collection schema"),
    ERROR_SAVING_PROCEDURE("500-27", Status.ERROR, false, "Error while saving the procedure"),
    ERROR_DELETING_PROCEDURE("500-28", Status.ERROR, false, "Error while deleting the procedure"),
    ERROR_SAVING_TRIGGER("500-29", Status.ERROR, false, "Error while saving the trigger"),
    ERROR_DELETING_TRIGGER("500-30", Status.ERROR, false, "Error while deleting the trigger"),
    ERROR_SAVING_SCHEDULE("500-31", Status.ERROR, false, "Error while saving the schedule"),
    ERROR_DELETING_SCHEDULE("500-32", Status.ERROR, false, "Error while deleting the schedule"),
    TRANSACTION_HALF_APPLIED("500-33", Status.ERROR, false, "A committed transaction could not be fully applied; its collections are fenced until it is finished"),
    WRITE_LOGGED_NOT_APPLIED("500-34", Status.ERROR, false, "The write was logged durably but could not be applied; it is applied before the collection is next used, or at the next startup, so it may already have taken effect"),
    ERROR_GATHERING_SERVER_INFO("500-35", Status.ERROR, false, "Error while gathering server info"),
    ERROR_STARTING_SNAPSHOT("500-36", Status.ERROR, false, "Error while opening the snapshot"),
    ERROR_READING_SNAPSHOT("500-37", Status.ERROR, false, "Error while reading the snapshot"),
    MAX_CONNECTIONS_REACHED("503-1", Status.ERROR, false, "Max number of connections reached"),
    NO_QUORUM("503-2", Status.ERROR, true, "Cluster does not have a write quorum"),
    REPLICATION_TIMEOUT("503-3", Status.ERROR, false, "Timed out waiting for the replication quorum"),
    OWNER_UNREACHABLE("503-4", Status.ERROR, true, "The collection's owner node is unreachable"),
    ADMIN_SYNCING("503-5", Status.ERROR, true, "Admin coordinator is synchronizing, retry shortly"),
    SCRIPT_CONCURRENCY_LIMIT("503-6", Status.ERROR, false, "Too many scripts running, retry shortly"),
    SCRIPT_OUTCOME_UNKNOWN("503-7", Status.ERROR, false, "The node the script was placed on did not report an outcome; it was not run again in case it already had"),
    WRITE_OUTCOME_UNKNOWN("503-8", Status.ERROR, false, "The collection's owner did not report an outcome; the write may already have been applied, so retrying is only safe for an idempotent write"),
    ADMIN_COORDINATOR_UNAVAILABLE("503-9", Status.ERROR, true, "The admin coordinator could not be resolved, retry shortly"),
    SCHEMA_UNAVAILABLE("503-11", Status.ERROR, false, "The collection's schema could not be read, so the write cannot be validated; retry shortly"),
    COLLECTION_NOT_READY("503-10", Status.ERROR, true, "The collection has not reached this node yet, retry shortly"),
    SERVER_SHUTTING_DOWN("503-13", Status.ERROR, false, "The server is shutting down, retry against another node or later"),
    TOO_MANY_UNAUTHENTICATED_CONNECTIONS("503-14", Status.ERROR, false, "Too many unauthenticated connections from this address"),
    LISTEN_RETAINED_BYTES_EXHAUSTED("503-15", Status.ERROR, false, "The server holds the maximum bytes of listen registrations, retry later"),
    BACKGROUND_BACKLOG("503-16", Status.ERROR, true, "The background index queue is full and nothing was written; retry shortly"),
    RESPONSE_BUDGET_EXHAUSTED("503-17", Status.ERROR, true, "The server holds the maximum bytes of unsent responses; retry shortly"),
    COLLECTION_FENCED("503-18", Status.ERROR, true, "The collection is held by a committed transaction that has not finished applying; retry shortly"),
    WRITES_NOT_APPLIED("503-19", Status.ERROR, true, "The collection holds logged writes that could not be applied yet, so it cannot be read consistently; retry shortly"),
    AGGREGATE_BUDGET_EXHAUSTED("503-20", Status.ERROR, true, "The server holds the maximum bytes of running aggregations; retry shortly"),
    TRANSACTION_BUDGET_EXHAUSTED("503-21", Status.ERROR, true, "The server holds the maximum bytes of buffered transactions; the write was not buffered, retry shortly"),
    SNAPSHOT_LIMIT_REACHED("503-22", Status.ERROR, true, "The server holds the maximum number of open snapshots; retry shortly");
    // @formatter:on

    private static final Map<String, ErrorCode> BY_CODE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(ErrorCode::code, Function.identity()));

    private final String code;
    private final Status status;
    private final boolean retryable;
    private final String defaultMessage;

    ErrorCode(String code, Status status, boolean retryable, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.retryable = retryable;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public Status status() {
        return status;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Optional<String> defaultMessage() {
        return Optional.ofNullable(defaultMessage);
    }

    public static Optional<ErrorCode> fromCode(String code) {
        return Optional.ofNullable(code == null ? null : BY_CODE.get(code));
    }
}
