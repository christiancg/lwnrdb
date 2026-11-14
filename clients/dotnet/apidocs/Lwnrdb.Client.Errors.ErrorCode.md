One entry of the server's error catalogue: code, name, reply status and whether a retry is safe.

The catalogue is shared by every LWNRDB client (`clients/protocol/error-codes.json`). Codes read `NNN-N`, where the first number is the HTTP-like class: 400 invalid request, 401 authentication, 403 permission, 404 not found, 408 timeout, 409 conflict, 410 ended, 413 backlog, 421 not the owner, 500 server failure and 503 temporarily unavailable. The `ErrorCode` members are instances, not enumeration values.

## ValidationError
Code `400-1` (`VALIDATION_ERROR`). The request is not valid.

## EntryTooLarge
Code `400-2` (`ENTRY_TOO_LARGE`). Entry size exceeds maximum allowed size.

## DuplicateId
Code `400-3` (`DUPLICATE_ID`). Duplicate _id in bulk save request.

## CannotDeleteLastAdmin
Code `400-4` (`CANNOT_DELETE_LAST_ADMIN`). Cannot delete the last admin user.

## CannotDemoteLastAdmin
Code `400-5` (`CANNOT_DEMOTE_LAST_ADMIN`). Cannot demote the last admin user.

## CurrentPasswordIncorrect
Code `400-6` (`CURRENT_PASSWORD_INCORRECT`). Current password is incorrect.

## SchemaValidationFailed
Code `400-7` (`SCHEMA_VALIDATION_FAILED`). Document does not comply with the collection schema.

## InvalidSchema
Code `400-8` (`INVALID_SCHEMA`). The provided JSON schema is not valid.

## ScriptFailed
Code `400-9` (`SCRIPT_FAILED`). Script execution failed.

## ScriptTooLarge
Code `400-10` (`SCRIPT_TOO_LARGE`). Script exceeds the maximum allowed size.

## ScriptLimitExceeded
Code `400-11` (`SCRIPT_LIMIT_EXCEEDED`). Script exceeded a sandbox limit.

## ScriptMemoryExceeded
Code `400-12` (`SCRIPT_MEMORY_EXCEEDED`). Script exceeded its memory budget.

## InvalidProcedure
Code `400-13` (`INVALID_PROCEDURE`). The procedure source could not be parsed.

## InvalidTrigger
Code `400-14` (`INVALID_TRIGGER`). The trigger definition is not valid.

## ScriptResultTooLarge
Code `400-15` (`SCRIPT_RESULT_TOO_LARGE`). Script result exceeds the maximum allowed size.

## InvalidSchedule
Code `400-16` (`INVALID_SCHEDULE`). The schedule definition is not valid.

## TooManySchedules
Code `400-17` (`TOO_MANY_SCHEDULES`). The database already has the maximum number of schedules.

## ProcedureImportNotFound
Code `400-18` (`PROCEDURE_IMPORT_NOT_FOUND`). The procedure imports a procedure that does not exist.

## ScriptNotAllowedInListen
Code `400-19` (`SCRIPT_NOT_ALLOWED_IN_LISTEN`). A SCRIPT operator is not allowed in a LISTEN pipeline.

## ScriptResultPending
Code `400-20` (`SCRIPT_RESULT_PENDING`). The script's result promise never settled.

## BeforeHookRejected
Code `400-21` (`BEFORE_HOOK_REJECTED`). A before trigger rejected this write.

## RequestTooLarge
Code `400-22` (`REQUEST_TOO_LARGE`). The request exceeds the maximum allowed size.

## SchemaValidationBudgetExceeded
Code `400-23` (`SCHEMA_VALIDATION_BUDGET_EXCEEDED`). Schema validation exceeded its evaluation budget.

## SchemaTooComplex
Code `400-24` (`SCHEMA_TOO_COMPLEX`). The schema expands past the complexity limit.

## TooManyListens
Code `400-25` (`TOO_MANY_LISTENS`). Too many listen registrations.

## TransactionTooLarge
Code `400-26` (`TRANSACTION_TOO_LARGE`). The transaction would exceed its size limit.

## FilterOperandLimit
Code `400-27` (`FILTER_OPERAND_LIMIT`). The filter has more operands than allowed.

## MapResultTooLarge
Code `400-28` (`MAP_RESULT_TOO_LARGE`). A MAP result exceeds the maximum entry size.

## ScheduleTimeoutTooLarge
Code `400-29` (`SCHEDULE_TIMEOUT_TOO_LARGE`). The schedule timeout exceeds the allowed maximum.

