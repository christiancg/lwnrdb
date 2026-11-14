from enum import StrEnum


class Operation(StrEnum):
    BULK_SAVE = 'BULK_SAVE'
    SAVE = 'SAVE'
    FIND_BY_ID = 'FIND_BY_ID'
    AGGREGATE = 'AGGREGATE'
    DELETE = 'DELETE'
    CREATE_DATABASE = 'CREATE_DATABASE'
    DROP_DATABASE = 'DROP_DATABASE'
    LIST_DATABASES = 'LIST_DATABASES'
    CREATE_COLLECTION = 'CREATE_COLLECTION'
    DROP_COLLECTION = 'DROP_COLLECTION'
    LIST_COLLECTIONS = 'LIST_COLLECTIONS'
    CREATE_INDEX = 'CREATE_INDEX'
    DROP_INDEX = 'DROP_INDEX'
    REINDEX = 'REINDEX'
    SAVE_SCHEMA = 'SAVE_SCHEMA'
    DELETE_SCHEMA = 'DELETE_SCHEMA'
    GET_SCHEMA = 'GET_SCHEMA'
    CLOSE_CONNECTION = 'CLOSE_CONNECTION'
    SERVER_INFO = 'SERVER_INFO'
    AUTHENTICATE = 'AUTHENTICATE'
    CREATE_USER = 'CREATE_USER'
    DELETE_USER = 'DELETE_USER'
    CHANGE_PERMISSIONS = 'CHANGE_PERMISSIONS'
    SET_DATABASE_OWNERS = 'SET_DATABASE_OWNERS'
    LIST_USERS = 'LIST_USERS'
    SET_PASSWORD = 'SET_PASSWORD'
    GET_DATABASE_STATS = 'GET_DATABASE_STATS'
    LISTEN = 'LISTEN'
    STOP_LISTEN = 'STOP_LISTEN'
    START_TRANSACTION = 'START_TRANSACTION'
    COMMIT_TRANSACTION = 'COMMIT_TRANSACTION'
    ROLLBACK_TRANSACTION = 'ROLLBACK_TRANSACTION'
    RESOLVE_TRANSACTION = 'RESOLVE_TRANSACTION'
    LIST_TRANSACTIONS = 'LIST_TRANSACTIONS'
    RUN_SCRIPT = 'RUN_SCRIPT'
    SAVE_PROCEDURE = 'SAVE_PROCEDURE'
    DELETE_PROCEDURE = 'DELETE_PROCEDURE'
    LIST_PROCEDURES = 'LIST_PROCEDURES'
    CALL_PROCEDURE = 'CALL_PROCEDURE'
    SAVE_TRIGGER = 'SAVE_TRIGGER'
    DELETE_TRIGGER = 'DELETE_TRIGGER'
    LIST_TRIGGERS = 'LIST_TRIGGERS'
    SAVE_SCHEDULE = 'SAVE_SCHEDULE'
    DELETE_SCHEDULE = 'DELETE_SCHEDULE'
    LIST_SCHEDULES = 'LIST_SCHEDULES'
    LIST_SCRIPTS = 'LIST_SCRIPTS'
    CANCEL_SCRIPT = 'CANCEL_SCRIPT'
    TEST_TRIGGER = 'TEST_TRIGGER'
    LIST_TRIGGER_RUNS = 'LIST_TRIGGER_RUNS'
    RESOLVE_TRIGGER_RUN = 'RESOLVE_TRIGGER_RUN'


