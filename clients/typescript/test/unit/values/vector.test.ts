import { describe, expect, test } from "vitest";
import { Vector } from "../../../src/index.js";

describe("Vector", () => {
    test("copies its input and exposes its components", () => {
        const input = [1, 2, 3];
        const vector = Vector.from(input);
        input[0] = 99;
        expect(vector.toArray()).toEqual([1, 2, 3]);
        expect(vector.dimensions).toBe(3);
        expect(vector.component(1)).toBe(2);
        expect(vector.toString()).toBe("Vector[1, 2, 3]");
    });

    test("keeps the sign of -0 and compares exactly", () => {
        const vector = Vector.of(-0, 1);
        expect(Object.is(vector.component(0), -0)).toBe(true);
        expect(vector.equals(Vector.of(-0, 1))).toBe(true);
        expect(vector.equals(Vector.of(0, 1))).toBe(false);
        expect(vector.equals(Vector.of(-0))).toBe(false);
        expect(vector.equals([-0, 1])).toBe(false);
    });

    test("rejects empty and non-finite vectors and out-of-range components", () => {
        expect(() => Vector.of()).toThrow(RangeError);
        expect(() => Vector.of(1, Number.NaN)).toThrow(/component 1/);
        expect(() => Vector.of(1).component(1)).toThrow(RangeError);
    });
});