## ScriptArrayTooLarge
Code `400-30` (`SCRIPT_ARRAY_TOO_LARGE`). A script array exceeds the maximum length.

## SchemaPatternTooLong
Code `400-31` (`SCHEMA_PATTERN_TOO_LONG`). A schema regular expression exceeds the maximum length.

## TooManyTriggers
Code `400-32` (`TOO_MANY_TRIGGERS`). The collection has the maximum number of triggers.

## TooManySchedulesForUser
Code `400-33` (`TOO_MANY_SCHEDULES_FOR_USER`). The user has the maximum number of schedules.

## TooManyProcedures
Code `400-34` (`TOO_MANY_PROCEDURES`). The database has the maximum number of procedures.

## ScheduleIntervalTooSmall
Code `400-35` (`SCHEDULE_INTERVAL_TOO_SMALL`). The schedule interval is below the allowed minimum.

## AggregateResultTooLarge
Code `400-36` (`AGGREGATE_RESULT_TOO_LARGE`). The aggregation result exceeds the maximum result size.

## ListenRequestTooLarge
Code `400-37` (`LISTEN_REQUEST_TOO_LARGE`). The listen request exceeds the maximum size a listen may retain.

## TooManyListensForUser
Code `400-38` (`TOO_MANY_LISTENS_FOR_USER`). The user has the maximum number of listen registrations.

## SchemaTooManyPatterns
Code `400-39` (`SCHEMA_TOO_MANY_PATTERNS`). The schema has more regular expressions than allowed.

## TooManyCollectionsForDatabase
Code `400-40` (`TOO_MANY_COLLECTIONS_FOR_DATABASE`). The database has the maximum number of collections.

## TooManyDatabases
Code `400-41` (`TOO_MANY_DATABASES`). The server has the maximum number of databases.

## TooManyIndexesForCollection
Code `400-42` (`TOO_MANY_INDEXES_FOR_COLLECTION`). The collection has the maximum number of indexes.

## InvalidCommand
Code `400-43` (`INVALID_COMMAND`). The command is not valid.

## DeleteMatchesTooMany
Code `400-44` (`DELETE_MATCHES_TOO_MANY`). The delete condition matches more documents than allowed.

## UpdateMatchesTooMany
Code `400-45` (`UPDATE_MATCHES_TOO_MANY`). The update condition matches more documents than allowed.

## MustAuthenticateFirst
Code `401-1` (`MUST_AUTHENTICATE_FIRST`). Must authenticate first.

## UserNoLongerExists
Code `401-2` (`USER_NO_LONGER_EXISTS`). User no longer exists.

## WrongCredentials
Code `401-3` (`WRONG_CREDENTIALS`). The user doesn't exist or the wrong credentials have been provided.

## TooManyFailedAttempts
Code `401-4` (`TOO_MANY_FAILED_ATTEMPTS`). Too many failed authentication attempts on this connection.

## NoPermissions
Code `403-1` (`NO_PERMISSIONS`). Action is forbidden, no permissions.

## ScriptsDisabled
Code `403-2` (`SCRIPTS_DISABLED`). Script execution is disabled on this server.

## FaultInjectionDisabled
Code `403-3` (`FAULT_INJECTION_DISABLED`). Fault injection is disabled on this server.

## ProcedureBoundByOtherDefiner
Code `403-4` (`PROCEDURE_BOUND_BY_OTHER_DEFINER`). Procedure is bound by a trigger or schedule that runs as another user.

## DefinitionOwnedByOtherDefiner
Code `403-5` (`DEFINITION_OWNED_BY_OTHER_DEFINER`). The trigger or schedule belongs to another user; only that user, a database owner or an admin may change it.

## SourceRequiresManage
Code `403-6` (`SOURCE_REQUIRES_MANAGE`). Procedure source requires script MANAGE.

## UserNotFound
Code `404-1` (`USER_NOT_FOUND`). User not found.

## EntryNotFound
Code `404-2` (`ENTRY_NOT_FOUND`). Entry not found.

## NoResults
Code `404-3` (`NO_RESULTS`). No results.

## DatabaseNotFound
Code `404-4` (`DATABASE_NOT_FOUND`). Database not found.

## NoUsersFound
Code `404-5` (`NO_USERS_FOUND`). No users found.

## IndexNotFound
Code `404-6` (`INDEX_NOT_FOUND`). No index registered for the specified field.

