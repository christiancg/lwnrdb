The server's error codes, `NNN-N`: the range is HTTP-like (4xx client, 5xx server, 503 temporarily
unavailable) and the number is sequential.

## VALIDATION_ERROR

`400-1`.

## ENTRY_TOO_LARGE

`400-2`: Entry size exceeds maximum allowed size.

## DUPLICATE_ID

`400-3`: Duplicate _id in bulk save request.

## CANNOT_DELETE_LAST_ADMIN

`400-4`: Cannot delete the last admin user.

## CANNOT_DEMOTE_LAST_ADMIN

`400-5`: Cannot demote the last admin user.

## CURRENT_PASSWORD_INCORRECT

`400-6`: Current password is incorrect.

## SCHEMA_VALIDATION_FAILED

`400-7`: Document does not comply with the collection schema.

## INVALID_SCHEMA

`400-8`: The provided JSON schema is not valid.

## SCRIPT_FAILED

`400-9`: Script execution failed.

## SCRIPT_TOO_LARGE

`400-10`: Script exceeds the maximum allowed size.

## SCRIPT_LIMIT_EXCEEDED

`400-11`: Script exceeded a sandbox limit.

## SCRIPT_MEMORY_EXCEEDED

`400-12`: Script exceeded its memory budget.

## INVALID_PROCEDURE

`400-13`: The procedure source could not be parsed.

## INVALID_TRIGGER

`400-14`: The trigger definition is not valid.

## SCRIPT_RESULT_TOO_LARGE

`400-15`: Script result exceeds the maximum allowed size.

## INVALID_SCHEDULE

`400-16`: The schedule definition is not valid.

## TOO_MANY_SCHEDULES

`400-17`: The database already has the maximum number of schedules.

## PROCEDURE_IMPORT_NOT_FOUND

`400-18`: The procedure imports a procedure that does not exist.

## SCRIPT_NOT_ALLOWED_IN_LISTEN

`400-19`: A SCRIPT operator is not allowed in a LISTEN pipeline.

## SCRIPT_RESULT_PENDING

`400-20`: The script's result promise never settled.

## BEFORE_HOOK_REJECTED

`400-21`: A before trigger rejected this write.

## REQUEST_TOO_LARGE

`400-22`: The request exceeds the maximum allowed size.

## SCHEMA_VALIDATION_BUDGET_EXCEEDED

`400-23`: Schema validation exceeded its evaluation budget.

## SCHEMA_TOO_COMPLEX

`400-24`: The schema expands past the complexity limit.

## TOO_MANY_LISTENS

`400-25`: Too many listen registrations.

## TRANSACTION_TOO_LARGE

`400-26`: The transaction would exceed its size limit.

## FILTER_OPERAND_LIMIT

`400-27`: The filter has more operands than allowed.

## MAP_RESULT_TOO_LARGE

`400-28`: A MAP result exceeds the maximum entry size.

## SCHEDULE_TIMEOUT_TOO_LARGE

`400-29`: The schedule timeout exceeds the allowed maximum.

## SCRIPT_ARRAY_TOO_LARGE

`400-30`: A script array exceeds the maximum length.

## SCHEMA_PATTERN_TOO_LONG

`400-31`: A schema regular expression exceeds the maximum length.

## TOO_MANY_TRIGGERS

`400-32`: The collection has the maximum number of triggers.

## TOO_MANY_SCHEDULES_FOR_USER

`400-33`: The user has the maximum number of schedules.

## TOO_MANY_PROCEDURES

`400-34`: The database has the maximum number of procedures.

## SCHEDULE_INTERVAL_TOO_SMALL

`400-35`: The schedule interval is below the allowed minimum.

## AGGREGATE_RESULT_TOO_LARGE

`400-36`: The aggregation result exceeds the maximum result size.

## LISTEN_REQUEST_TOO_LARGE

`400-37`: The listen request exceeds the maximum size a listen may retain.

## TOO_MANY_LISTENS_FOR_USER