class ErrorCode(StrEnum):
    VALIDATION_ERROR = '400-1'
    ENTRY_TOO_LARGE = '400-2'
    DUPLICATE_ID = '400-3'
    CANNOT_DELETE_LAST_ADMIN = '400-4'
    CANNOT_DEMOTE_LAST_ADMIN = '400-5'
    CURRENT_PASSWORD_INCORRECT = '400-6'
    SCHEMA_VALIDATION_FAILED = '400-7'
    INVALID_SCHEMA = '400-8'
    SCRIPT_FAILED = '400-9'
    SCRIPT_TOO_LARGE = '400-10'
    SCRIPT_LIMIT_EXCEEDED = '400-11'
    SCRIPT_MEMORY_EXCEEDED = '400-12'
    INVALID_PROCEDURE = '400-13'
    INVALID_TRIGGER = '400-14'
    SCRIPT_RESULT_TOO_LARGE = '400-15'
    INVALID_SCHEDULE = '400-16'
    TOO_MANY_SCHEDULES = '400-17'
    PROCEDURE_IMPORT_NOT_FOUND = '400-18'
    SCRIPT_NOT_ALLOWED_IN_LISTEN = '400-19'
    SCRIPT_RESULT_PENDING = '400-20'
    BEFORE_HOOK_REJECTED = '400-21'
    REQUEST_TOO_LARGE = '400-22'
    SCHEMA_VALIDATION_BUDGET_EXCEEDED = '400-23'
    SCHEMA_TOO_COMPLEX = '400-24'
    TOO_MANY_LISTENS = '400-25'
    TRANSACTION_TOO_LARGE = '400-26'
    FILTER_OPERAND_LIMIT = '400-27'
    MAP_RESULT_TOO_LARGE = '400-28'
    SCHEDULE_TIMEOUT_TOO_LARGE = '400-29'
    SCRIPT_ARRAY_TOO_LARGE = '400-30'
    SCHEMA_PATTERN_TOO_LONG = '400-31'
    TOO_MANY_TRIGGERS = '400-32'
    TOO_MANY_SCHEDULES_FOR_USER = '400-33'
    TOO_MANY_PROCEDURES = '400-34'
    SCHEDULE_INTERVAL_TOO_SMALL = '400-35'
    AGGREGATE_RESULT_TOO_LARGE = '400-36'
    LISTEN_REQUEST_TOO_LARGE = '400-37'
    TOO_MANY_LISTENS_FOR_USER = '400-38'
    SCHEMA_TOO_MANY_PATTERNS = '400-39'
    TOO_MANY_COLLECTIONS_FOR_DATABASE = '400-40'
    TOO_MANY_DATABASES = '400-41'
    TOO_MANY_INDEXES_FOR_COLLECTION = '400-42'
    INVALID_COMMAND = '400-43'
    DELETE_MATCHES_TOO_MANY = '400-44'
    UPDATE_MATCHES_TOO_MANY = '400-45'
    MUST_AUTHENTICATE_FIRST = '401-1'
    USER_NO_LONGER_EXISTS = '401-2'
    WRONG_CREDENTIALS = '401-3'
    TOO_MANY_FAILED_ATTEMPTS = '401-4'
    NO_PERMISSIONS = '403-1'
    SCRIPTS_DISABLED = '403-2'
    FAULT_INJECTION_DISABLED = '403-3'
    PROCEDURE_BOUND_BY_OTHER_DEFINER = '403-4'
    DEFINITION_OWNED_BY_OTHER_DEFINER = '403-5'
    SOURCE_REQUIRES_MANAGE = '403-6'
    USER_NOT_FOUND = '404-1'
    ENTRY_NOT_FOUND = '404-2'
    NO_RESULTS = '404-3'
    DATABASE_NOT_FOUND = '404-4'
    NO_USERS_FOUND = '404-5'
    INDEX_NOT_FOUND = '404-6'
    LISTEN_NOT_FOUND = '404-7'
    PROCEDURE_NOT_FOUND = '404-8'
    TRIGGER_NOT_FOUND = '404-9'
    COLLECTION_NOT_FOUND = '404-11'
    SCHEMA_NOT_FOUND = '404-12'
    SNAPSHOT_NOT_FOUND = '404-13'
    SCRIPT_TIMEOUT = '408-1'
    SCRIPT_CANCELLED = '408-2'
    FAULT_NOT_REACHED = '408-3'
    AUTHENTICATION_TIMEOUT = '408-4'
    TRANSACTION_TOO_OLD = '408-5'
    AGGREGATE_TIMEOUT = '408-6'
    USER_ALREADY_EXISTS = '409-1'
    DATABASE_ALREADY_EXISTS = '409-2'
    TRANSACTION_ALREADY_ACTIVE = '409-3'
    NO_ACTIVE_TRANSACTION = '409-4'
    TRANSACTION_LOCK_TIMEOUT = '409-5'
    OPERATION_NOT_ALLOWED_IN_TRANSACTION = '409-6'
    TRANSACTION_ABORTED = '409-7'
    PROCEDURE_VERSION_CONFLICT = '409-8'
    TRANSACTION_NOT_USABLE = '409-9'
    TRANSACTION_INDETERMINATE = '409-10'
    NAME_COLLIDES_ON_DISK = '409-11'
    TRANSACTION_PREVIOUS_UNRESOLVED = '409-12'
    TRANSACTION_SLICE_LOST = '409-13'
    TRANSACTION_ALREADY_COMMITTED = '409-14'
    PRIMARY_KEY_REBUILD_REFUSED = '409-15'
    TRANSACTION_IDLE_TIMEOUT = '409-16'
    SNAPSHOT_ABORTED = '409-17'
    LISTEN_ENDED = '410-1'
    OUTBOUND_BACKLOG = '413-1'
    NOT_COLLECTION_OWNER = '421-1'
    TRANSACTION_READ_SPANS_NODES = '421-3'
    AUTHENTICATION_ERROR = '500-1'
    ERROR_CREATING_USER = '500-2'
    ERROR_DELETING_USER = '500-3'
    ERROR_CHANGING_PASSWORD = '500-4'
    ERROR_CHANGING_PERMISSIONS = '500-5'
    ERROR_BULK_SAVING = '500-6'
    ERROR_SAVING = '500-7'
    ERROR_RETRIEVING = '500-8'
    ERROR_AGGREGATING = '500-9'
    ERROR_DELETING = '500-10'
    ERROR_CREATING_DATABASE = '500-11'
    ERROR_UPDATING_DATABASE_OWNERS = '500-12'
    ERROR_DROPPING_DATABASE = '500-13'
    ERROR_LISTING_DATABASES = '500-14'
    ERROR_CREATING_COLLECTION = '500-15'
    ERROR_DROPPING_COLLECTION = '500-16'
    ERROR_LISTING_COLLECTIONS = '500-17'
    ERROR_CREATING_INDEX = '500-18'
    ERROR_LISTING_USERS = '500-19'
    ERROR_DROPPING_INDEX = '500-20'
    ERROR_REINDEXING = '500-21'
    ERROR_GATHERING_STATS = '500-22'
    ERROR_LISTEN = '500-23'
    ERROR_TRANSACTION = '500-24'
    ERROR_SAVING_SCHEMA = '500-25'
    ERROR_DELETING_SCHEMA = '500-26'
    ERROR_SAVING_PROCEDURE = '500-27'
    ERROR_DELETING_PROCEDURE = '500-28'
    ERROR_SAVING_TRIGGER = '500-29'
    ERROR_DELETING_TRIGGER = '500-30'
    ERROR_SAVING_SCHEDULE = '500-31'
    ERROR_DELETING_SCHEDULE = '500-32'
    TRANSACTION_HALF_APPLIED = '500-33'
    WRITE_LOGGED_NOT_APPLIED = '500-34'
    ERROR_GATHERING_SERVER_INFO = '500-35'
    ERROR_STARTING_SNAPSHOT = '500-36'
    ERROR_READING_SNAPSHOT = '500-37'
    MAX_CONNECTIONS_REACHED = '503-1'
    NO_QUORUM = '503-2'
    REPLICATION_TIMEOUT = '503-3'
    OWNER_UNREACHABLE = '503-4'
    ADMIN_SYNCING = '503-5'
    SCRIPT_CONCURRENCY_LIMIT = '503-6'
    SCRIPT_OUTCOME_UNKNOWN = '503-7'
    WRITE_OUTCOME_UNKNOWN = '503-8'
    ADMIN_COORDINATOR_UNAVAILABLE = '503-9'
    SCHEMA_UNAVAILABLE = '503-11'
    COLLECTION_NOT_READY = '503-10'
    SERVER_SHUTTING_DOWN = '503-13'
    TOO_MANY_UNAUTHENTICATED_CONNECTIONS = '503-14'
    LISTEN_RETAINED_BYTES_EXHAUSTED = '503-15'
    BACKGROUND_BACKLOG = '503-16'
    RESPONSE_BUDGET_EXHAUSTED = '503-17'
    COLLECTION_FENCED = '503-18'
    WRITES_NOT_APPLIED = '503-19'
    AGGREGATE_BUDGET_EXHAUSTED = '503-20'
    TRANSACTION_BUDGET_EXHAUSTED = '503-21'
    SNAPSHOT_LIMIT_REACHED = '503-22'