## ListenNotFound
Code `404-7` (`LISTEN_NOT_FOUND`). Listen registration not found.

## ProcedureNotFound
Code `404-8` (`PROCEDURE_NOT_FOUND`). Procedure not found.

## TriggerNotFound
Code `404-9` (`TRIGGER_NOT_FOUND`). Trigger not found.

## CollectionNotFound
Code `404-11` (`COLLECTION_NOT_FOUND`). Collection not found.

## SchemaNotFound
Code `404-12` (`SCHEMA_NOT_FOUND`). The collection has no schema.

## SnapshotNotFound
Code `404-13` (`SNAPSHOT_NOT_FOUND`). The snapshot does not exist: it was released or expired, or it belongs to another connection.

## ScriptTimeout
Code `408-1` (`SCRIPT_TIMEOUT`). Script exceeded its time budget.

## ScriptCancelled
Code `408-2` (`SCRIPT_CANCELLED`). Script was cancelled.

## FaultNotReached
Code `408-3` (`FAULT_NOT_REACHED`). The armed fault point was not reached in time.

## AuthenticationTimeout
Code `408-4` (`AUTHENTICATION_TIMEOUT`). The connection did not authenticate in time.

## TransactionTooOld
Code `408-5` (`TRANSACTION_TOO_OLD`). The transaction was rolled back after reaching its maximum age.

## AggregateTimeout
Code `408-6` (`AGGREGATE_TIMEOUT`). The aggregation exceeded its time budget.

## UserAlreadyExists
Code `409-1` (`USER_ALREADY_EXISTS`). User already exists.

## DatabaseAlreadyExists
Code `409-2` (`DATABASE_ALREADY_EXISTS`). Database already exists.

## TransactionAlreadyActive
Code `409-3` (`TRANSACTION_ALREADY_ACTIVE`). A transaction is already in progress for this connection.

## NoActiveTransaction
Code `409-4` (`NO_ACTIVE_TRANSACTION`). No active transaction for this connection.

## TransactionLockTimeout
Code `409-5` (`TRANSACTION_LOCK_TIMEOUT`). Could not acquire the collection lock in time; transaction aborted.

## OperationNotAllowedInTransaction
Code `409-6` (`OPERATION_NOT_ALLOWED_IN_TRANSACTION`). Operation not allowed while a transaction is open.

## TransactionAborted
Code `409-7` (`TRANSACTION_ABORTED`). Transaction aborted: a participant could not prepare.

## ProcedureVersionConflict
Code `409-8` (`PROCEDURE_VERSION_CONFLICT`). The procedure or trigger was modified by someone else.

## TransactionNotUsable
Code `409-9` (`TRANSACTION_NOT_USABLE`). The transaction was aborted and must be rolled back before continuing.

## TransactionIndeterminate
Code `409-10` (`TRANSACTION_INDETERMINATE`). The transaction's outcome is indeterminate: part of it did not apply and recovery will re-drive it.

## NameCollidesOnDisk
Code `409-11` (`NAME_COLLIDES_ON_DISK`). A name that differs only by case already exists and would share storage with it.

## TransactionPreviousUnresolved
Code `409-12` (`TRANSACTION_PREVIOUS_UNRESOLVED`). A previous transaction on this connection is still being resolved on a participant; retry once it finishes.

## TransactionSliceLost
Code `409-13` (`TRANSACTION_SLICE_LOST`). A participant no longer holds this transaction's buffered writes (it restarted or reaped the session); roll back and retry.

## TransactionAlreadyCommitted
Code `409-14` (`TRANSACTION_ALREADY_COMMITTED`). The transaction already committed on its owner; it can no longer be rolled back.

## PrimaryKeyRebuildRefused
Code `409-15` (`PRIMARY_KEY_REBUILD_REFUSED`). The primary-key index cannot be rebuilt from the page files.

## TransactionIdleTimeout
Code `409-16` (`TRANSACTION_IDLE_TIMEOUT`). The transaction was rolled back after being idle too long.

## SnapshotAborted
Code `409-17` (`SNAPSHOT_ABORTED`). The snapshot was aborted and holds nothing any more; open a new one.

## DocumentAlreadyExists
Code `409-18` (`DOCUMENT_ALREADY_EXISTS`). A document with this _id already exists.

## ListenEnded
Code `410-1` (`LISTEN_ENDED`). The listen ended.

## OutboundBacklog
Code `413-1` (`OUTBOUND_BACKLOG`). The connection's outbound buffer overflowed.

