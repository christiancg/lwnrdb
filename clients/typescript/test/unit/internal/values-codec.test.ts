import { describe, expect, test } from "vitest";
import { GeoPoint, LocalDateTime, LocalTime, MappingError, Vector } from "../../../src/index.js";
import { decodeValue, encodeDocument, encodeValue } from "../../../src/internal/values-codec.js";

describe("encodeValue", () => {
    test("turns custom values into tagged strings at any depth", () => {
        expect(
            encodeValue({
                a: [{ at: LocalDateTime.of(2024, 1, 1, 10, 0) }],
                t: LocalTime.of(9, 30),
                g: GeoPoint.of(1, -0),
                v: Vector.of(0.00044, 1e21, -0),
            }),
        ).toEqual({
            a: [{ at: "#datetime(2024-01-01T10:00)" }],
            t: "#time(09:30)",
            g: "#geo(1,-0.0)",
            v: "#vector(0.00044,1e+21,-0.0)",
        });
    });

    test("refuses text that looks like a custom value, naming its path", () => {
        expect(() => encodeValue({ a: { b: ["#geo(1,2)"] } })).toThrow(/field 'a.b\[0]' looks like a custom value/);
        expect(encodeValue("#ab(x)")).toBe("#ab(x)");
        expect(encodeValue("#geo(1,2")).toBe("#geo(1,2");
    });

    test("refuses numbers the server cannot hold", () => {
        expect(() => encodeValue({ n: Number.NaN })).toThrow(/field 'n' is not a finite number/);
        expect(() => encodeValue(Number.POSITIVE_INFINITY)).toThrow(/the value is not a finite number/);
        expect(() => encodeValue({ n: 2 ** 53 + 2 })).toThrow(/exceeds 2\^53/);
        expect(encodeValue(2 ** 53 - 1)).toBe(2 ** 53 - 1);
        expect(encodeValue(0.5)).toBe(0.5);
    });

    test("refuses values with no JSON form", () => {
        expect(() => encodeValue({ n: 10n })).toThrow(/bigint/);
        expect(() => encodeValue({ f: () => 1 })).toThrow(/function/);
        expect(() => encodeValue({ s: Symbol("x") })).toThrow(/symbol/);
        expect(() => encodeValue([1, undefined])).toThrow(/'\[1]' is undefined/);
        expect(() => encodeValue(new Map())).toThrow(/a Map/);
        expect(() => encodeValue({ s: new Set() })).toThrow(/a Set/);
        expect(() => encodeValue(new Uint8Array(2))).toThrow(/a binary view/);
        expect(() => encodeValue(undefined)).toThrow(MappingError);
    });

    test("drops undefined properties and encodes class instances by their own fields", () => {
        class Point {
            readonly x = 1;
            readonly y = undefined;
        }
        expect(encodeValue({ a: undefined, b: null, c: true })).toEqual({ b: null, c: true });
        expect(encodeValue(new Point())).toEqual({ x: 1 });
        expect(encodeValue(Object.create(null) as object)).toEqual({});
    });

    test("encodes a Date only with a temporal zone", () => {
        const instant = new Date(Date.UTC(2024, 0, 1, 10, 0));
        expect(() => encodeValue({ at: instant })).toThrow(/field 'at' is a Date/);
        expect(encodeValue({ at: instant }, "", { temporalZone: "UTC" })).toEqual({
            at: "#datetime(2024-01-01T10:00)",
        });
    });
});

describe("encodeDocument", () => {
    test("needs a plain object", () => {
        expect(encodeDocument({ _id: "a" })).toEqual({ _id: "a" });
        for (const refused of [null, [], "text", 1, GeoPoint.of(0, 0)]) {
            expect(() => encodeDocument(refused)).toThrow(/a document must be a plain object/);
        }
    });
});

describe("decodeValue", () => {
    test("decodes tagged strings at any depth and leaves other strings alone", () => {
        const decoded = decodeValue({
            a: [{ at: "#datetime(2024-01-01T10:00)" }],
            t: "#time(09:30)",
            g: "#geo(1.0, 2.0)",
            v: "#vector(1,2)",
            unknown: "#unknown(x)",
            short: "#ab(x)",
            plain: "hello",
            n: 1,
            nothing: null,
        });
        expect(decoded).toEqual({
            a: [{ at: LocalDateTime.of(2024, 1, 1, 10, 0) }],
            t: LocalTime.of(9, 30),
            g: GeoPoint.of(1, 2),
            v: Vector.of(1, 2),
            unknown: "#unknown(x)",
            short: "#ab(x)",
            plain: "hello",
            n: 1,
            nothing: null,
        });
    });

    test("fails on a malformed value of a known type", () => {
        expect(() => decodeValue("#geo(1)")).toThrow(MappingError);
        expect(() => decodeValue("#geo(1,2,3)")).toThrow(MappingError);
        expect(() => decodeValue("#vector(1,x)")).toThrow(MappingError);
        expect(() => decodeValue("#vector(1,)")).toThrow(MappingError);
        expect(() => decodeValue("#datetime(nope)")).toThrow(/malformed datetime/);
    });
});
