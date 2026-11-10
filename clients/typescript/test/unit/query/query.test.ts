import { describe, expect, test } from "vitest";
import {
    and,
    CastType,
    CustomType,
    Database,
    distance,
    field,
    GeoDistanceComparator,
    GeoPoint,
    nearest,
    type Order,
    script,
    Vector,
    within,
} from "../../../src/index.js";
import { remove, set } from "../../../src/query/map-ops.js";
import * as MidOps from "../../../src/query/mid-ops.js";
import { RecordingChannel, recordingContext } from "../../support/recording-channel.js";

function orders(channel = new RecordingChannel()) {
    return new Database(recordingContext(channel), "shop").collection("orders");
}

describe("Query", () => {
    test("is immutable: every step returns a new query", () => {
        const base = orders().query();
        const filtered = base.filter(field("a").eq(1));
        const dirty = filtered.dirtyRead();
        expect(base.steps).toEqual([]);
        expect(filtered.steps).toHaveLength(1);
        expect(filtered.isDirtyRead).toBe(false);
        expect(dirty.isDirtyRead).toBe(true);
        expect(dirty.limit(1).isDirtyRead).toBe(true);
        expect(Object.isFrozen(filtered.steps)).toBe(true);
    });

    test("validates its arguments", () => {
        const query = orders().query();
        expect(() => query.limit(0)).toThrow(RangeError);
        expect(() => query.limit(1.5)).toThrow(RangeError);
        expect(() => query.skip(-1)).toThrow(RangeError);
        expect(() => query.map()).toThrow(/at least one operation/);
        expect(() => query.map({} as never)).toThrow(/MapOperations/);
        expect(() => query.sort("a", "UP" as Order)).toThrow(/Order.ASC or Order.DESC/);
        expect(() => query.filter(undefined as never)).toThrow(TypeError);
        expect(() => query.groupBy(1 as never)).toThrow(/field must be a string/);
        expect(query.sort("a").steps[0]).toEqual({ type: "SORT", field: "a", ascending: true });
        expect(query.skip(0).steps[0]).toEqual({ type: "SKIP", skip: 0 });
    });

    test("first adds LIMIT 1 and decodes the row, count reads count", async () => {
        const channel = new RecordingChannel();
        const collection = orders(channel);
        channel.replies = (request) => ({ status: "OK", type: request.type ?? null, results: [{ _id: "x" }] });
        expect(await collection.query().first()).toEqual({ _id: "x" });
        expect(channel.last().aggregationSteps).toEqual([{ type: "LIMIT", limit: 1 }]);
        channel.replies = () => ({ status: "OK", results: [{ count: 7 }] });
        expect(await collection.query().count()).toBe(7);
        channel.replies = () => ({ status: "OK", results: [{ count: "7" }] });
        expect(await collection.query().count()).toBe(0);
    });

    test("an empty result is a 404-3 on the wire", async () => {
        const channel = new RecordingChannel();
        const collection = orders(channel);
        channel.replies = () => ({ status: "NOT_FOUND", errorCode: "404-3", message: "No results" });
        expect(await collection.query().toArray()).toEqual([]);
        expect(await collection.query().first()).toBeUndefined();
        expect(await collection.query().count()).toBe(0);
        channel.replies = () => ({ status: "NOT_FOUND", errorCode: "404-11", message: "no collection" });
        await expect(collection.query().toArray()).rejects.toThrow(/404-11/);
    });

    test("analyze returns rows and the analysis", async () => {
        const channel = new RecordingChannel();
        channel.replies = () => ({ status: "OK", results: [{ a: 1 }], analyzeResult: { scanned: 3 } });
        expect(await orders(channel).query().analyze()).toEqual({ results: [{ a: 1 }], analysis: { scanned: 3 } });
        channel.replies = () => ({ status: "OK" });
        expect(await orders(channel).query().analyze()).toEqual({ results: [], analysis: {} });
    });

    test("reduce with a null seed sends a null initialValue", async () => {
        const channel = new RecordingChannel();
        await orders(channel).query().reduce("s", null).toArray();
        expect(channel.last().aggregationSteps).toEqual([{ type: "REDUCE", script: "s", initialValue: null }]);
        await orders(channel).query().reduce("s", undefined).toArray();
        expect(channel.last().aggregationSteps).toEqual([{ type: "REDUCE", script: "s", initialValue: null }]);
    });
});

describe("filters and operations", () => {
    test("refuse what the server would", () => {
        expect(() => and()).toThrow(/AND needs at least one operand/);
        expect(() => distance("f", GeoPoint.of(0, 0), GeoDistanceComparator.EQUALS, -1)).toThrow(RangeError);
        expect(() => distance("f", { lat: 0, lng: 0 } as GeoPoint, GeoDistanceComparator.EQUALS, 1)).toThrow(TypeError);
        expect(() => within("f", GeoPoint.of(0, 0), GeoPoint.of(1, 1))).toThrow(/at least 3 points/);
        expect(() => nearest("f", Vector.of(1), 0)).toThrow(RangeError);
        expect(() => nearest("f", [1] as unknown as Vector, 1)).toThrow(TypeError);
        expect(() => field("a").eq(undefined)).toThrow(/use null/);
        expect(() => field(5 as never)).toThrow(TypeError);
        expect(() => script(undefined as never)).toThrow(TypeError);
        expect(() => MidOps.sum()).toThrow(/SUM needs at least one operand/);
        expect(() => MidOps.cast("f", CastType.JSON_CUSTOM)).toThrow(/CustomType/);
        expect(() => MidOps.cast("f", "planet" as CastType)).toThrow(TypeError);
        expect(() => set("", MidOps.abs("a")).when(field("a").eq(1))).not.toThrow();
        expect(() => remove(1 as never)).toThrow(TypeError);
    });

    test("expose what they hold", () => {
        expect(nearest("f", Vector.of(1), 2).exact().isExact).toBe(true);
        expect(MidOps.cast("f", CustomType.GEO)).toEqual({
            kind: "cast",
            field: "f",
            type: "JSON_CUSTOM",
            customType: "geo",
        });
        expect(remove("a").removes).toBe(true);
        expect(set("a", MidOps.size("b")).removes).toBe(false);
        expect(field("a").field).toBe("a");
    });

    test("a filter value is encoded with the query, so a bad value fails the call", async () => {
        const collection = orders();
        await expect(collection.query().filter(field("n").eq(Number.NaN)).toArray()).rejects.toThrow(
            /field 'n' is not a finite number/,
        );
        await expect(collection.query().filter(field("t").eq("#geo(1,2)")).toArray()).rejects.toThrow(
            /looks like a custom value/,
        );
    });
});
