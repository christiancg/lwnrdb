import { describe, expect, test } from "vitest";
import { LocalDateTime } from "../../../src/index.js";

describe("LocalDateTime", () => {
    test("toString omits zero seconds the way Java does", () => {
        expect(LocalDateTime.of(2024, 1, 1, 10, 0).toString()).toBe("2024-01-01T10:00");
        expect(LocalDateTime.of(2024, 1, 1, 10, 0, 30).toString()).toBe("2024-01-01T10:00:30");
        expect(LocalDateTime.of(2024, 1, 1).toString()).toBe("2024-01-01T00:00");
    });

    test("prints fractions in 3, 6 or 9 digits", () => {
        expect(LocalDateTime.of(2024, 1, 1, 0, 0, 0, 500_000_000).toString()).toBe("2024-01-01T00:00:00.500");
        expect(LocalDateTime.of(2024, 1, 1, 0, 0, 0, 123_456_000).toString()).toBe("2024-01-01T00:00:00.123456");
        expect(LocalDateTime.of(2024, 1, 1, 0, 0, 0, 1).toString()).toBe("2024-01-01T00:00:00.000000001");
    });

    test("prints years outside four digits with a sign", () => {
        expect(LocalDateTime.of(12_345, 1, 1).toString()).toBe("+12345-01-01T00:00");
        expect(LocalDateTime.of(-5, 1, 1).toString()).toBe("-0005-01-01T00:00");
        expect(LocalDateTime.of(-12_345, 1, 1).toString()).toBe("-12345-01-01T00:00");
    });

    test("parse round-trips toString", () => {
        for (const text of [
            "2024-01-01T10:00",
            "2024-02-29T23:59:59",
            "2024-01-01T00:00:00.500",
            "2024-01-01T00:00:00.123456",
            "+12345-01-01T00:00",
        ]) {
            expect(LocalDateTime.parse(text).toString()).toBe(text);
        }
        expect(LocalDateTime.parse("2024-01-01T10:00:00.5").nanosecond).toBe(500_000_000);
    });

    test("rejects impossible dates and malformed text", () => {
        expect(() => LocalDateTime.of(2024, 2, 30)).toThrow(RangeError);
        expect(() => LocalDateTime.of(2023, 2, 29)).toThrow(RangeError);
        expect(() => LocalDateTime.of(2024, 4, 31)).toThrow(RangeError);
        expect(() => LocalDateTime.of(2024, 13, 1)).toThrow(RangeError);
        expect(() => LocalDateTime.of(2024, 1, 1, 24)).toThrow(RangeError);
        expect(() => LocalDateTime.of(2024, 1, 1.5)).toThrow(RangeError);
        expect(() => LocalDateTime.parse("2024-01-01")).toThrow(RangeError);
        expect(() => LocalDateTime.parse("2024-01-01T10")).toThrow(RangeError);
        expect(LocalDateTime.of(2000, 2, 29).day).toBe(29);
        expect(() => LocalDateTime.of(1900, 2, 29)).toThrow(RangeError);
    });

    test("compares and equals by every field", () => {
        const earlier = LocalDateTime.of(2024, 1, 1, 10, 0);
        const later = LocalDateTime.of(2024, 1, 1, 10, 0, 0, 1);
        expect(earlier.compareTo(later)).toBe(-1);
        expect(later.compareTo(earlier)).toBe(1);
        expect(earlier.equals(LocalDateTime.parse("2024-01-01T10:00"))).toBe(true);
        expect(earlier.equals("2024-01-01T10:00")).toBe(false);
        expect(JSON.stringify({ at: earlier })).toBe('{"at":"2024-01-01T10:00"}');
    });

    test("fromDate needs a valid zone and a valid date", () => {
        expect(() => LocalDateTime.fromDate(new Date(0), "Not/AZone")).toThrow(RangeError);
        expect(() => LocalDateTime.fromDate(new Date(Number.NaN), "UTC")).toThrow(RangeError);
    });

    test("fromDate and toDate convert through a zone", () => {
        const instant = new Date(Date.UTC(2024, 6, 1, 8, 30, 15, 250));
        const madrid = LocalDateTime.fromDate(instant, "Europe/Madrid");
        expect(madrid.toString()).toBe("2024-07-01T10:30:15.250");
        expect(madrid.toDate("Europe/Madrid").getTime()).toBe(instant.getTime());
        expect(LocalDateTime.fromDate(instant, "UTC").toString()).toBe("2024-07-01T08:30:15.250");
    });

    test("round-trips across a daylight-saving change", () => {
        for (const instant of [
            Date.UTC(2024, 2, 31, 0, 30),
            Date.UTC(2024, 2, 31, 1, 30),
            Date.UTC(2024, 9, 27, 2, 30),
        ]) {
            const local = LocalDateTime.fromDate(new Date(instant), "Europe/Madrid");
            expect(local.toDate("Europe/Madrid").getTime()).toBe(instant);
        }
    });

    test("a wall-clock time in a gap moves forward", () => {
        const gap = LocalDateTime.of(2024, 3, 31, 2, 30);
        const instant = gap.toDate("Europe/Madrid");
        expect(LocalDateTime.fromDate(instant, "Europe/Madrid").hour).toBe(3);
    });

    test("fromDate handles instants before 1970 and before year 1", () => {
        const old = new Date(Date.UTC(1969, 11, 31, 23, 59, 59, 999));
        expect(LocalDateTime.fromDate(old, "UTC").toString()).toBe("1969-12-31T23:59:59.999");
        const ancient = new Date(Date.UTC(2000, 0, 1));
        ancient.setUTCFullYear(-1);
        expect(LocalDateTime.fromDate(ancient, "UTC").year).toBe(-1);
        expect(LocalDateTime.fromDate(ancient, "UTC").toDate("UTC").getTime()).toBe(ancient.getTime());
    });
});
