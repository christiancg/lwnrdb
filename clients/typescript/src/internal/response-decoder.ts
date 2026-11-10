import type { Document } from "../document.js";
import { errorCodeDetails } from "../errors/error-codes.js";
import {
    AuthenticationError,
    ConflictError,
    ForbiddenError,
    ListenEndedError,
    LwnrdbError,
    NotFoundError,
    UnavailableError,
    UnknownOutcomeError,
    type LwnrdbErrorOptions,
} from "../errors/errors.js";
import { Status } from "../protocol/status.js";
import { decodeDocument, type Json, type JsonObject } from "./values-codec.js";

const FAILURES = new Set<Status>([Status.ERROR, Status.NOT_FOUND, Status.UNAUTHENTICATED, Status.FORBIDDEN]);
const UNKNOWN_OUTCOME = new Set(["500-33", "500-34"]);
const KNOWN_STATUSES = new Set<string>(Object.values(Status));

type ErrorClass = new (message: string, options: LwnrdbErrorOptions) => LwnrdbError;

const BY_RANGE = new Map<string, ErrorClass>([
    ["401", AuthenticationError],
    ["403", ForbiddenError],
    ["404", NotFoundError],
    ["409", ConflictError],
    ["410", ListenEndedError],
    ["503", UnavailableError],
]);

const BY_STATUS = new Map<Status, ErrorClass>([
    [Status.UNAUTHENTICATED, AuthenticationError],
    [Status.FORBIDDEN, ForbiddenError],
    [Status.NOT_FOUND, NotFoundError],
]);

export function textOf(node: Json | undefined): string | undefined {
    if (node === undefined || node === null) {
        return undefined;
    }
    return typeof node === "object" ? JSON.stringify(node) : String(node);
}

function statusOf(frame: JsonObject): Status | undefined {
    const status = textOf(frame.status);
    if (status === undefined) {
        return undefined;
    }
    return KNOWN_STATUSES.has(status) ? (status as Status) : Status.ERROR;
}

export function check(frame: JsonObject): JsonObject {
    const status = statusOf(frame);
    if (status !== undefined && FAILURES.has(status)) {
        throw raise(frame);
    }
    return frame;
}

function errorClassOf(code: string | undefined, status: Status | undefined): ErrorClass {
    if (code !== undefined && UNKNOWN_OUTCOME.has(code)) {
        return UnknownOutcomeError;
    }
    const byRange = code === undefined ? undefined : BY_RANGE.get(code.slice(0, 3));
    return byRange ?? (status === undefined ? undefined : BY_STATUS.get(status)) ?? LwnrdbError;
}

export function raise(frame: JsonObject): LwnrdbError {
    const code = textOf(frame.errorCode);
    const status = statusOf(frame);
    const message = textOf(frame.message) ?? errorCodeDetails(code)?.message ?? "request failed";
    const text = code === undefined ? message : `${message} (${code})`;
    const ErrorType = errorClassOf(code, status);
    return new ErrorType(text, { code, status, operation: textOf(frame.type) });
}

function isObject(node: Json | undefined): node is JsonObject {
    return typeof node === "object" && node !== null && !Array.isArray(node);
}

export function documents(frame: JsonObject, field: string): Document[] {
    const node = frame[field];
    return Array.isArray(node) ? node.filter(isObject).map(decodeDocument) : [];
}

export function document(frame: JsonObject, field: string): Document | undefined {
    const node = frame[field];
    return isObject(node) ? decodeDocument(node) : undefined;
}

export function strings(node: Json | undefined): string[] {
    return Array.isArray(node) ? node.map((item) => textOf(item) ?? "null") : [];
}

export function booleanOf(node: Json | undefined, fallback: boolean): boolean {
    return typeof node === "boolean" ? node : fallback;
}

export function numberOf(node: Json | undefined): number | undefined {
    return typeof node === "number" ? node : undefined;
}
