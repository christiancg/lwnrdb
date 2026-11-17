import { CustomType } from "../values/custom-type.js";
import { ArrayOperator, CastType, Literal, requireText, type MidOperation } from "./types.js";

function array(operator: ArrayOperator, operands: readonly unknown[]): MidOperation {
    if (operands.length === 0) {
        throw new RangeError(`${operator} needs at least one operand`);
    }
    return Object.freeze({ kind: "array", operator, operands: Object.freeze([...operands]) });
}

export function avg(...operands: unknown[]): MidOperation {
    return array(ArrayOperator.AVG, operands);
}

export function sum(...operands: unknown[]): MidOperation {
    return array(ArrayOperator.SUM, operands);
}

export function subs(...operands: unknown[]): MidOperation {
    return array(ArrayOperator.SUBS, operands);
}

export function max(...operands: unknown[]): MidOperation {
    return array(ArrayOperator.MAX, operands);
}

export function min(...operands: unknown[]): MidOperation {
    return array(ArrayOperator.MIN, operands);
}

export function multiply(...operands: unknown[]): MidOperation {
    return array(ArrayOperator.MULTIPLY, operands);
}

export function divide(...operands: unknown[]): MidOperation {
    return array(ArrayOperator.DIVIDE, operands);
}

export function pow(...operands: unknown[]): MidOperation {
    return array(ArrayOperator.POW, operands);
}

export function root(...operands: unknown[]): MidOperation {
    return array(ArrayOperator.ROOT, operands);
}

export function concat(...operands: unknown[]): MidOperation {
    return array(ArrayOperator.CONCAT, operands);
}

export function literal(text: string): Literal {
    return new Literal(requireText(text, "text"));
}

export function abs(field: string): MidOperation {
    return Object.freeze({ kind: "function", function: "ABS", field: requireText(field, "field") });
}

export function size(field: string): MidOperation {
    return Object.freeze({ kind: "function", function: "SIZE", field: requireText(field, "field") });
}

export function cast(field: string, type: CastType | CustomType): MidOperation {
    const isCastType = Object.values<string>(CastType).includes(type);
    if (type === CastType.JSON_CUSTOM || (!isCastType && !Object.values<string>(CustomType).includes(type))) {
        throw new TypeError(
            `cast takes a CastType other than JSON_CUSTOM, or a CustomType such as CustomType.GEO: ${type}`,
        );
    }
    return Object.freeze({
        kind: "cast",
        field: requireText(field, "field"),
        type: isCastType ? (type as CastType) : CastType.JSON_CUSTOM,
        customType: isCastType ? undefined : (type as CustomType),
    });
}

export function script(source: string): MidOperation {
    return Object.freeze({ kind: "script", source: requireText(source, "source") });
}
