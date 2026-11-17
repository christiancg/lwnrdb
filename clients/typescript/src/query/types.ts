import type { CustomType } from "../values/custom-type.js";
import type { GeoDistanceComparator } from "../values/geo-distance-comparator.js";
import type { GeoPoint } from "../values/geo-point.js";
import type { NearestFilter } from "./nearest-filter.js";

export const FieldOperator = {
    EQUALS: "EQUALS",
    NOT_EQUALS: "NOT_EQUALS",
    GREATER_THAN: "GREATER_THAN",
    GREATER_THAN_EQUALS: "GREATER_THAN_EQUALS",
    SMALLER_THAN: "SMALLER_THAN",
    SMALLER_THAN_EQUALS: "SMALLER_THAN_EQUALS",
    IN: "IN",
    NOT_IN: "NOT_IN",
    CONTAINS: "CONTAINS",
} as const;

export type FieldOperator = (typeof FieldOperator)[keyof typeof FieldOperator];

export const Conjunction = {
    AND: "AND",
    OR: "OR",
    NOR: "NOR",
    XOR: "XOR",
    NAND: "NAND",
} as const;

export type Conjunction = (typeof Conjunction)[keyof typeof Conjunction];

export const Order = {
    ASC: "ASC",
    DESC: "DESC",
} as const;

export type Order = (typeof Order)[keyof typeof Order];

export const ArrayOperator = {
    AVG: "AVG",
    SUM: "SUM",
    SUBS: "SUBS",
    MAX: "MAX",
    MIN: "MIN",
    MULTIPLY: "MULTIPLY",
    DIVIDE: "DIVIDE",
    POW: "POW",
    ROOT: "ROOT",
    CONCAT: "CONCAT",
} as const;

export type ArrayOperator = (typeof ArrayOperator)[keyof typeof ArrayOperator];

export const CastType = {
    NUMBER: "NUMBER",
    STRING: "STRING",
    BOOLEAN: "BOOLEAN",
    JSON_CUSTOM: "JSON_CUSTOM",
} as const;

export type CastType = (typeof CastType)[keyof typeof CastType];

export interface FieldFilter {
    readonly kind: "field";
    readonly operator: FieldOperator;
    readonly field: string;
    readonly value: unknown;
}

export interface ConjunctionFilter {
    readonly kind: "conjunction";
    readonly conjunction: Conjunction;
    readonly operands: readonly Filter[];
}

export interface DistanceFilter {
    readonly kind: "distance";
    readonly field: string;
    readonly target: GeoPoint;
    readonly comparator: GeoDistanceComparator;
    readonly metres: number;
}

export interface WithinFilter {
    readonly kind: "within";
    readonly field: string;
    readonly polygon: readonly GeoPoint[];
}

export interface ScriptFilter {
    readonly kind: "script";
    readonly source: string;
}

export type Filter = FieldFilter | ConjunctionFilter | DistanceFilter | WithinFilter | NearestFilter | ScriptFilter;

export class Literal {
    static readonly WIRE_PREFIX = "-";
    readonly text: string;

    constructor(text: string) {
        this.text = text;
        Object.freeze(this);
    }
}

export interface ArrayOperation {
    readonly kind: "array";
    readonly operator: ArrayOperator;
    readonly operands: readonly unknown[];
}

export interface FieldFunction {
    readonly kind: "function";
    readonly function: "ABS" | "SIZE";
    readonly field: string;
}

export interface CastOperation {
    readonly kind: "cast";
    readonly field: string;
    readonly type: CastType;
    readonly customType: CustomType | undefined;
}

export interface ScriptOperation {
    readonly kind: "script";
    readonly source: string;
}

export type MidOperation = ArrayOperation | FieldFunction | CastOperation | ScriptOperation;

export type PipelineStep =
    | { readonly type: "FILTER"; readonly filter: Filter }
    | { readonly type: "MAP"; readonly operations: readonly MapOperation[] }
    | { readonly type: "GROUP_BY"; readonly field: string }
    | {
          readonly type: "JOIN";
          readonly collection: string;
          readonly localField: string;
          readonly remoteField: string;
          readonly asField: string;
      }
    | { readonly type: "COUNT" }
    | { readonly type: "DISTINCT"; readonly field: string | undefined }
    | { readonly type: "LIMIT"; readonly limit: number }
    | { readonly type: "SKIP"; readonly skip: number }
    | { readonly type: "SORT"; readonly field: string; readonly ascending: boolean }
    | {
          readonly type: "REDUCE";
          readonly script: string;
          readonly hasInitialValue: boolean;
          readonly initialValue: unknown;
          readonly resultField: string | undefined;
      };

export class MapOperation {
    readonly field: string;
    readonly operation: MidOperation | undefined;
    readonly condition: Filter | undefined;

    constructor(field: string, operation: MidOperation | undefined, condition: Filter | undefined) {
        this.field = requireText(field, "field");
        this.operation = operation;
        this.condition = condition;
        Object.freeze(this);
    }

    when(condition: Filter): MapOperation {
        return new MapOperation(this.field, this.operation, condition);
    }

    get removes(): boolean {
        return this.operation === undefined;
    }
}

export function requireText(value: unknown, name: string): string {
    if (typeof value !== "string") {
        throw new TypeError(`${name} must be a string`);
    }
    return value;
}