DETECTION_REGEX = '^#[a-zA-Z0-9]{3,20}\\(.*\\)$'

GEO_DISTANCE_COMPARATORS = ('EQUALS', 'GREATER_THAN', 'GREATER_THAN_EQUALS', 'SMALLER_THAN', 'SMALLER_THAN_EQUALS')

CUSTOM_TYPES = (
    ('datetime', (), ()),
    ('geo', ('distance', 'within'), ()),
    ('time', (), ()),
    ('vector', (), ('nearest',)),
)

OPERATIONS = (
    ('BULK_SAVE', 'COLLECTION', False, True),
    ('SAVE', 'COLLECTION', False, True),
    ('FIND_BY_ID', 'COLLECTION', False, True),
    ('AGGREGATE', 'COLLECTION', False, True),
    ('DELETE', 'COLLECTION', False, True),
    ('CREATE_DATABASE', 'DATABASE', False, False),
    ('DROP_DATABASE', 'DATABASE', False, False),
    ('LIST_DATABASES', 'NONE', False, False),
    ('CREATE_COLLECTION', 'COLLECTION', False, False),
    ('DROP_COLLECTION', 'COLLECTION', False, False),
    ('LIST_COLLECTIONS', 'DATABASE', False, False),
    ('CREATE_INDEX', 'COLLECTION', False, False),
    ('DROP_INDEX', 'COLLECTION', False, False),
    ('REINDEX', 'COLLECTION', False, False),
    ('SAVE_SCHEMA', 'COLLECTION', False, False),
    ('DELETE_SCHEMA', 'COLLECTION', False, False),
    ('GET_SCHEMA', 'COLLECTION', False, False),
    ('CLOSE_CONNECTION', 'NONE', False, True),
    ('SERVER_INFO', 'NONE', False, True),
    ('AUTHENTICATE', 'NONE', False, False),
    ('CREATE_USER', 'NONE', True, False),
    ('DELETE_USER', 'NONE', True, False),
    ('CHANGE_PERMISSIONS', 'NONE', True, False),
    ('SET_DATABASE_OWNERS', 'DATABASE', True, False),
    ('LIST_USERS', 'NONE', True, False),
    ('SET_PASSWORD', 'NONE', False, False),
    ('GET_DATABASE_STATS', 'DATABASE', False, False),
    ('LISTEN', 'COLLECTION', False, False),
    ('STOP_LISTEN', 'NONE', False, False),
    ('START_TRANSACTION', 'NONE', False, True),
    ('COMMIT_TRANSACTION', 'NONE', False, True),
    ('ROLLBACK_TRANSACTION', 'NONE', False, True),
    ('RESOLVE_TRANSACTION', 'NONE', True, False),
    ('LIST_TRANSACTIONS', 'NONE', True, False),
    ('RUN_SCRIPT', 'DATABASE', False, False),
    ('SAVE_PROCEDURE', 'DATABASE', False, False),
    ('DELETE_PROCEDURE', 'DATABASE', False, False),
    ('LIST_PROCEDURES', 'DATABASE', False, False),
    ('CALL_PROCEDURE', 'DATABASE', False, False),
    ('SAVE_TRIGGER', 'COLLECTION', False, False),
    ('DELETE_TRIGGER', 'COLLECTION', False, False),
    ('LIST_TRIGGERS', 'OPTIONAL_COLLECTION', False, False),
    ('SAVE_SCHEDULE', 'DATABASE', False, False),
    ('DELETE_SCHEDULE', 'DATABASE', False, False),
    ('LIST_SCHEDULES', 'DATABASE', False, False),
    ('LIST_SCRIPTS', 'NONE', True, False),
    ('CANCEL_SCRIPT', 'NONE', True, False),
    ('TEST_TRIGGER', 'COLLECTION', False, False),
    ('LIST_TRIGGER_RUNS', 'NONE', True, False),
    ('RESOLVE_TRIGGER_RUN', 'NONE', True, False),
)

