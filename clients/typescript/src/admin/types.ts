import type { Document } from "../document.js";

export const DataPermission = { READ: "READ", READ_WRITE: "READ_WRITE" } as const;
export type DataPermission = (typeof DataPermission)[keyof typeof DataPermission];

export const GlobalPermission = { CREATE_DATABASE: "CREATE_DATABASE", DROP_DATABASE: "DROP_DATABASE" } as const;
export type GlobalPermission = (typeof GlobalPermission)[keyof typeof GlobalPermission];

export const ScriptPermission = { NONE: "NONE", RUN: "RUN", MANAGE: "MANAGE" } as const;
export type ScriptPermission = (typeof ScriptPermission)[keyof typeof ScriptPermission];

export const TransactionDecision = { COMMIT: "commit", ABORT: "abort" } as const;
export type TransactionDecision = (typeof TransactionDecision)[keyof typeof TransactionDecision];

export const TriggerRunStatus = { PENDING: "PENDING", DEAD: "DEAD", STAGED: "STAGED" } as const;
export type TriggerRunStatus = (typeof TriggerRunStatus)[keyof typeof TriggerRunStatus];

export const TriggerRunDecision = { REPLAY: "replay", DISCARD: "discard" } as const;
export type TriggerRunDecision = (typeof TriggerRunDecision)[keyof typeof TriggerRunDecision];

export interface Permissions {
    readonly admin?: boolean;
    readonly global?: readonly GlobalPermission[];
    readonly databases?: Readonly<Record<string, DataPermission>>;
    readonly collections?: Readonly<Record<string, DataPermission>>;
    readonly scripts?: Readonly<Record<string, ScriptPermission>>;
}

export function collectionKey(database: string, collection: string): string {
    return `${database}|${collection}`;
}

export interface UserInfo {
    readonly username: string;
    readonly admin: boolean;
    readonly globalPermissions: readonly string[];
    readonly databasePermissions: Document;
    readonly collectionPermissions: Document;
    readonly scriptPermissions: Document;
    readonly ownedDatabases: readonly string[];
    readonly raw: Document;
}

export interface TransactionInfo {
    readonly dtxId: string;
    readonly coordinator: string | undefined;
    readonly coordinatorReachable: boolean;
    readonly participants: readonly string[];
    readonly ageMs: number;
    readonly raw: Document;
}

export interface ScriptRun {
    readonly runId: string;
    readonly node: string | undefined;
    readonly kind: string;
    readonly database: string | undefined;
    readonly name: string | undefined;
    readonly username: string | undefined;
    readonly ageMs: number;
    readonly raw: Document;
}

export interface TriggerRun {
    readonly runId: string;
    readonly status: string;
    readonly database: string | undefined;
    readonly collection: string | undefined;
    readonly trigger: string | undefined;
    readonly procedure: string | undefined;
    readonly event: string | undefined;
    readonly attempts: number;
    readonly lastError: string | undefined;
    readonly raw: Document;
}

export interface ServerInfo {
    readonly state: string | undefined;
    readonly version: string | undefined;
    readonly raw: Document;
}
