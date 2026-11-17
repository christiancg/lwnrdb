import { describe, expect, test } from "vitest";
import { LocalTime } from "../../../src/index.js";

describe("LocalTime", () => {
    test("formats like Java", () => {
        expect(LocalTime.of(9, 30).toString()).toBe("09:30");
        expect(LocalTime.of(9, 30, 15).toString()).toBe("09:30:15");
        expect(LocalTime.of(9, 30, 0, 1_000).toString()).toBe("09:30:00.000001");
        expect(JSON.stringify(LocalTime.of(0, 0))).toBe('"00:00"');
    });

    test("parses and compares", () => {
        expect(LocalTime.parse("23:59:59.999999999").nanosecond).toBe(999_999_999);
        expect(LocalTime.parse("09:30").equals(LocalTime.of(9, 30))).toBe(true);
        expect(LocalTime.of(9, 30).compareTo(LocalTime.of(9, 31))).toBe(-1);
        expect(LocalTime.of(9, 30).compareTo(LocalTime.of(9, 30))).toBe(0);
        expect(LocalTime.of(9, 30).equals(null)).toBe(false);
    });

    test("rejects out-of-range fields and malformed text", () => {
        expect(() => LocalTime.of(-1, 0)).toThrow(RangeError);
        expect(() => LocalTime.of(0, 60)).toThrow(RangeError);
        expect(() => LocalTime.of(0, 0, 60)).toThrow(RangeError);
        expect(() => LocalTime.of(0, 0, 0, 1e9)).toThrow(RangeError);
        expect(() => LocalTime.parse("9:30")).toThrow(RangeError);
        expect(() => LocalTime.parse("25:00")).toThrow(RangeError);
    });
});
