import type { GeoDistanceComparator } from "../values/geo-distance-comparator.js";
import { GeoPoint } from "../values/geo-point.js";
import type { Vector } from "../values/vector.js";
import { FieldCondition } from "./field-condition.js";
import { NearestFilter } from "./nearest-filter.js";
import { Conjunction, requireText, type Filter } from "./types.js";

const MIN_POLYGON_POINTS = 3;

function conjunction(type: Conjunction, operands: readonly Filter[]): Filter {
    if (operands.length === 0) {
        throw new RangeError(`${type} needs at least one operand`);
    }
    return Object.freeze({ kind: "conjunction", conjunction: type, operands: Object.freeze([...operands]) });
}

function requirePoint(point: unknown, name: string): GeoPoint {
    if (!(point instanceof GeoPoint)) {
        throw new TypeError(`${name} must be a GeoPoint`);
    }
    return point;
}

export function field(name: string): FieldCondition {
    return new FieldCondition(name);
}

export function and(...operands: Filter[]): Filter {
    return conjunction(Conjunction.AND, operands);
}

export function or(...operands: Filter[]): Filter {
    return conjunction(Conjunction.OR, operands);
}

export function nor(...operands: Filter[]): Filter {
    return conjunction(Conjunction.NOR, operands);
}

export function xor(...operands: Filter[]): Filter {
    return conjunction(Conjunction.XOR, operands);
}

export function nand(...operands: Filter[]): Filter {
    return conjunction(Conjunction.NAND, operands);
}

export function distance(name: string, target: GeoPoint, comparator: GeoDistanceComparator, metres: number): Filter {
    if (!Number.isFinite(metres) || metres < 0) {
        throw new RangeError(`distance must be a finite, non-negative number of metres: ${metres}`);
    }
    return Object.freeze({
        kind: "distance",
        field: requireText(name, "field"),
        target: requirePoint(target, "target"),
        comparator,
        metres,
    });
}

export function within(name: string, ...polygon: GeoPoint[]): Filter {
    if (polygon.length < MIN_POLYGON_POINTS) {
        throw new RangeError(`a within polygon needs at least 3 points, got ${polygon.length}`);
    }
    return Object.freeze({
        kind: "within",
        field: requireText(name, "field"),
        polygon: Object.freeze(polygon.map((point) => requirePoint(point, "a polygon point"))),
    });
}

export function nearest(name: string, query: Vector, k: number): NearestFilter {
    return new NearestFilter(name, query, k);
}

export function script(source: string): Filter {
    return Object.freeze({ kind: "script", source: requireText(source, "source") });
}
