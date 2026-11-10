import type { ScriptRun, ServerInfo, TransactionInfo, TriggerRun, UserInfo } from "../admin/types.js";
import type { Document } from "../document.js";
import type { ScriptResult } from "../results.js";
import type { ProcedureInfo, ScheduleInfo, TriggerInfo } from "../scripting/definitions.js";
import { booleanOf, strings, textOf } from "./response-decoder.js";
import { decodeValue, type JsonObject } from "./values-codec.js";

function optionalText(row: Document, field: string): string | undefined {
    const value = row[field];
    return typeof value === "string" ? value : undefined;
}

function text(row: Document, field: string): string {
    return optionalText(row, field) ?? "";
}

function optionalWhole(row: Document, field: string): number | undefined {
    const value = row[field];
    return typeof value === "number" ? value : undefined;
}

function whole(row: Document, field: string): number {
    return optionalWhole(row, field) ?? 0;
}

function flag(row: Document, field: string, fallback: boolean): boolean {
    const value = row[field];
    return typeof value === "boolean" ? value : fallback;
}

function list(row: Document, field: string): string[] {
    const value = row[field];
    return Array.isArray(value) ? value.map(String) : [];
}

function grants(row: Document, field: string): Document {
    const value = row[field];
    return typeof value === "object" && value !== null && !Array.isArray(value) && value.constructor === Object
        ? (value as Document)
        : {};
}

export function procedure(row: Document): ProcedureInfo {
    return {
        name: text(row, "name"),
        version: whole(row, "version"),
        enabled: flag(row, "enabled", true),
        description: optionalText(row, "description"),
        source: optionalText(row, "source"),
        raw: row,
    };
}

export function trigger(row: Document): TriggerInfo {
    return {
        name: text(row, "name"),
        collection: optionalText(row, "collection"),
        events: list(row, "events"),
        procedureName: text(row, "procedureName"),
        timing: optionalText(row, "timing") ?? "after",
        mode: optionalText(row, "mode") ?? "document",
        enabled: flag(row, "enabled", true),
        version: whole(row, "version"),
        definer: optionalText(row, "definer"),
        raw: row,
    };
}

export function schedule(row: Document): ScheduleInfo {
    return {
        name: text(row, "name"),
        procedureName: text(row, "procedureName"),
        cron: optionalText(row, "cron"),
        intervalMs: optionalWhole(row, "intervalMs"),
        enabled: flag(row, "enabled", true),
        version: whole(row, "version"),
        definer: optionalText(row, "definer"),
        nextRunAt: optionalWhole(row, "nextRunAt"),
        raw: row,
    };
}

export function user(row: Document): UserInfo {
    return {
        username: text(row, "_id"),
        admin: flag(row, "admin", false),
        globalPermissions: list(row, "globalPermissions"),
        databasePermissions: grants(row, "databasePermissions"),
        collectionPermissions: grants(row, "collectionPermissions"),
        scriptPermissions: grants(row, "scriptPermissions"),
        ownedDatabases: list(row, "ownedDatabases"),
        raw: row,
    };
}

export function transaction(row: Document): TransactionInfo {
    return {
        dtxId: text(row, "dtxId"),
        coordinator: optionalText(row, "coordinator"),
        coordinatorReachable: flag(row, "coordinatorReachable", false),
        participants: list(row, "participants"),
        ageMs: whole(row, "ageMs"),
        raw: row,
    };
}

export function scriptRun(row: Document): ScriptRun {
    return {
        runId: text(row, "runId"),
        node: optionalText(row, "node"),
        kind: text(row, "kind"),
        database: optionalText(row, "database"),
        name: optionalText(row, "name"),
        username: optionalText(row, "username"),
        ageMs: whole(row, "ageMs"),
        raw: row,
    };
}

export function triggerRun(row: Document): TriggerRun {
    return {
        runId: text(row, "runId"),
        status: text(row, "status"),
        database: optionalText(row, "database"),
        collection: optionalText(row, "collection"),
        trigger: optionalText(row, "trigger"),
        procedure: optionalText(row, "procedure"),
        event: optionalText(row, "event"),
        attempts: whole(row, "attempts"),
        lastError: optionalText(row, "lastError"),
        raw: row,
    };
}

export function server(row: Document): ServerInfo {
    return { state: optionalText(row, "state"), version: optionalText(row, "version"), raw: row };
}

export function scriptResult(reply: JsonObject): ScriptResult {
    const result = reply.result;
    return {
        value: result === undefined ? undefined : decodeValue(result),
        logs: strings(reply.logs),
        logsTruncated: booleanOf(reply.logsTruncated, false),
        runId: textOf(reply.runId),
    };
}
