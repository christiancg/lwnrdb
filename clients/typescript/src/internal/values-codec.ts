import type { Document, Value } from "../document.js";
import { MappingError } from "../errors/errors.js";
import { LocalDateTime } from "../values/local-date-time.js";
import { formatCustom, isCustomValue, looksCustom, parseCustom } from "./custom-values.js";
import { checkNumber, label } from "./number-guard.js";

export type Json = string | number | boolean | null | Json[] | { [field: string]: Json };
export type JsonObject = Record<string, Json>;

export interface EncodeOptions {
    readonly temporalZone?: string | undefined;
}

const REFUSED_OBJECTS: readonly [new (...args: never[]) => object, string][] = [
    [Map, "a Map"],
    [Set, "a Set"],
    [WeakMap, "a WeakMap"],
    [WeakSet, "a WeakSet"],
    [Promise, "a Promise"],
    [RegExp, "a RegExp"],
    [ArrayBuffer, "an ArrayBuffer"],
];

function child(path: string, key: string): string {
    return path === "" ? key : `${path}.${key}`;
}

function encodeDate(date: Date, path: string, options: EncodeOptions): string {
    if (options.temporalZone === undefined) {
        throw new MappingError(
            `${label(path)} is a Date, an instant; set temporalZone or store a LocalDateTime, since the server's date-time has no zone`,
        );
    }
    return formatCustom(LocalDateTime.fromDate(date, options.temporalZone));
}

function encodeObject(value: object, path: string, options: EncodeOptions): JsonObject {
    const refused = REFUSED_OBJECTS.find(([type]) => value instanceof type);
    if (refused !== undefined || ArrayBuffer.isView(value)) {
        throw new MappingError(`${label(path)} is ${refused?.[1] ?? "a binary view"}, which has no JSON form`);
    }
    const encoded: JsonObject = {};
    for (const [key, item] of Object.entries(value)) {
        if (item !== undefined) {
            encoded[key] = encodeValue(item, child(path, key), options);
        }
    }
    return encoded;
}

export function encodeValue(value: unknown, path = "", options: EncodeOptions = {}): Json {
    switch (typeof value) {
        case "string":
            if (looksCustom(value)) {
                throw new MappingError(
                    `${label(path)} looks like a custom value (${value}); the server would read it as one, so it cannot be stored as text`,
                );
            }
            return value;
        case "number":
            return checkNumber(value, path);
        case "boolean":
            return value;
        case "object":
            break;
        default:
            throw new MappingError(`${label(path)} is ${typeof value}, which has no JSON form`);
    }
    if (value === null) {
        return null;
    }
    if (isCustomValue(value)) {
        return formatCustom(value);
    }
    if (value instanceof Date) {
        return encodeDate(value, path, options);
    }
    if (Array.isArray(value)) {
        return Array.from(value as unknown[], (item, index) => {
            if (item === undefined) {
                throw new MappingError(`${label(`${path}[${index}]`)} is undefined, which an array cannot hold`);
            }
            return encodeValue(item, `${path}[${index}]`, options);
        });
    }
    return encodeObject(value, path, options);
}

export function encodeDocument(value: unknown, options: EncodeOptions = {}): JsonObject {
    if (typeof value !== "object" || value === null || Array.isArray(value) || isCustomValue(value)) {
        throw new MappingError("a document must be a plain object");
    }
    return encodeObject(value, "", options);
}

export function decodeValue(value: unknown): Value {
    if (typeof value === "string") {
        return looksCustom(value) ? parseCustom(value) : value;
    }
    if (Array.isArray(value)) {
        return value.map(decodeValue);
    }
    if (typeof value === "object" && value !== null) {
        return decodeDocument(value);
    }
    return value as Value;
}

export function decodeDocument(value: object): Document {
    const decoded: Document = {};
    for (const [key, item] of Object.entries(value)) {
        decoded[key] = decodeValue(item);
    }
    return decoded;
}
