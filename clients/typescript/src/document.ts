import type { GeoPoint } from "./values/geo-point.js";
import type { LocalDateTime } from "./values/local-date-time.js";
import type { LocalTime } from "./values/local-time.js";
import type { Vector } from "./values/vector.js";

export type Value =
    string | number | boolean | null | LocalDateTime | LocalTime | GeoPoint | Vector | Value[] | Document;

export interface Document {
    [field: string]: Value;
}

export type Stored<T> = T & { _id: string };

export interface DocumentCodec<T, R = T> {
    encode(value: T): Document;
    decode(document: Document): R;
}