## NotCollectionOwner
Code `421-1` (`NOT_COLLECTION_OWNER`). This node is not the owner of the target collection.

## TransactionReadSpansNodes
Code `421-3` (`TRANSACTION_READ_SPANS_NODES`). This read joins collections the transaction wrote on different nodes.

## AuthenticationError
Code `500-1` (`AUTHENTICATION_ERROR`). Error during authentication.

## ErrorCreatingUser
Code `500-2` (`ERROR_CREATING_USER`). Error creating user.

## ErrorDeletingUser
Code `500-3` (`ERROR_DELETING_USER`). Error deleting user.

## ErrorChangingPassword
Code `500-4` (`ERROR_CHANGING_PASSWORD`). Error changing password.

## ErrorChangingPermissions
Code `500-5` (`ERROR_CHANGING_PERMISSIONS`). Error changing permissions.

## ErrorBulkSaving
Code `500-6` (`ERROR_BULK_SAVING`). Error while saving entries.

## ErrorSaving
Code `500-7` (`ERROR_SAVING`). Error while saving entry.

## ErrorRetrieving
Code `500-8` (`ERROR_RETRIEVING`). Error while retrieving entry.

## ErrorAggregating
Code `500-9` (`ERROR_AGGREGATING`). Error while processing aggregation.

## ErrorDeleting
Code `500-10` (`ERROR_DELETING`). Error while deleting entry.

## ErrorCreatingDatabase
Code `500-11` (`ERROR_CREATING_DATABASE`). Error while creating database.

## ErrorUpdatingDatabaseOwners
Code `500-12` (`ERROR_UPDATING_DATABASE_OWNERS`). Error updating database owners.

## ErrorDroppingDatabase
Code `500-13` (`ERROR_DROPPING_DATABASE`). Error while dropping database.

## ErrorListingDatabases
Code `500-14` (`ERROR_LISTING_DATABASES`). Error while listing databases.

## ErrorCreatingCollection
Code `500-15` (`ERROR_CREATING_COLLECTION`). Error while creating collection.

## ErrorDroppingCollection
Code `500-16` (`ERROR_DROPPING_COLLECTION`). Error while dropping collection.

## ErrorListingCollections
Code `500-17` (`ERROR_LISTING_COLLECTIONS`). Error while listing collections.

## ErrorCreatingIndex
Code `500-18` (`ERROR_CREATING_INDEX`). Error while creating index.

## ErrorListingUsers
Code `500-19` (`ERROR_LISTING_USERS`). Error listing users.

## ErrorDroppingIndex
Code `500-20` (`ERROR_DROPPING_INDEX`). Error while dropping index.

## ErrorReindexing
Code `500-21` (`ERROR_REINDEXING`). Error while reindexing.

## ErrorGatheringStats
Code `500-22` (`ERROR_GATHERING_STATS`). Error while gathering database stats.

## ErrorListen
Code `500-23` (`ERROR_LISTEN`). Error while processing listen operation.

## ErrorTransaction
Code `500-24` (`ERROR_TRANSACTION`). Error while processing transaction operation.

## ErrorSavingSchema
Code `500-25` (`ERROR_SAVING_SCHEMA`). Error while saving collection schema.

## ErrorDeletingSchema
Code `500-26` (`ERROR_DELETING_SCHEMA`). Error while deleting collection schema.

## ErrorSavingProcedure
Code `500-27` (`ERROR_SAVING_PROCEDURE`). Error while saving the procedure.

## ErrorDeletingProcedure
Code `500-28` (`ERROR_DELETING_PROCEDURE`). Error while deleting the procedure.

## ErrorSavingTrigger
Code `500-29` (`ERROR_SAVING_TRIGGER`). Error while saving the trigger.

## ErrorDeletingTrigger
Code `500-30` (`ERROR_DELETING_TRIGGER`). Error while deleting the trigger.

## ErrorSavingSchedule
Code `500-31` (`ERROR_SAVING_SCHEDULE`). Error while saving the schedule.

## ErrorDeletingSchedule
Code `500-32` (`ERROR_DELETING_SCHEDULE`). Error while deleting the schedule.

## TransactionHalfApplied
Code `500-33` (`TRANSACTION_HALF_APPLIED`). A committed transaction could not be fully applied; its collections are fenced until it is finished.

## WriteLoggedNotApplied
Code `500-34` (`WRITE_LOGGED_NOT_APPLIED`). The write was logged durably but could not be applied; it is applied before the collection is next used, or at the next startup, so it may already have taken effect.

