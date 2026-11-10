import { MapOperation, type MidOperation } from "./types.js";

export function set(field: string, operation: MidOperation): MapOperation {
    return new MapOperation(field, operation, undefined);
}

export function remove(field: string): MapOperation {
    return new MapOperation(field, undefined, undefined);
}
