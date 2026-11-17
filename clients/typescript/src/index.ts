export { LwnrdbClient, type TransactionBody } from "./client.js";
export { Database } from "./database.js";
export { Collection } from "./collection.js";
export { Transaction, TransactionCollection } from "./transaction.js";
export { Indexes } from "./indexes.js";
export { Schema } from "./schema.js";
export type { ListenHandler, ListenUpdate, Subscription } from "./subscription.js";
export type { ClientSettings, Credentials, ResolvedSettings, ServerAddress } from "./settings.js";
export type { TlsSettings } from "./tls-settings.js";
export { DEFAULT_RETRY_POLICY, NO_RETRIES, type RetryPolicy } from "./retry-policy.js";
export type { Document, DocumentCodec, Stored, Value } from "./document.js";
export type { AnalyzeResult, BulkSaveResult, ReadOptions, ReindexResult, SaveResult, ScriptResult } from "./results.js";
export { Query } from "./query/query.js";
export { and, distance, field, nand, nearest, nor, or, script, within, xor } from "./query/filters.js";
export { FieldCondition } from "./query/field-condition.js";
export { NearestFilter } from "./query/nearest-filter.js";
export {
    ArrayOperator,
    CastType,
    Conjunction,
    FieldOperator,
    Literal,
    MapOperation,
    Order,
    type ArrayOperation,
    type CastOperation,
    type ConjunctionFilter,
    type DistanceFilter,
    type FieldFilter,
    type FieldFunction,
    type Filter,
    type MidOperation,
    type PipelineStep,
    type ScriptFilter,
    type ScriptOperation,
    type WithinFilter,
} from "./query/types.js";
export * as MapOps from "./query/map-ops.js";
export * as MidOps from "./query/mid-ops.js";
export { CustomType } from "./values/custom-type.js";
export { GeoDistanceComparator } from "./values/geo-distance-comparator.js";
export { GeoPoint } from "./values/geo-point.js";
export { LocalDateTime } from "./values/local-date-time.js";
export { LocalTime } from "./values/local-time.js";
export { Vector } from "./values/vector.js";
export {
    AuthenticationError,
    ConflictError,
    ConnectionLostError,
    ForbiddenError,
    ListenEndedError,
    LwnrdbError,
    MappingError,
    NotFoundError,
    PoolExhaustedError,
    RequestTimeoutError,
    ServerClosedConnectionError,
    UnavailableError,
    UnknownOutcomeError,
    type LwnrdbErrorOptions,
} from "./errors/errors.js";
export { ERROR_CODES, ErrorCode, type ErrorCodeDetails } from "./errors/error-codes.js";
export { OPERATIONS, Operation, type OperationDetails, type OperationScope } from "./protocol/operations.js";
export { Status } from "./protocol/status.js";
export { Admin, DistributedTransactions, Scripts, TriggerRuns, Users } from "./admin/admin.js";
export {
    DataPermission,
    GlobalPermission,
    ScriptPermission,
    TransactionDecision,
    TriggerRunDecision,
    TriggerRunStatus,
    collectionKey,
    type Permissions,
    type ScriptRun,
    type ServerInfo,
    type TransactionInfo,
    type TriggerRun,
    type UserInfo,
} from "./admin/types.js";
export { Procedures } from "./scripting/procedures.js";
export { Schedules } from "./scripting/schedules.js";
export { Triggers } from "./scripting/triggers.js";
export {
    TriggerEvent,
    TriggerMode,
    TriggerTiming,
    type ProcedureDefinition,
    type ProcedureInfo,
    type ScheduleDefinition,
    type ScheduleInfo,
    type TriggerDefinition,
    type TriggerInfo,
    type TriggerSaved,
    type TriggerTestResult,
} from "./scripting/definitions.js";