ERROR_CODES = (
    ('VALIDATION_ERROR', '400-1', 'ERROR', False, None),
    ('ENTRY_TOO_LARGE', '400-2', 'ERROR', False, 'Entry size exceeds maximum allowed size'),
    ('DUPLICATE_ID', '400-3', 'ERROR', False, 'Duplicate _id in bulk save request'),
    ('CANNOT_DELETE_LAST_ADMIN', '400-4', 'ERROR', False, 'Cannot delete the last admin user'),
    ('CANNOT_DEMOTE_LAST_ADMIN', '400-5', 'ERROR', False, 'Cannot demote the last admin user'),
    ('CURRENT_PASSWORD_INCORRECT', '400-6', 'ERROR', False, 'Current password is incorrect'),
    ('SCHEMA_VALIDATION_FAILED', '400-7', 'ERROR', False, 'Document does not comply with the collection schema'),
    ('INVALID_SCHEMA', '400-8', 'ERROR', False, 'The provided JSON schema is not valid'),
    ('SCRIPT_FAILED', '400-9', 'ERROR', False, 'Script execution failed'),
    ('SCRIPT_TOO_LARGE', '400-10', 'ERROR', False, 'Script exceeds the maximum allowed size'),
    ('SCRIPT_LIMIT_EXCEEDED', '400-11', 'ERROR', False, 'Script exceeded a sandbox limit'),
    ('SCRIPT_MEMORY_EXCEEDED', '400-12', 'ERROR', False, 'Script exceeded its memory budget'),
    ('INVALID_PROCEDURE', '400-13', 'ERROR', False, 'The procedure source could not be parsed'),
    ('INVALID_TRIGGER', '400-14', 'ERROR', False, 'The trigger definition is not valid'),
    ('SCRIPT_RESULT_TOO_LARGE', '400-15', 'ERROR', False, 'Script result exceeds the maximum allowed size'),
    ('INVALID_SCHEDULE', '400-16', 'ERROR', False, 'The schedule definition is not valid'),
    ('TOO_MANY_SCHEDULES', '400-17', 'ERROR', False, 'The database already has the maximum number of schedules'),
    ('PROCEDURE_IMPORT_NOT_FOUND', '400-18', 'ERROR', False, 'The procedure imports a procedure that does not exist'),
    ('SCRIPT_NOT_ALLOWED_IN_LISTEN', '400-19', 'ERROR', False, 'A SCRIPT operator is not allowed in a LISTEN pipeline'),
    ('SCRIPT_RESULT_PENDING', '400-20', 'ERROR', False, "The script's result promise never settled"),
    ('BEFORE_HOOK_REJECTED', '400-21', 'ERROR', False, 'A before trigger rejected this write'),
    ('REQUEST_TOO_LARGE', '400-22', 'ERROR', False, 'The request exceeds the maximum allowed size'),
    ('SCHEMA_VALIDATION_BUDGET_EXCEEDED', '400-23', 'ERROR', False, 'Schema validation exceeded its evaluation budget'),
    ('SCHEMA_TOO_COMPLEX', '400-24', 'ERROR', False, 'The schema expands past the complexity limit'),
    ('TOO_MANY_LISTENS', '400-25', 'ERROR', False, 'Too many listen registrations'),
    ('TRANSACTION_TOO_LARGE', '400-26', 'ERROR', False, 'The transaction would exceed its size limit'),
    ('FILTER_OPERAND_LIMIT', '400-27', 'ERROR', False, 'The filter has more operands than allowed'),
    ('MAP_RESULT_TOO_LARGE', '400-28', 'ERROR', False, 'A MAP result exceeds the maximum entry size'),
    ('SCHEDULE_TIMEOUT_TOO_LARGE', '400-29', 'ERROR', False, 'The schedule timeout exceeds the allowed maximum'),
    ('SCRIPT_ARRAY_TOO_LARGE', '400-30', 'ERROR', False, 'A script array exceeds the maximum length'),
    ('SCHEMA_PATTERN_TOO_LONG', '400-31', 'ERROR', False, 'A schema regular expression exceeds the maximum length'),
    ('TOO_MANY_TRIGGERS', '400-32', 'ERROR', False, 'The collection has the maximum number of triggers'),
    ('TOO_MANY_SCHEDULES_FOR_USER', '400-33', 'ERROR', False, 'The user has the maximum number of schedules'),
    ('TOO_MANY_PROCEDURES', '400-34', 'ERROR', False, 'The database has the maximum number of procedures'),
    ('SCHEDULE_INTERVAL_TOO_SMALL', '400-35', 'ERROR', False, 'The schedule interval is below the allowed minimum'),
    ('AGGREGATE_RESULT_TOO_LARGE', '400-36', 'ERROR', False, 'The aggregation result exceeds the maximum result size'),
    ('LISTEN_REQUEST_TOO_LARGE', '400-37', 'ERROR', False, 'The listen request exceeds the maximum size a listen may retain'),
    ('TOO_MANY_LISTENS_FOR_USER', '400-38', 'ERROR', False, 'The user has the maximum number of listen registrations'),
    ('SCHEMA_TOO_MANY_PATTERNS', '400-39', 'ERROR', False, 'The schema has more regular expressions than allowed'),
    ('TOO_MANY_COLLECTIONS_FOR_DATABASE', '400-40', 'ERROR', False, 'The database has the maximum number of collections'),
    ('TOO_MANY_DATABASES', '400-41', 'ERROR', False, 'The server has the maximum number of databases'),
    ('TOO_MANY_INDEXES_FOR_COLLECTION', '400-42', 'ERROR', False, 'The collection has the maximum number of indexes'),
    ('INVALID_COMMAND', '400-43', 'ERROR', False, 'The command is not valid'),
    ('DELETE_MATCHES_TOO_MANY', '400-44', 'ERROR', False, 'The delete condition matches more documents than allowed'),
    ('UPDATE_MATCHES_TOO_MANY', '400-45', 'ERROR', False, 'The update condition matches more documents than allowed'),
    ('MUST_AUTHENTICATE_FIRST', '401-1', 'UNAUTHENTICATED', False, 'Must authenticate first'),
    ('USER_NO_LONGER_EXISTS', '401-2', 'UNAUTHENTICATED', False, 'User no longer exists'),
    ('WRONG_CREDENTIALS', '401-3', 'ERROR', False, "The user doesn't exist or the wrong credentials have been provided"),
    ('TOO_MANY_FAILED_ATTEMPTS', '401-4', 'UNAUTHENTICATED', False, 'Too many failed authentication attempts on this connection'),
    ('NO_PERMISSIONS', '403-1', 'FORBIDDEN', False, 'Action is forbidden, no permissions'),
    ('SCRIPTS_DISABLED', '403-2', 'FORBIDDEN', False, 'Script execution is disabled on this server'),
    ('FAULT_INJECTION_DISABLED', '403-3', 'FORBIDDEN', False, 'Fault injection is disabled on this server'),
    ('PROCEDURE_BOUND_BY_OTHER_DEFINER', '403-4', 'FORBIDDEN', False, 'Procedure is bound by a trigger or schedule that runs as another user'),
    ('DEFINITION_OWNED_BY_OTHER_DEFINER', '403-5', 'FORBIDDEN', False, 'The trigger or schedule belongs to another user; only that user, a database owner or an admin may change it'),
    ('SOURCE_REQUIRES_MANAGE', '403-6', 'FORBIDDEN', False, 'Procedure source requires script MANAGE'),
    ('USER_NOT_FOUND', '404-1', 'NOT_FOUND', False, 'User not found'),
    ('ENTRY_NOT_FOUND', '404-2', 'NOT_FOUND', False, 'Entry not found'),
    ('NO_RESULTS', '404-3', 'NOT_FOUND', False, 'No results'),
    ('DATABASE_NOT_FOUND', '404-4', 'NOT_FOUND', False, 'Database not found'),
    ('NO_USERS_FOUND', '404-5', 'NOT_FOUND', False, 'No users found'),
    ('INDEX_NOT_FOUND', '404-6', 'NOT_FOUND', False, 'No index registered for the specified field'),
    ('LISTEN_NOT_FOUND', '404-7', 'NOT_FOUND', False, 'Listen registration not found'),
    ('PROCEDURE_NOT_FOUND', '404-8', 'NOT_FOUND', False, 'Procedure not found'),
    ('TRIGGER_NOT_FOUND', '404-9', 'NOT_FOUND', False, 'Trigger not found'),
    ('COLLECTION_NOT_FOUND', '404-11', 'NOT_FOUND', False, 'Collection not found'),
    ('SCHEMA_NOT_FOUND', '404-12', 'NOT_FOUND', False, 'The collection has no schema'),
    ('SNAPSHOT_NOT_FOUND', '404-13', 'NOT_FOUND', False, 'The snapshot does not exist: it was released or expired, or it belongs to another connection'),
    ('SCRIPT_TIMEOUT', '408-1', 'ERROR', False, 'Script exceeded its time budget'),
    ('SCRIPT_CANCELLED', '408-2', 'ERROR', False, 'Script was cancelled'),
    ('FAULT_NOT_REACHED', '408-3', 'ERROR', False, 'The armed fault point was not reached in time'),
    ('AUTHENTICATION_TIMEOUT', '408-4', 'ERROR', False, 'The connection did not authenticate in time'),
    ('TRANSACTION_TOO_OLD', '408-5', 'ERROR', False, 'The transaction was rolled back after reaching its maximum age'),
    ('AGGREGATE_TIMEOUT', '408-6', 'ERROR', False, 'The aggregation exceeded its time budget'),
    ('USER_ALREADY_EXISTS', '409-1', 'ERROR', False, 'User already exists'),
    ('DATABASE_ALREADY_EXISTS', '409-2', 'ERROR', False, 'Database already exists'),
    ('TRANSACTION_ALREADY_ACTIVE', '409-3', 'ERROR', False, 'A transaction is already in progress for this connection'),
    ('NO_ACTIVE_TRANSACTION', '409-4', 'ERROR', False, 'No active transaction for this connection'),
    ('TRANSACTION_LOCK_TIMEOUT', '409-5', 'ERROR', False, 'Could not acquire the collection lock in time; transaction aborted'),
    ('OPERATION_NOT_ALLOWED_IN_TRANSACTION', '409-6', 'ERROR', False, 'Operation not allowed while a transaction is open'),
    ('TRANSACTION_ABORTED', '409-7', 'ERROR', False, 'Transaction aborted: a participant could not prepare'),
    ('PROCEDURE_VERSION_CONFLICT', '409-8', 'ERROR', False, 'The procedure or trigger was modified by someone else'),
    ('TRANSACTION_NOT_USABLE', '409-9', 'ERROR', False, 'The transaction was aborted and must be rolled back before continuing'),
    ('TRANSACTION_INDETERMINATE', '409-10', 'ERROR', False, "The transaction's outcome is indeterminate: part of it did not apply and recovery will re-drive it"),
    ('NAME_COLLIDES_ON_DISK', '409-11', 'ERROR', False, 'A name that differs only by case already exists and would share storage with it'),
    ('TRANSACTION_PREVIOUS_UNRESOLVED', '409-12', 'ERROR', False, 'A previous transaction on this connection is still being resolved on a participant; retry once it finishes'),
    ('TRANSACTION_SLICE_LOST', '409-13', 'ERROR', False, "A participant no longer holds this transaction's buffered writes (it restarted or reaped the session); roll back and retry"),
    ('TRANSACTION_ALREADY_COMMITTED', '409-14', 'ERROR', False, 'The transaction already committed on its owner; it can no longer be rolled back'),
    ('PRIMARY_KEY_REBUILD_REFUSED', '409-15', 'ERROR', False, 'The primary-key index cannot be rebuilt from the page files'),
    ('TRANSACTION_IDLE_TIMEOUT', '409-16', 'ERROR', False, 'The transaction was rolled back after being idle too long'),
    ('SNAPSHOT_ABORTED', '409-17', 'ERROR', False, 'The snapshot was aborted and holds nothing any more; open a new one'),
    ('LISTEN_ENDED', '410-1', 'NOT_FOUND', False, 'The listen ended'),
    ('OUTBOUND_BACKLOG', '413-1', 'ERROR', False, "The connection's outbound buffer overflowed"),
    ('NOT_COLLECTION_OWNER', '421-1', 'ERROR', False, 'This node is not the owner of the target collection'),
    ('TRANSACTION_READ_SPANS_NODES', '421-3', 'ERROR', False, 'This read joins collections the transaction wrote on different nodes'),
    ('AUTHENTICATION_ERROR', '500-1', 'ERROR', False, 'Error during authentication'),
    ('ERROR_CREATING_USER', '500-2', 'ERROR', False, 'Error creating user'),
    ('ERROR_DELETING_USER', '500-3', 'ERROR', False, 'Error deleting user'),
    ('ERROR_CHANGING_PASSWORD', '500-4', 'ERROR', False, 'Error changing password'),
    ('ERROR_CHANGING_PERMISSIONS', '500-5', 'ERROR', False, 'Error changing permissions'),
    ('ERROR_BULK_SAVING', '500-6', 'ERROR', False, 'Error while saving entries'),
    ('ERROR_SAVING', '500-7', 'ERROR', False, 'Error while saving entry'),
    ('ERROR_RETRIEVING', '500-8', 'ERROR', False, 'Error while retrieving entry'),
    ('ERROR_AGGREGATING', '500-9', 'ERROR', False, 'Error while processing aggregation'),
    ('ERROR_DELETING', '500-10', 'ERROR', False, 'Error while deleting entry'),
    ('ERROR_CREATING_DATABASE', '500-11', 'ERROR', False, 'Error while creating database'),
    ('ERROR_UPDATING_DATABASE_OWNERS', '500-12', 'ERROR', False, 'Error updating database owners'),
    ('ERROR_DROPPING_DATABASE', '500-13', 'ERROR', False, 'Error while dropping database'),
    ('ERROR_LISTING_DATABASES', '500-14', 'ERROR', False, 'Error while listing databases'),
    ('ERROR_CREATING_COLLECTION', '500-15', 'ERROR', False, 'Error while creating collection'),
    ('ERROR_DROPPING_COLLECTION', '500-16', 'ERROR', False, 'Error while dropping collection'),
    ('ERROR_LISTING_COLLECTIONS', '500-17', 'ERROR', False, 'Error while listing collections'),
    ('ERROR_CREATING_INDEX', '500-18', 'ERROR', False, 'Error while creating index'),
    ('ERROR_LISTING_USERS', '500-19', 'ERROR', False, 'Error listing users'),
    ('ERROR_DROPPING_INDEX', '500-20', 'ERROR', False, 'Error while dropping index'),
    ('ERROR_REINDEXING', '500-21', 'ERROR', False, 'Error while reindexing'),
    ('ERROR_GATHERING_STATS', '500-22', 'ERROR', False, 'Error while gathering database stats'),
    ('ERROR_LISTEN', '500-23', 'ERROR', False, 'Error while processing listen operation'),
    ('ERROR_TRANSACTION', '500-24', 'ERROR', False, 'Error while processing transaction operation'),
    ('ERROR_SAVING_SCHEMA', '500-25', 'ERROR', False, 'Error while saving collection schema'),
    ('ERROR_DELETING_SCHEMA', '500-26', 'ERROR', False, 'Error while deleting collection schema'),
    ('ERROR_SAVING_PROCEDURE', '500-27', 'ERROR', False, 'Error while saving the procedure'),
    ('ERROR_DELETING_PROCEDURE', '500-28', 'ERROR', False, 'Error while deleting the procedure'),
    ('ERROR_SAVING_TRIGGER', '500-29', 'ERROR', False, 'Error while saving the trigger'),
    ('ERROR_DELETING_TRIGGER', '500-30', 'ERROR', False, 'Error while deleting the trigger'),
    ('ERROR_SAVING_SCHEDULE', '500-31', 'ERROR', False, 'Error while saving the schedule'),
    ('ERROR_DELETING_SCHEDULE', '500-32', 'ERROR', False, 'Error while deleting the schedule'),
    ('TRANSACTION_HALF_APPLIED', '500-33', 'ERROR', False, 'A committed transaction could not be fully applied; its collections are fenced until it is finished'),
    ('WRITE_LOGGED_NOT_APPLIED', '500-34', 'ERROR', False, 'The write was logged durably but could not be applied; it is applied before the collection is next used, or at the next startup, so it may already have taken effect'),
    ('ERROR_GATHERING_SERVER_INFO', '500-35', 'ERROR', False, 'Error while gathering server info'),
    ('ERROR_STARTING_SNAPSHOT', '500-36', 'ERROR', False, 'Error while opening the snapshot'),
    ('ERROR_READING_SNAPSHOT', '500-37', 'ERROR', False, 'Error while reading the snapshot'),
    ('MAX_CONNECTIONS_REACHED', '503-1', 'ERROR', False, 'Max number of connections reached'),
    ('NO_QUORUM', '503-2', 'ERROR', True, 'Cluster does not have a write quorum'),
    ('REPLICATION_TIMEOUT', '503-3', 'ERROR', False, 'Timed out waiting for the replication quorum'),
    ('OWNER_UNREACHABLE', '503-4', 'ERROR', True, "The collection's owner node is unreachable"),
    ('ADMIN_SYNCING', '503-5', 'ERROR', True, 'Admin coordinator is synchronizing, retry shortly'),
    ('SCRIPT_CONCURRENCY_LIMIT', '503-6', 'ERROR', False, 'Too many scripts running, retry shortly'),
    ('SCRIPT_OUTCOME_UNKNOWN', '503-7', 'ERROR', False, 'The node the script was placed on did not report an outcome; it was not run again in case it already had'),
    ('WRITE_OUTCOME_UNKNOWN', '503-8', 'ERROR', False, "The collection's owner did not report an outcome; the write may already have been applied, so retrying is only safe for an idempotent write"),
    ('ADMIN_COORDINATOR_UNAVAILABLE', '503-9', 'ERROR', True, 'The admin coordinator could not be resolved, retry shortly'),
    ('SCHEMA_UNAVAILABLE', '503-11', 'ERROR', False, "The collection's schema could not be read, so the write cannot be validated; retry shortly"),
    ('COLLECTION_NOT_READY', '503-10', 'ERROR', True, 'The collection has not reached this node yet, retry shortly'),
    ('SERVER_SHUTTING_DOWN', '503-13', 'ERROR', False, 'The server is shutting down, retry against another node or later'),
    ('TOO_MANY_UNAUTHENTICATED_CONNECTIONS', '503-14', 'ERROR', False, 'Too many unauthenticated connections from this address'),
    ('LISTEN_RETAINED_BYTES_EXHAUSTED', '503-15', 'ERROR', False, 'The server holds the maximum bytes of listen registrations, retry later'),
    ('BACKGROUND_BACKLOG', '503-16', 'ERROR', True, 'The background index queue is full and nothing was written; retry shortly'),
    ('RESPONSE_BUDGET_EXHAUSTED', '503-17', 'ERROR', True, 'The server holds the maximum bytes of unsent responses; retry shortly'),
    ('COLLECTION_FENCED', '503-18', 'ERROR', True, 'The collection is held by a committed transaction that has not finished applying; retry shortly'),
    ('WRITES_NOT_APPLIED', '503-19', 'ERROR', True, 'The collection holds logged writes that could not be applied yet, so it cannot be read consistently; retry shortly'),
    ('AGGREGATE_BUDGET_EXHAUSTED', '503-20', 'ERROR', True, 'The server holds the maximum bytes of running aggregations; retry shortly'),
    ('TRANSACTION_BUDGET_EXHAUSTED', '503-21', 'ERROR', True, 'The server holds the maximum bytes of buffered transactions; the write was not buffered, retry shortly'),
    ('SNAPSHOT_LIMIT_REACHED', '503-22', 'ERROR', True, 'The server holds the maximum number of open snapshots; retry shortly'),
)