`400-38`: The user has the maximum number of listen registrations.

## SCHEMA_TOO_MANY_PATTERNS

`400-39`: The schema has more regular expressions than allowed.

## TOO_MANY_COLLECTIONS_FOR_DATABASE

`400-40`: The database has the maximum number of collections.

## TOO_MANY_DATABASES

`400-41`: The server has the maximum number of databases.

## TOO_MANY_INDEXES_FOR_COLLECTION

`400-42`: The collection has the maximum number of indexes.

## INVALID_COMMAND

`400-43`: The command is not valid.

## DELETE_MATCHES_TOO_MANY

`400-44`: The delete condition matches more documents than allowed.

## UPDATE_MATCHES_TOO_MANY

`400-45`: The update condition matches more documents than allowed.

## MUST_AUTHENTICATE_FIRST

`401-1`: Must authenticate first.

## USER_NO_LONGER_EXISTS

`401-2`: User no longer exists.

## WRONG_CREDENTIALS

`401-3`: The user doesn't exist or the wrong credentials have been provided.

## TOO_MANY_FAILED_ATTEMPTS

`401-4`: Too many failed authentication attempts on this connection.

## NO_PERMISSIONS

`403-1`: Action is forbidden, no permissions.

## SCRIPTS_DISABLED

`403-2`: Script execution is disabled on this server.

## FAULT_INJECTION_DISABLED

`403-3`: Fault injection is disabled on this server.

## PROCEDURE_BOUND_BY_OTHER_DEFINER

`403-4`: Procedure is bound by a trigger or schedule that runs as another user.

## DEFINITION_OWNED_BY_OTHER_DEFINER

`403-5`: The trigger or schedule belongs to another user; only that user, a database owner or an admin may change it.

## SOURCE_REQUIRES_MANAGE

`403-6`: Procedure source requires script MANAGE.

## USER_NOT_FOUND

`404-1`: User not found.

## ENTRY_NOT_FOUND

`404-2`: Entry not found.

## NO_RESULTS

`404-3`: No results.

## DATABASE_NOT_FOUND

`404-4`: Database not found.

## NO_USERS_FOUND

`404-5`: No users found.

## INDEX_NOT_FOUND

`404-6`: No index registered for the specified field.

## LISTEN_NOT_FOUND

`404-7`: Listen registration not found.

## PROCEDURE_NOT_FOUND

`404-8`: Procedure not found.

## TRIGGER_NOT_FOUND

`404-9`: Trigger not found.

## COLLECTION_NOT_FOUND

`404-11`: Collection not found.

## SCHEMA_NOT_FOUND

`404-12`: The collection has no schema.

## SNAPSHOT_NOT_FOUND

`404-13`: The snapshot does not exist: it was released or expired, or it belongs to another connection.

## SCRIPT_TIMEOUT

`408-1`: Script exceeded its time budget.

## SCRIPT_CANCELLED

`408-2`: Script was cancelled.

## FAULT_NOT_REACHED

`408-3`: The armed fault point was not reached in time.

## AUTHENTICATION_TIMEOUT

`408-4`: The connection did not authenticate in time.

## TRANSACTION_TOO_OLD

`408-5`: The transaction was rolled back after reaching its maximum age.

## AGGREGATE_TIMEOUT

`408-6`: The aggregation exceeded its time budget.

## USER_ALREADY_EXISTS

`409-1`: User already exists.

## DATABASE_ALREADY_EXISTS

`409-2`: Database already exists.

## TRANSACTION_ALREADY_ACTIVE

`409-3`: A transaction is already in progress for this connection.

## NO_ACTIVE_TRANSACTION

`409-4`: No active transaction for this connection.

## TRANSACTION_LOCK_TIMEOUT

`409-5`: Could not acquire the collection lock in time; transaction aborted.

## OPERATION_NOT_ALLOWED_IN_TRANSACTION

`409-6`: Operation not allowed while a transaction is open.

## TRANSACTION_ABORTED

`409-7`: Transaction aborted: a participant could not prepare.