## ErrorGatheringServerInfo
Code `500-35` (`ERROR_GATHERING_SERVER_INFO`). Error while gathering server info.

## ErrorStartingSnapshot
Code `500-36` (`ERROR_STARTING_SNAPSHOT`). Error while opening the snapshot.

## ErrorReadingSnapshot
Code `500-37` (`ERROR_READING_SNAPSHOT`). Error while reading the snapshot.

## MaxConnectionsReached
Code `503-1` (`MAX_CONNECTIONS_REACHED`). Max number of connections reached.

## NoQuorum
Code `503-2` (`NO_QUORUM`). Cluster does not have a write quorum. Safe to retry.

## ReplicationTimeout
Code `503-3` (`REPLICATION_TIMEOUT`). Timed out waiting for the replication quorum.

## OwnerUnreachable
Code `503-4` (`OWNER_UNREACHABLE`). The collection's owner node is unreachable. Safe to retry.

## AdminSyncing
Code `503-5` (`ADMIN_SYNCING`). Admin coordinator is synchronizing, retry shortly. Safe to retry.

## ScriptConcurrencyLimit
Code `503-6` (`SCRIPT_CONCURRENCY_LIMIT`). Too many scripts running, retry shortly.

## ScriptOutcomeUnknown
Code `503-7` (`SCRIPT_OUTCOME_UNKNOWN`). The node the script was placed on did not report an outcome; it was not run again in case it already had.

## WriteOutcomeUnknown
Code `503-8` (`WRITE_OUTCOME_UNKNOWN`). The collection's owner did not report an outcome; the write may already have been applied, so retrying is only safe for an idempotent write.

## AdminCoordinatorUnavailable
Code `503-9` (`ADMIN_COORDINATOR_UNAVAILABLE`). The admin coordinator could not be resolved, retry shortly. Safe to retry.

## SchemaUnavailable
Code `503-11` (`SCHEMA_UNAVAILABLE`). The collection's schema could not be read, so the write cannot be validated; retry shortly.

## CollectionNotReady
Code `503-10` (`COLLECTION_NOT_READY`). The collection has not reached this node yet, retry shortly. Safe to retry.

## ServerShuttingDown
Code `503-13` (`SERVER_SHUTTING_DOWN`). The server is shutting down, retry against another node or later.

## TooManyUnauthenticatedConnections
Code `503-14` (`TOO_MANY_UNAUTHENTICATED_CONNECTIONS`). Too many unauthenticated connections from this address.

## ListenRetainedBytesExhausted
Code `503-15` (`LISTEN_RETAINED_BYTES_EXHAUSTED`). The server holds the maximum bytes of listen registrations, retry later.

## BackgroundBacklog
Code `503-16` (`BACKGROUND_BACKLOG`). The background index queue is full and nothing was written; retry shortly. Safe to retry.

## ResponseBudgetExhausted
Code `503-17` (`RESPONSE_BUDGET_EXHAUSTED`). The server holds the maximum bytes of unsent responses; retry shortly. Safe to retry.

## CollectionFenced
Code `503-18` (`COLLECTION_FENCED`). The collection is held by a committed transaction that has not finished applying; retry shortly. Safe to retry.

## WritesNotApplied
Code `503-19` (`WRITES_NOT_APPLIED`). The collection holds logged writes that could not be applied yet, so it cannot be read consistently; retry shortly. Safe to retry.

## AggregateBudgetExhausted
Code `503-20` (`AGGREGATE_BUDGET_EXHAUSTED`). The server holds the maximum bytes of running aggregations; retry shortly. Safe to retry.

## TransactionBudgetExhausted
Code `503-21` (`TRANSACTION_BUDGET_EXHAUSTED`). The server holds the maximum bytes of buffered transactions; the write was not buffered, retry shortly. Safe to retry.

## SnapshotLimitReached
Code `503-22` (`SNAPSHOT_LIMIT_REACHED`). The server holds the maximum number of open snapshots; retry shortly. Safe to retry.

## Of(string)
Looks a code up in the catalogue.

@param rawCode the code such as `409-2`, or null
@returns the entry, or null when the code is not in the catalogue

## All
Every entry of the catalogue.

## Code
The code, such as `409-2`.

## IsRetryable
Whether the server marks a request refused with this code as safe to send again. The `RetryPolicy` retries only these.

## Message
The server's default message for the code, or null when it has none.

## Name
The code's name in the catalogue, such as `DATABASE_ALREADY_EXISTS`.

## Status
The status the server puts on a reply with this code.
