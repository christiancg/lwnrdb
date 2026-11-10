import { looksCustom, parseCustom } from "../../src/internal/custom-values.js";
import type { Json } from "../../src/internal/values-codec.js";
import { GeoPoint, LocalDateTime, LocalTime, Vector } from "../../src/index.js";

function sameCustomValue(left: string, right: string): boolean {
    const parsedLeft = parseCustom(left);
    const parsedRight = parseCustom(right);
    if (
        parsedLeft instanceof GeoPoint ||
        parsedLeft instanceof Vector ||
        parsedLeft instanceof LocalDateTime ||
        parsedLeft instanceof LocalTime
    ) {
        return parsedLeft.equals(parsedRight);
    }
    return left === right;
}

export function equivalent(left: Json | undefined, right: Json | undefined): boolean {
    if (typeof left === "number" && typeof right === "number") {
        return Object.is(left, right) || left === right;
    }
    if (typeof left === "string" && typeof right === "string") {
        return left === right || (looksCustom(left) && looksCustom(right) && sameCustomValue(left, right));
    }
    if (Array.isArray(left) && Array.isArray(right)) {
        return left.length === right.length && left.every((item, index) => equivalent(item, right[index]));
    }
    if (isObject(left) && isObject(right)) {
        const leftKeys = Object.keys(left).sort();
        const rightKeys = Object.keys(right).sort();
        return (
            leftKeys.length === rightKeys.length &&
            leftKeys.every((key, index) => key === rightKeys[index] && equivalent(left[key], right[key]))
        );
    }
    return left === right;
}

function isObject(value: Json | undefined): value is Record<string, Json> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}