## PROCEDURE_VERSION_CONFLICT

`409-8`: The procedure or trigger was modified by someone else.

## TRANSACTION_NOT_USABLE

`409-9`: The transaction was aborted and must be rolled back before continuing.

## TRANSACTION_INDETERMINATE

`409-10`: The transaction's outcome is indeterminate: part of it did not apply and recovery will re-drive it.

## NAME_COLLIDES_ON_DISK

`409-11`: A name that differs only by case already exists and would share storage with it.

## TRANSACTION_PREVIOUS_UNRESOLVED

`409-12`: A previous transaction on this connection is still being resolved on a participant; retry once it finishes.

## TRANSACTION_SLICE_LOST

`409-13`: A participant no longer holds this transaction's buffered writes (it restarted or reaped the session); roll back and retry.

## TRANSACTION_ALREADY_COMMITTED

`409-14`: The transaction already committed on its owner; it can no longer be rolled back.

## PRIMARY_KEY_REBUILD_REFUSED

`409-15`: The primary-key index cannot be rebuilt from the page files.

## TRANSACTION_IDLE_TIMEOUT

`409-16`: The transaction was rolled back after being idle too long.

## SNAPSHOT_ABORTED

`409-17`: The snapshot was aborted and holds nothing any more; open a new one.

## DOCUMENT_ALREADY_EXISTS

`409-18`: A document with this _id already exists.

## LISTEN_ENDED

`410-1`: The listen ended.

## OUTBOUND_BACKLOG

`413-1`: The connection's outbound buffer overflowed.

## NOT_COLLECTION_OWNER

`421-1`: This node is not the owner of the target collection.

## TRANSACTION_READ_SPANS_NODES

`421-3`: This read joins collections the transaction wrote on different nodes.

## AUTHENTICATION_ERROR

`500-1`: Error during authentication.

## ERROR_CREATING_USER

`500-2`: Error creating user.

## ERROR_DELETING_USER

`500-3`: Error deleting user.

## ERROR_CHANGING_PASSWORD

`500-4`: Error changing password.

## ERROR_CHANGING_PERMISSIONS

`500-5`: Error changing permissions.

## ERROR_BULK_SAVING

`500-6`: Error while saving entries.

## ERROR_SAVING

`500-7`: Error while saving entry.

## ERROR_RETRIEVING

`500-8`: Error while retrieving entry.

## ERROR_AGGREGATING

`500-9`: Error while processing aggregation.

## ERROR_DELETING

`500-10`: Error while deleting entry.

## ERROR_CREATING_DATABASE

`500-11`: Error while creating database.

## ERROR_UPDATING_DATABASE_OWNERS

`500-12`: Error updating database owners.

## ERROR_DROPPING_DATABASE

`500-13`: Error while dropping database.

## ERROR_LISTING_DATABASES

`500-14`: Error while listing databases.

## ERROR_CREATING_COLLECTION

`500-15`: Error while creating collection.

## ERROR_DROPPING_COLLECTION

`500-16`: Error while dropping collection.

## ERROR_LISTING_COLLECTIONS

`500-17`: Error while listing collections.

## ERROR_CREATING_INDEX

`500-18`: Error while creating index.

## ERROR_LISTING_USERS

`500-19`: Error listing users.

## ERROR_DROPPING_INDEX

`500-20`: Error while dropping index.

## ERROR_REINDEXING

`500-21`: Error while reindexing.

## ERROR_GATHERING_STATS

`500-22`: Error while gathering database stats.

## ERROR_LISTEN

`500-23`: Error while processing listen operation.

## ERROR_TRANSACTION

`500-24`: Error while processing transaction operation.

## ERROR_SAVING_SCHEMA

`500-25`: Error while saving collection schema.

## ERROR_DELETING_SCHEMA

`500-26`: Error while deleting collection schema.

## ERROR_SAVING_PROCEDURE

`500-27`: Error while saving the procedure.

## ERROR_DELETING_PROCEDURE

