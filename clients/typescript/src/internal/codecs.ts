import type { Document, DocumentCodec, Stored } from "../document.js";
import type { RowCodec } from "./collection-ops.js";

export function identityCodec<T>(): RowCodec<T, Stored<T>> {
    return {
        encode: (value) => value,
        decode: (document: Document) => document as Stored<T>,
    };
}

export function rowCodec<T, R>(codec: DocumentCodec<T, R>): RowCodec<T, R> {
    return {
        encode: (value) => codec.encode(value),
        decode: (document) => codec.decode(document),
    };
}
