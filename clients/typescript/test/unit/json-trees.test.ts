import { describe, expect, test } from "vitest";
import { equivalent } from "../support/json-trees.js";

describe("equivalent", () => {
    test("compares numbers by value and custom values by what they parse to", () => {
        expect(equivalent({ a: [1, 2.5] }, { a: [1.0, 2.5] })).toBe(true);
        expect(equivalent("#geo(1,2)", "#geo(1.0,2.0)")).toBe(true);
        expect(equivalent("#vector(0.00044,1e+21)", "#vector(4.4E-4,1.0E21)")).toBe(true);
        expect(equivalent("#datetime(2024-01-01T10:00)", "#datetime(2024-01-01T10:00:00)")).toBe(true);
        expect(equivalent("#time(09:30)", "#time(09:30:00.000)")).toBe(true);
        expect(equivalent("#unknown(a)", "#unknown(a)")).toBe(true);
    });

    test("tells different trees apart", () => {
        expect(equivalent("#geo(1,2)", "#geo(2,1)")).toBe(false);
        expect(equivalent("#vector(-0.0)", "#vector(0)")).toBe(false);
        expect(equivalent("#datetime(2024-01-01T10:00)", "#time(10:00)")).toBe(false);
        expect(equivalent("#unknown(a)", "#unknown(b)")).toBe(false);
        expect(equivalent({ a: 1 }, { a: 1, b: 2 })).toBe(false);
        expect(equivalent({ a: 1 }, { b: 1 })).toBe(false);
        expect(equivalent([1, 2], [2, 1])).toBe(false);
        expect(equivalent("a", "A")).toBe(false);
        expect(equivalent(null, {})).toBe(false);
        expect(equivalent(1, "1")).toBe(false);
    });
});