`500-28`: Error while deleting the procedure.

## ERROR_SAVING_TRIGGER

`500-29`: Error while saving the trigger.

## ERROR_DELETING_TRIGGER

`500-30`: Error while deleting the trigger.

## ERROR_SAVING_SCHEDULE

`500-31`: Error while saving the schedule.

## ERROR_DELETING_SCHEDULE

`500-32`: Error while deleting the schedule.

## TRANSACTION_HALF_APPLIED

`500-33`: A committed transaction could not be fully applied; its collections are fenced until it is finished.

## WRITE_LOGGED_NOT_APPLIED

`500-34`: The write was logged durably but could not be applied; it is applied before the collection is next used, or at the next startup, so it may already have taken effect.

## ERROR_GATHERING_SERVER_INFO

`500-35`: Error while gathering server info.

## ERROR_STARTING_SNAPSHOT

`500-36`: Error while opening the snapshot.

## ERROR_READING_SNAPSHOT

`500-37`: Error while reading the snapshot.

## MAX_CONNECTIONS_REACHED

`503-1`: Max number of connections reached.

## NO_QUORUM

`503-2`: Cluster does not have a write quorum. Retryable.

## REPLICATION_TIMEOUT

`503-3`: Timed out waiting for the replication quorum.

## OWNER_UNREACHABLE

`503-4`: The collection's owner node is unreachable. Retryable.

## ADMIN_SYNCING

`503-5`: Admin coordinator is synchronizing, retry shortly. Retryable.

## SCRIPT_CONCURRENCY_LIMIT

`503-6`: Too many scripts running, retry shortly.

## SCRIPT_OUTCOME_UNKNOWN

`503-7`: The node the script was placed on did not report an outcome; it was not run again in case it already had.

## WRITE_OUTCOME_UNKNOWN

`503-8`: The collection's owner did not report an outcome; the write may already have been applied, so retrying is only safe for an idempotent write.

## ADMIN_COORDINATOR_UNAVAILABLE

`503-9`: The admin coordinator could not be resolved, retry shortly. Retryable.

## SCHEMA_UNAVAILABLE

`503-11`: The collection's schema could not be read, so the write cannot be validated; retry shortly.

## COLLECTION_NOT_READY

`503-10`: The collection has not reached this node yet, retry shortly. Retryable.

## SERVER_SHUTTING_DOWN

`503-13`: The server is shutting down, retry against another node or later.

## TOO_MANY_UNAUTHENTICATED_CONNECTIONS

`503-14`: Too many unauthenticated connections from this address.

## LISTEN_RETAINED_BYTES_EXHAUSTED

`503-15`: The server holds the maximum bytes of listen registrations, retry later.

## BACKGROUND_BACKLOG

`503-16`: The background index queue is full and nothing was written; retry shortly. Retryable.

## RESPONSE_BUDGET_EXHAUSTED

`503-17`: The server holds the maximum bytes of unsent responses; retry shortly. Retryable.

## COLLECTION_FENCED

`503-18`: The collection is held by a committed transaction that has not finished applying; retry shortly. Retryable.

## WRITES_NOT_APPLIED

`503-19`: The collection holds logged writes that could not be applied yet, so it cannot be read consistently; retry shortly. Retryable.

## AGGREGATE_BUDGET_EXHAUSTED

`503-20`: The server holds the maximum bytes of running aggregations; retry shortly. Retryable.

## TRANSACTION_BUDGET_EXHAUSTED

`503-21`: The server holds the maximum bytes of buffered transactions; the write was not buffered, retry shortly. Retryable.

## SNAPSHOT_LIMIT_REACHED

`503-22`: The server holds the maximum number of open snapshots; retry shortly. Retryable.

## code()

@return the code, `NNN-N`

## status()

@return the `status` sent with it

## isRetryable()

@return `true` when resending the request is always safe

## defaultMessage()

@return the server's default message

## fromCode(String)

@param code a code, `NNN-N`
@return its constant, empty when unknown
