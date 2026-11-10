import { describe, expect, test } from "vitest";
import { GeoPoint } from "../../../src/index.js";

describe("GeoPoint", () => {
    test("holds valid coordinates", () => {
        const point = GeoPoint.of(-90, 180);
        expect([point.lat, point.lng]).toEqual([-90, 180]);
        expect(point.equals(GeoPoint.of(-90, 180))).toBe(true);
        expect(point.equals(GeoPoint.of(-90, 179))).toBe(false);
        expect(point.equals({ lat: -90, lng: 180 })).toBe(false);
        expect(point.toString()).toBe("GeoPoint(-90, 180)");
        expect(Object.isFrozen(point)).toBe(true);
    });

    test("rejects out-of-range and non-finite coordinates", () => {
        expect(() => GeoPoint.of(90.1, 0)).toThrow(RangeError);
        expect(() => GeoPoint.of(0, -180.1)).toThrow(RangeError);
        expect(() => GeoPoint.of(Number.NaN, 0)).toThrow(RangeError);
        expect(() => GeoPoint.of(0, Number.POSITIVE_INFINITY)).toThrow(RangeError);
    });
});
