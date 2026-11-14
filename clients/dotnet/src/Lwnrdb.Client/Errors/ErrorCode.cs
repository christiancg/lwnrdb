using System;
using System.Collections.Frozen;
using System.Collections.Generic;
using System.Linq;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Errors;

public sealed class ErrorCode
{
    public static readonly ErrorCode ValidationError = new("400-1", "VALIDATION_ERROR", Status.Error, false, null);
    public static readonly ErrorCode EntryTooLarge = new("400-2", "ENTRY_TOO_LARGE", Status.Error, false, "Entry size exceeds maximum allowed size");
    public static readonly ErrorCode DuplicateId = new("400-3", "DUPLICATE_ID", Status.Error, false, "Duplicate _id in bulk save request");
    public static readonly ErrorCode CannotDeleteLastAdmin = new("400-4", "CANNOT_DELETE_LAST_ADMIN", Status.Error, false, "Cannot delete the last admin user");
    public static readonly ErrorCode CannotDemoteLastAdmin = new("400-5", "CANNOT_DEMOTE_LAST_ADMIN", Status.Error, false, "Cannot demote the last admin user");
    public static readonly ErrorCode CurrentPasswordIncorrect = new("400-6", "CURRENT_PASSWORD_INCORRECT", Status.Error, false, "Current password is incorrect");
    public static readonly ErrorCode SchemaValidationFailed = new("400-7", "SCHEMA_VALIDATION_FAILED", Status.Error, false, "Document does not comply with the collection schema");
    public static readonly ErrorCode InvalidSchema = new("400-8", "INVALID_SCHEMA", Status.Error, false, "The provided JSON schema is not valid");
    public static readonly ErrorCode ScriptFailed = new("400-9", "SCRIPT_FAILED", Status.Error, false, "Script execution failed");
    public static readonly ErrorCode ScriptTooLarge = new("400-10", "SCRIPT_TOO_LARGE", Status.Error, false, "Script exceeds the maximum allowed size");
    public static readonly ErrorCode ScriptLimitExceeded = new("400-11", "SCRIPT_LIMIT_EXCEEDED", Status.Error, false, "Script exceeded a sandbox limit");
    public static readonly ErrorCode ScriptMemoryExceeded = new("400-12", "SCRIPT_MEMORY_EXCEEDED", Status.Error, false, "Script exceeded its memory budget");
    public static readonly ErrorCode InvalidProcedure = new("400-13", "INVALID_PROCEDURE", Status.Error, false, "The procedure source could not be parsed");
    public static readonly ErrorCode InvalidTrigger = new("400-14", "INVALID_TRIGGER", Status.Error, false, "The trigger definition is not valid");
    public static readonly ErrorCode ScriptResultTooLarge = new("400-15", "SCRIPT_RESULT_TOO_LARGE", Status.Error, false, "Script result exceeds the maximum allowed size");
    public static readonly ErrorCode InvalidSchedule = new("400-16", "INVALID_SCHEDULE", Status.Error, false, "The schedule definition is not valid");
    public static readonly ErrorCode TooManySchedules = new("400-17", "TOO_MANY_SCHEDULES", Status.Error, false, "The database already has the maximum number of schedules");
    public static readonly ErrorCode ProcedureImportNotFound = new("400-18", "PROCEDURE_IMPORT_NOT_FOUND", Status.Error, false, "The procedure imports a procedure that does not exist");
    public static readonly ErrorCode ScriptNotAllowedInListen = new("400-19", "SCRIPT_NOT_ALLOWED_IN_LISTEN", Status.Error, false, "A SCRIPT operator is not allowed in a LISTEN pipeline");
    public static readonly ErrorCode ScriptResultPending = new("400-20", "SCRIPT_RESULT_PENDING", Status.Error, false, "The script's result promise never settled");
    public static readonly ErrorCode BeforeHookRejected = new("400-21", "BEFORE_HOOK_REJECTED", Status.Error, false, "A before trigger rejected this write");
    public static readonly ErrorCode RequestTooLarge = new("400-22", "REQUEST_TOO_LARGE", Status.Error, false, "The request exceeds the maximum allowed size");
    public static readonly ErrorCode SchemaValidationBudgetExceeded = new("400-23", "SCHEMA_VALIDATION_BUDGET_EXCEEDED", Status.Error, false, "Schema validation exceeded its evaluation budget");
    public static readonly ErrorCode SchemaTooComplex = new("400-24", "SCHEMA_TOO_COMPLEX", Status.Error, false, "The schema expands past the complexity limit");
    public static readonly ErrorCode TooManyListens = new("400-25", "TOO_MANY_LISTENS", Status.Error, false, "Too many listen registrations");
    public static readonly ErrorCode TransactionTooLarge = new("400-26", "TRANSACTION_TOO_LARGE", Status.Error, false, "The transaction would exceed its size limit");
    public static readonly ErrorCode FilterOperandLimit = new("400-27", "FILTER_OPERAND_LIMIT", Status.Error, false, "The filter has more operands than allowed");
    public static readonly ErrorCode MapResultTooLarge = new("400-28", "MAP_RESULT_TOO_LARGE", Status.Error, false, "A MAP result exceeds the maximum entry size");
    public static readonly ErrorCode ScheduleTimeoutTooLarge = new("400-29", "SCHEDULE_TIMEOUT_TOO_LARGE", Status.Error, false, "The schedule timeout exceeds the allowed maximum");
    public static readonly ErrorCode ScriptArrayTooLarge = new("400-30", "SCRIPT_ARRAY_TOO_LARGE", Status.Error, false, "A script array exceeds the maximum length");
    public static readonly ErrorCode SchemaPatternTooLong = new("400-31", "SCHEMA_PATTERN_TOO_LONG", Status.Error, false, "A schema regular expression exceeds the maximum length");
    public static readonly ErrorCode TooManyTriggers = new("400-32", "TOO_MANY_TRIGGERS", Status.Error, false, "The collection has the maximum number of triggers");
    public static readonly ErrorCode TooManySchedulesForUser = new("400-33", "TOO_MANY_SCHEDULES_FOR_USER", Status.Error, false, "The user has the maximum number of schedules");
    public static readonly ErrorCode TooManyProcedures = new("400-34", "TOO_MANY_PROCEDURES", Status.Error, false, "The database has the maximum number of procedures");
    public static readonly ErrorCode ScheduleIntervalTooSmall = new("400-35", "SCHEDULE_INTERVAL_TOO_SMALL", Status.Error, false, "The schedule interval is below the allowed minimum");
    public static readonly ErrorCode AggregateResultTooLarge = new("400-36", "AGGREGATE_RESULT_TOO_LARGE", Status.Error, false, "The aggregation result exceeds the maximum result size");
    public static readonly ErrorCode ListenRequestTooLarge = new("400-37", "LISTEN_REQUEST_TOO_LARGE", Status.Error, false, "The listen request exceeds the maximum size a listen may retain");
    public static readonly ErrorCode TooManyListensForUser = new("400-38", "TOO_MANY_LISTENS_FOR_USER", Status.Error, false, "The user has the maximum number of listen registrations");
    public static readonly ErrorCode SchemaTooManyPatterns = new("400-39", "SCHEMA_TOO_MANY_PATTERNS", Status.Error, false, "The schema has more regular expressions than allowed");
    public static readonly ErrorCode TooManyCollectionsForDatabase = new("400-40", "TOO_MANY_COLLECTIONS_FOR_DATABASE", Status.Error, false, "The database has the maximum number of collections");
    public static readonly ErrorCode TooManyDatabases = new("400-41", "TOO_MANY_DATABASES", Status.Error, false, "The server has the maximum number of databases");
    public static readonly ErrorCode TooManyIndexesForCollection = new("400-42", "TOO_MANY_INDEXES_FOR_COLLECTION", Status.Error, false, "The collection has the maximum number of indexes");
    public static readonly ErrorCode InvalidCommand = new("400-43", "INVALID_COMMAND", Status.Error, false, "The command is not valid");
    public static readonly ErrorCode DeleteMatchesTooMany = new("400-44", "DELETE_MATCHES_TOO_MANY", Status.Error, false, "The delete condition matches more documents than allowed");
    public static readonly ErrorCode UpdateMatchesTooMany = new("400-45", "UPDATE_MATCHES_TOO_MANY", Status.Error, false, "The update condition matches more documents than allowed");
    public static readonly ErrorCode MustAuthenticateFirst = new("401-1", "MUST_AUTHENTICATE_FIRST", Status.Unauthenticated, false, "Must authenticate first");
    public static readonly ErrorCode UserNoLongerExists = new("401-2", "USER_NO_LONGER_EXISTS", Status.Unauthenticated, false, "User no longer exists");
    public static readonly ErrorCode WrongCredentials = new("401-3", "WRONG_CREDENTIALS", Status.Error, false, "The user doesn't exist or the wrong credentials have been provided");
    public static readonly ErrorCode TooManyFailedAttempts = new("401-4", "TOO_MANY_FAILED_ATTEMPTS", Status.Unauthenticated, false, "Too many failed authentication attempts on this connection");
    public static readonly ErrorCode NoPermissions = new("403-1", "NO_PERMISSIONS", Status.Forbidden, false, "Action is forbidden, no permissions");
    public static readonly ErrorCode ScriptsDisabled = new("403-2", "SCRIPTS_DISABLED", Status.Forbidden, false, "Script execution is disabled on this server");
    public static readonly ErrorCode FaultInjectionDisabled = new("403-3", "FAULT_INJECTION_DISABLED", Status.Forbidden, false, "Fault injection is disabled on this server");
    public static readonly ErrorCode ProcedureBoundByOtherDefiner = new("403-4", "PROCEDURE_BOUND_BY_OTHER_DEFINER", Status.Forbidden, false, "Procedure is bound by a trigger or schedule that runs as another user");
    public static readonly ErrorCode DefinitionOwnedByOtherDefiner = new("403-5", "DEFINITION_OWNED_BY_OTHER_DEFINER", Status.Forbidden, false, "The trigger or schedule belongs to another user; only that user, a database owner or an admin may change it");
    public static readonly ErrorCode SourceRequiresManage = new("403-6", "SOURCE_REQUIRES_MANAGE", Status.Forbidden, false, "Procedure source requires script MANAGE");
    public static readonly ErrorCode UserNotFound = new("404-1", "USER_NOT_FOUND", Status.NotFound, false, "User not found");
    public static readonly ErrorCode EntryNotFound = new("404-2", "ENTRY_NOT_FOUND", Status.NotFound, false, "Entry not found");
    public static readonly ErrorCode NoResults = new("404-3", "NO_RESULTS", Status.NotFound, false, "No results");
    public static readonly ErrorCode DatabaseNotFound = new("404-4", "DATABASE_NOT_FOUND", Status.NotFound, false, "Database not found");
    public static readonly ErrorCode NoUsersFound = new("404-5", "NO_USERS_FOUND", Status.NotFound, false, "No users found");
    public static readonly ErrorCode IndexNotFound = new("404-6", "INDEX_NOT_FOUND", Status.NotFound, false, "No index registered for the specified field");
    public static readonly ErrorCode ListenNotFound = new("404-7", "LISTEN_NOT_FOUND", Status.NotFound, false, "Listen registration not found");
    public static readonly ErrorCode ProcedureNotFound = new("404-8", "PROCEDURE_NOT_FOUND", Status.NotFound, false, "Procedure not found");
    public static readonly ErrorCode TriggerNotFound = new("404-9", "TRIGGER_NOT_FOUND", Status.NotFound, false, "Trigger not found");
    public static readonly ErrorCode CollectionNotFound = new("404-11", "COLLECTION_NOT_FOUND", Status.NotFound, false, "Collection not found");
    public static readonly ErrorCode SchemaNotFound = new("404-12", "SCHEMA_NOT_FOUND", Status.NotFound, false, "The collection has no schema");
    public static readonly ErrorCode SnapshotNotFound = new("404-13", "SNAPSHOT_NOT_FOUND", Status.NotFound, false, "The snapshot does not exist: it was released or expired, or it belongs to another connection");
    public static readonly ErrorCode ScriptTimeout = new("408-1", "SCRIPT_TIMEOUT", Status.Error, false, "Script exceeded its time budget");
    public static readonly ErrorCode ScriptCancelled = new("408-2", "SCRIPT_CANCELLED", Status.Error, false, "Script was cancelled");
    public static readonly ErrorCode FaultNotReached = new("408-3", "FAULT_NOT_REACHED", Status.Error, false, "The armed fault point was not reached in time");
    public static readonly ErrorCode AuthenticationTimeout = new("408-4", "AUTHENTICATION_TIMEOUT", Status.Error, false, "The connection did not authenticate in time");
    public static readonly ErrorCode TransactionTooOld = new("408-5", "TRANSACTION_TOO_OLD", Status.Error, false, "The transaction was rolled back after reaching its maximum age");
    public static readonly ErrorCode AggregateTimeout = new("408-6", "AGGREGATE_TIMEOUT", Status.Error, false, "The aggregation exceeded its time budget");
    public static readonly ErrorCode UserAlreadyExists = new("409-1", "USER_ALREADY_EXISTS", Status.Error, false, "User already exists");
    public static readonly ErrorCode DatabaseAlreadyExists = new("409-2", "DATABASE_ALREADY_EXISTS", Status.Error, false, "Database already exists");
    public static readonly ErrorCode TransactionAlreadyActive = new("409-3", "TRANSACTION_ALREADY_ACTIVE", Status.Error, false, "A transaction is already in progress for this connection");
    public static readonly ErrorCode NoActiveTransaction = new("409-4", "NO_ACTIVE_TRANSACTION", Status.Error, false, "No active transaction for this connection");
    public static readonly ErrorCode TransactionLockTimeout = new("409-5", "TRANSACTION_LOCK_TIMEOUT", Status.Error, false, "Could not acquire the collection lock in time; transaction aborted");
    public static readonly ErrorCode OperationNotAllowedInTransaction = new("409-6", "OPERATION_NOT_ALLOWED_IN_TRANSACTION", Status.Error, false, "Operation not allowed while a transaction is open");
    public static readonly ErrorCode TransactionAborted = new("409-7", "TRANSACTION_ABORTED", Status.Error, false, "Transaction aborted: a participant could not prepare");
    public static readonly ErrorCode ProcedureVersionConflict = new("409-8", "PROCEDURE_VERSION_CONFLICT", Status.Error, false, "The procedure or trigger was modified by someone else");
    public static readonly ErrorCode TransactionNotUsable = new("409-9", "TRANSACTION_NOT_USABLE", Status.Error, false, "The transaction was aborted and must be rolled back before continuing");
    public static readonly ErrorCode TransactionIndeterminate = new("409-10", "TRANSACTION_INDETERMINATE", Status.Error, false, "The transaction's outcome is indeterminate: part of it did not apply and recovery will re-drive it");
    public static readonly ErrorCode NameCollidesOnDisk = new("409-11", "NAME_COLLIDES_ON_DISK", Status.Error, false, "A name that differs only by case already exists and would share storage with it");
    public static readonly ErrorCode TransactionPreviousUnresolved = new("409-12", "TRANSACTION_PREVIOUS_UNRESOLVED", Status.Error, false, "A previous transaction on this connection is still being resolved on a participant; retry once it finishes");
    public static readonly ErrorCode TransactionSliceLost = new("409-13", "TRANSACTION_SLICE_LOST", Status.Error, false, "A participant no longer holds this transaction's buffered writes (it restarted or reaped the session); roll back and retry");
    public static readonly ErrorCode TransactionAlreadyCommitted = new("409-14", "TRANSACTION_ALREADY_COMMITTED", Status.Error, false, "The transaction already committed on its owner; it can no longer be rolled back");
    public static readonly ErrorCode PrimaryKeyRebuildRefused = new("409-15", "PRIMARY_KEY_REBUILD_REFUSED", Status.Error, false, "The primary-key index cannot be rebuilt from the page files");
    public static readonly ErrorCode TransactionIdleTimeout = new("409-16", "TRANSACTION_IDLE_TIMEOUT", Status.Error, false, "The transaction was rolled back after being idle too long");
    public static readonly ErrorCode SnapshotAborted = new("409-17", "SNAPSHOT_ABORTED", Status.Error, false, "The snapshot was aborted and holds nothing any more; open a new one");
    public static readonly ErrorCode ListenEnded = new("410-1", "LISTEN_ENDED", Status.NotFound, false, "The listen ended");
    public static readonly ErrorCode OutboundBacklog = new("413-1", "OUTBOUND_BACKLOG", Status.Error, false, "The connection's outbound buffer overflowed");
    public static readonly ErrorCode NotCollectionOwner = new("421-1", "NOT_COLLECTION_OWNER", Status.Error, false, "This node is not the owner of the target collection");
    public static readonly ErrorCode TransactionReadSpansNodes = new("421-3", "TRANSACTION_READ_SPANS_NODES", Status.Error, false, "This read joins collections the transaction wrote on different nodes");
    public static readonly ErrorCode AuthenticationError = new("500-1", "AUTHENTICATION_ERROR", Status.Error, false, "Error during authentication");
    public static readonly ErrorCode ErrorCreatingUser = new("500-2", "ERROR_CREATING_USER", Status.Error, false, "Error creating user");
    public static readonly ErrorCode ErrorDeletingUser = new("500-3", "ERROR_DELETING_USER", Status.Error, false, "Error deleting user");
    public static readonly ErrorCode ErrorChangingPassword = new("500-4", "ERROR_CHANGING_PASSWORD", Status.Error, false, "Error changing password");
    public static readonly ErrorCode ErrorChangingPermissions = new("500-5", "ERROR_CHANGING_PERMISSIONS", Status.Error, false, "Error changing permissions");
    public static readonly ErrorCode ErrorBulkSaving = new("500-6", "ERROR_BULK_SAVING", Status.Error, false, "Error while saving entries");
    public static readonly ErrorCode ErrorSaving = new("500-7", "ERROR_SAVING", Status.Error, false, "Error while saving entry");
    public static readonly ErrorCode ErrorRetrieving = new("500-8", "ERROR_RETRIEVING", Status.Error, false, "Error while retrieving entry");
    public static readonly ErrorCode ErrorAggregating = new("500-9", "ERROR_AGGREGATING", Status.Error, false, "Error while processing aggregation");
    public static readonly ErrorCode ErrorDeleting = new("500-10", "ERROR_DELETING", Status.Error, false, "Error while deleting entry");
    public static readonly ErrorCode ErrorCreatingDatabase = new("500-11", "ERROR_CREATING_DATABASE", Status.Error, false, "Error while creating database");
    public static readonly ErrorCode ErrorUpdatingDatabaseOwners = new("500-12", "ERROR_UPDATING_DATABASE_OWNERS", Status.Error, false, "Error updating database owners");
    public static readonly ErrorCode ErrorDroppingDatabase = new("500-13", "ERROR_DROPPING_DATABASE", Status.Error, false, "Error while dropping database");
    public static readonly ErrorCode ErrorListingDatabases = new("500-14", "ERROR_LISTING_DATABASES", Status.Error, false, "Error while listing databases");
    public static readonly ErrorCode ErrorCreatingCollection = new("500-15", "ERROR_CREATING_COLLECTION", Status.Error, false, "Error while creating collection");
    public static readonly ErrorCode ErrorDroppingCollection = new("500-16", "ERROR_DROPPING_COLLECTION", Status.Error, false, "Error while dropping collection");
    public static readonly ErrorCode ErrorListingCollections = new("500-17", "ERROR_LISTING_COLLECTIONS", Status.Error, false, "Error while listing collections");
    public static readonly ErrorCode ErrorCreatingIndex = new("500-18", "ERROR_CREATING_INDEX", Status.Error, false, "Error while creating index");
    public static readonly ErrorCode ErrorListingUsers = new("500-19", "ERROR_LISTING_USERS", Status.Error, false, "Error listing users");
    public static readonly ErrorCode ErrorDroppingIndex = new("500-20", "ERROR_DROPPING_INDEX", Status.Error, false, "Error while dropping index");
    public static readonly ErrorCode ErrorReindexing = new("500-21", "ERROR_REINDEXING", Status.Error, false, "Error while reindexing");
    public static readonly ErrorCode ErrorGatheringStats = new("500-22", "ERROR_GATHERING_STATS", Status.Error, false, "Error while gathering database stats");
    public static readonly ErrorCode ErrorListen = new("500-23", "ERROR_LISTEN", Status.Error, false, "Error while processing listen operation");
    public static readonly ErrorCode ErrorTransaction = new("500-24", "ERROR_TRANSACTION", Status.Error, false, "Error while processing transaction operation");
    public static readonly ErrorCode ErrorSavingSchema = new("500-25", "ERROR_SAVING_SCHEMA", Status.Error, false, "Error while saving collection schema");
    public static readonly ErrorCode ErrorDeletingSchema = new("500-26", "ERROR_DELETING_SCHEMA", Status.Error, false, "Error while deleting collection schema");
    public static readonly ErrorCode ErrorSavingProcedure = new("500-27", "ERROR_SAVING_PROCEDURE", Status.Error, false, "Error while saving the procedure");
    public static readonly ErrorCode ErrorDeletingProcedure = new("500-28", "ERROR_DELETING_PROCEDURE", Status.Error, false, "Error while deleting the procedure");
    public static readonly ErrorCode ErrorSavingTrigger = new("500-29", "ERROR_SAVING_TRIGGER", Status.Error, false, "Error while saving the trigger");
    public static readonly ErrorCode ErrorDeletingTrigger = new("500-30", "ERROR_DELETING_TRIGGER", Status.Error, false, "Error while deleting the trigger");
    public static readonly ErrorCode ErrorSavingSchedule = new("500-31", "ERROR_SAVING_SCHEDULE", Status.Error, false, "Error while saving the schedule");
    public static readonly ErrorCode ErrorDeletingSchedule = new("500-32", "ERROR_DELETING_SCHEDULE", Status.Error, false, "Error while deleting the schedule");
    public static readonly ErrorCode TransactionHalfApplied = new("500-33", "TRANSACTION_HALF_APPLIED", Status.Error, false, "A committed transaction could not be fully applied; its collections are fenced until it is finished");
    public static readonly ErrorCode WriteLoggedNotApplied = new("500-34", "WRITE_LOGGED_NOT_APPLIED", Status.Error, false, "The write was logged durably but could not be applied; it is applied before the collection is next used, or at the next startup, so it may already have taken effect");
    public static readonly ErrorCode ErrorGatheringServerInfo = new("500-35", "ERROR_GATHERING_SERVER_INFO", Status.Error, false, "Error while gathering server info");
    public static readonly ErrorCode ErrorStartingSnapshot = new("500-36", "ERROR_STARTING_SNAPSHOT", Status.Error, false, "Error while opening the snapshot");
    public static readonly ErrorCode ErrorReadingSnapshot = new("500-37", "ERROR_READING_SNAPSHOT", Status.Error, false, "Error while reading the snapshot");
    public static readonly ErrorCode MaxConnectionsReached = new("503-1", "MAX_CONNECTIONS_REACHED", Status.Error, false, "Max number of connections reached");
    public static readonly ErrorCode NoQuorum = new("503-2", "NO_QUORUM", Status.Error, true, "Cluster does not have a write quorum");
    public static readonly ErrorCode ReplicationTimeout = new("503-3", "REPLICATION_TIMEOUT", Status.Error, false, "Timed out waiting for the replication quorum");
    public static readonly ErrorCode OwnerUnreachable = new("503-4", "OWNER_UNREACHABLE", Status.Error, true, "The collection's owner node is unreachable");
    public static readonly ErrorCode AdminSyncing = new("503-5", "ADMIN_SYNCING", Status.Error, true, "Admin coordinator is synchronizing, retry shortly");
    public static readonly ErrorCode ScriptConcurrencyLimit = new("503-6", "SCRIPT_CONCURRENCY_LIMIT", Status.Error, false, "Too many scripts running, retry shortly");
    public static readonly ErrorCode ScriptOutcomeUnknown = new("503-7", "SCRIPT_OUTCOME_UNKNOWN", Status.Error, false, "The node the script was placed on did not report an outcome; it was not run again in case it already had");
    public static readonly ErrorCode WriteOutcomeUnknown = new("503-8", "WRITE_OUTCOME_UNKNOWN", Status.Error, false, "The collection's owner did not report an outcome; the write may already have been applied, so retrying is only safe for an idempotent write");
    public static readonly ErrorCode AdminCoordinatorUnavailable = new("503-9", "ADMIN_COORDINATOR_UNAVAILABLE", Status.Error, true, "The admin coordinator could not be resolved, retry shortly");
    public static readonly ErrorCode SchemaUnavailable = new("503-11", "SCHEMA_UNAVAILABLE", Status.Error, false, "The collection's schema could not be read, so the write cannot be validated; retry shortly");
    public static readonly ErrorCode CollectionNotReady = new("503-10", "COLLECTION_NOT_READY", Status.Error, true, "The collection has not reached this node yet, retry shortly");
    public static readonly ErrorCode ServerShuttingDown = new("503-13", "SERVER_SHUTTING_DOWN", Status.Error, false, "The server is shutting down, retry against another node or later");
    public static readonly ErrorCode TooManyUnauthenticatedConnections = new("503-14", "TOO_MANY_UNAUTHENTICATED_CONNECTIONS", Status.Error, false, "Too many unauthenticated connections from this address");
    public static readonly ErrorCode ListenRetainedBytesExhausted = new("503-15", "LISTEN_RETAINED_BYTES_EXHAUSTED", Status.Error, false, "The server holds the maximum bytes of listen registrations, retry later");
    public static readonly ErrorCode BackgroundBacklog = new("503-16", "BACKGROUND_BACKLOG", Status.Error, true, "The background index queue is full and nothing was written; retry shortly");
    public static readonly ErrorCode ResponseBudgetExhausted = new("503-17", "RESPONSE_BUDGET_EXHAUSTED", Status.Error, true, "The server holds the maximum bytes of unsent responses; retry shortly");
    public static readonly ErrorCode CollectionFenced = new("503-18", "COLLECTION_FENCED", Status.Error, true, "The collection is held by a committed transaction that has not finished applying; retry shortly");
    public static readonly ErrorCode WritesNotApplied = new("503-19", "WRITES_NOT_APPLIED", Status.Error, true, "The collection holds logged writes that could not be applied yet, so it cannot be read consistently; retry shortly");
    public static readonly ErrorCode AggregateBudgetExhausted = new("503-20", "AGGREGATE_BUDGET_EXHAUSTED", Status.Error, true, "The server holds the maximum bytes of running aggregations; retry shortly");
    public static readonly ErrorCode TransactionBudgetExhausted = new("503-21", "TRANSACTION_BUDGET_EXHAUSTED", Status.Error, true, "The server holds the maximum bytes of buffered transactions; the write was not buffered, retry shortly");
    public static readonly ErrorCode SnapshotLimitReached = new("503-22", "SNAPSHOT_LIMIT_REACHED", Status.Error, true, "The server holds the maximum number of open snapshots; retry shortly");

    private static readonly FrozenDictionary<string, ErrorCode> ByCode = typeof(ErrorCode)
        .GetFields(System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static)
        .Where(field => field.FieldType == typeof(ErrorCode))
        .Select(field => (ErrorCode)field.GetValue(null)!)
        .ToFrozenDictionary(code => code.Code, StringComparer.Ordinal);

    private ErrorCode(string code, string name, Status status, bool isRetryable, string? message)
    {
        Code = code;
        Name = name;
        Status = status;
        IsRetryable = isRetryable;
        Message = message;
    }

    public string Code { get; }

    public string Name { get; }

    public Status Status { get; }

    public bool IsRetryable { get; }

    public string? Message { get; }

    public static IReadOnlyCollection<ErrorCode> All => ByCode.Values;

    public static ErrorCode? Of(string? rawCode) =>
        rawCode is not null && ByCode.TryGetValue(rawCode, out var code) ? code : null;

    public override string ToString() => $"{Name} ({Code})";
}
