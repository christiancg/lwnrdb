export type OperationScope = "DATABASE" | "COLLECTION" | "OPTIONAL_COLLECTION" | "NONE";

export interface OperationDetails {
    readonly name: string;
    readonly scope: OperationScope;
    readonly adminOnly: boolean;
    readonly allowedInTransaction: boolean;
    readonly read: boolean;
}

const CATALOGUE = [
    ["BULK_SAVE", "COLLECTION", false, true, false],
    ["SAVE", "COLLECTION", false, true, false],
    ["FIND_BY_ID", "COLLECTION", false, true, true],
    ["AGGREGATE", "COLLECTION", false, true, true],
    ["DELETE", "COLLECTION", false, true, false],
    ["CREATE_DATABASE", "DATABASE", false, false, false],
    ["DROP_DATABASE", "DATABASE", false, false, false],
    ["LIST_DATABASES", "NONE", false, false, true],
    ["CREATE_COLLECTION", "COLLECTION", false, false, false],
    ["DROP_COLLECTION", "COLLECTION", false, false, false],
    ["LIST_COLLECTIONS", "DATABASE", false, false, true],
    ["CREATE_INDEX", "COLLECTION", false, false, false],
    ["DROP_INDEX", "COLLECTION", false, false, false],
    ["REINDEX", "COLLECTION", false, false, false],
    ["SAVE_SCHEMA", "COLLECTION", false, false, false],
    ["DELETE_SCHEMA", "COLLECTION", false, false, false],
    ["GET_SCHEMA", "COLLECTION", false, false, true],
    ["CLOSE_CONNECTION", "NONE", false, true, false],
    ["SERVER_INFO", "NONE", false, true, true],
    ["AUTHENTICATE", "NONE", false, false, false],
    ["CREATE_USER", "NONE", true, false, false],
    ["DELETE_USER", "NONE", true, false, false],
    ["CHANGE_PERMISSIONS", "NONE", true, false, false],
    ["SET_DATABASE_OWNERS", "DATABASE", true, false, false],
    ["LIST_USERS", "NONE", true, false, true],
    ["SET_PASSWORD", "NONE", false, false, false],
    ["GET_DATABASE_STATS", "DATABASE", false, false, true],
    ["LISTEN", "COLLECTION", false, false, false],
    ["STOP_LISTEN", "NONE", false, false, false],
    ["START_TRANSACTION", "NONE", false, true, false],
    ["COMMIT_TRANSACTION", "NONE", false, true, false],
    ["ROLLBACK_TRANSACTION", "NONE", false, true, false],
    ["RESOLVE_TRANSACTION", "NONE", true, false, false],
    ["LIST_TRANSACTIONS", "NONE", true, false, true],
    ["RUN_SCRIPT", "DATABASE", false, false, false],
    ["SAVE_PROCEDURE", "DATABASE", false, false, false],
    ["DELETE_PROCEDURE", "DATABASE", false, false, false],
    ["LIST_PROCEDURES", "DATABASE", false, false, true],
    ["CALL_PROCEDURE", "DATABASE", false, false, false],
    ["SAVE_TRIGGER", "COLLECTION", false, false, false],
    ["DELETE_TRIGGER", "COLLECTION", false, false, false],
    ["LIST_TRIGGERS", "OPTIONAL_COLLECTION", false, false, true],
    ["SAVE_SCHEDULE", "DATABASE", false, false, false],
    ["DELETE_SCHEDULE", "DATABASE", false, false, false],
    ["LIST_SCHEDULES", "DATABASE", false, false, true],
    ["LIST_SCRIPTS", "NONE", true, false, true],
    ["CANCEL_SCRIPT", "NONE", true, false, false],
    ["TEST_TRIGGER", "COLLECTION", false, false, false],
    ["LIST_TRIGGER_RUNS", "NONE", true, false, true],
    ["RESOLVE_TRIGGER_RUN", "NONE", true, false, false],
] as const;

type CatalogueEntry = (typeof CATALOGUE)[number];

export type Operation = CatalogueEntry[0];

export const Operation = Object.freeze(Object.fromEntries(CATALOGUE.map(([name]) => [name, name]))) as {
    readonly [Entry in CatalogueEntry as Entry[0]]: Entry[0];
};

export const OPERATIONS: readonly OperationDetails[] = Object.freeze(
    CATALOGUE.map(([name, scope, adminOnly, allowedInTransaction, read]) =>
        Object.freeze({ name, scope, adminOnly, allowedInTransaction, read }),
    ),
);

const BY_NAME = new Map(OPERATIONS.map((details) => [details.name, details]));

export function operationDetails(operation: Operation): OperationDetails {
    const details = BY_NAME.get(operation);
    if (details === undefined) {
        throw new TypeError(`unknown operation ${operation}`);
    }
    return details;
}
