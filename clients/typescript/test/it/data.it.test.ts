import { beforeAll, describe, expect, test } from "vitest";
import {
    and,
    type Collection,
    type Document,
    field,
    LwnrdbError,
    NotFoundError,
    type Stored,
} from "../../src/index.js";
import { serverIt } from "./server-it.js";

const it = serverIt();
let orders: Collection<Document, Stored<Document>>;

beforeAll(async () => {
    const database = it.client.database("data_it");
    await database.create();
    orders = database.collection("orders");
    await orders.create();
});

describe("documents", () => {
    test("save upserts and findById reads back", async () => {
        const first = await orders.save({ _id: "o-1", status: "new", total: 10 });
        expect(first).toEqual({ id: "o-1", inserted: true });
        expect((await orders.save({ _id: "o-1", status: "paid", total: 12.5 })).inserted).toBe(false);
        expect(await orders.findById("o-1")).toEqual({ _id: "o-1", status: "paid", total: 12.5 });
        expect((await orders.findById("o-1", { dirtyRead: true }))?.status).toBe("paid");
    });

    test("a save without an id gets one, and ids are case-sensitive", async () => {
        const generated = await orders.save({ status: "new" });
        expect(await orders.findById(generated.id ?? "")).toBeDefined();
        await orders.save({ _id: "Case", v: 1 });
        expect(await orders.findById("case")).toBeUndefined();
        expect(await orders.findById("Case")).toBeDefined();
    });

    test("insert adds a new document and refuses an _id that is already stored", async () => {
        expect(await orders.insert({ _id: "i-1", status: "new" })).toEqual({ id: "i-1", inserted: true });
        const refused = orders.insert({ _id: "i-1", status: "replaced" });
        await expect(refused).rejects.toMatchObject({ rawCode: "409-18" });
        await expect(refused).rejects.toBeInstanceOf(LwnrdbError);
        expect(await orders.findById("i-1")).toEqual({ _id: "i-1", status: "new" });
        const generated = await orders.insert({ status: "fresh" });
        expect(generated.inserted).toBe(true);
        expect(await orders.findById(generated.id ?? "")).toEqual({ _id: generated.id, status: "fresh" });
    });

    test("saveAll reports inserts and updates and refuses duplicates", async () => {
        await orders.save({ _id: "b-1", v: 1 });
        expect(
            await orders.saveAll([
                { _id: "b-1", v: 2 },
                { _id: "b-2", v: 3 },
            ]),
        ).toEqual({
            inserted: ["b-2"],
            updated: ["b-1"],
        });
        const refused = orders.saveAll([{ _id: "d-1" }, { _id: "d-1" }]);
        await expect(refused).rejects.toMatchObject({ rawCode: "400-3" });
        await expect(refused).rejects.toBeInstanceOf(LwnrdbError);
    });

    test("delete removes the document and a missing one is reported", async () => {
        await orders.save({ _id: "gone", v: 1 });
        await orders.delete("gone");
        expect(await orders.findById("gone")).toBeUndefined();
        await expect(orders.delete("gone")).rejects.toBeInstanceOf(NotFoundError);
    });

    test("deleteWhere removes every match and reports the count", async () => {
        await orders.saveAll([
            { _id: "dw-1", status: "archived", total: 10 },
            { _id: "dw-2", status: "archived", total: 500 },
            { _id: "dw-3", status: "active", total: 10 },
        ]);
        expect(await orders.deleteWhere(and(field("status").eq("archived"), field("total").lt(100)))).toBe(1);
        expect(await orders.findById("dw-1")).toBeUndefined();
        expect(await orders.findById("dw-2")).toBeDefined();
        expect(await orders.findById("dw-3")).toBeDefined();
        expect(await orders.deleteWhere(field("status").eq("never"))).toBe(0);
    });

    test("updateWhere merges the patch into every match and keeps the other fields", async () => {
        await orders.saveAll([
            { _id: "uw-1", status: "pending", total: 10, note: "keep" },
            { _id: "uw-2", status: "pending", total: 500 },
            { _id: "uw-3", status: "active", total: 10 },
        ]);
        const pendingAndSmall = and(field("status").eq("pending"), field("total").lt(100));
        expect(await orders.updateWhere(pendingAndSmall, { status: "expired", expiredBy: "sweeper" })).toBe(1);
        expect(await orders.findById("uw-1")).toEqual({
            _id: "uw-1",
            status: "expired",
            total: 10,
            note: "keep",
            expiredBy: "sweeper",
        });
        expect(await orders.findById("uw-2")).toEqual({ _id: "uw-2", status: "pending", total: 500 });
        expect(await orders.findById("uw-3")).toEqual({ _id: "uw-3", status: "active", total: 10 });
        expect(await orders.updateWhere(field("status").eq("never"), { status: "x" })).toBe(0);
        await expect(orders.updateWhere(field("status").eq("active"), { _id: "uw-9" })).rejects.toMatchObject({
            rawCode: "400-1",
        });
        expect(await orders.findById("uw-9")).toBeUndefined();
    });

    test("nested values round-trip", async () => {
        const nested = {
            _id: "n-1",
            customer: { name: "Ana", tags: ["vip", "new"] },
            flags: [true, false],
            none: null,
        };
        await orders.save(nested);
        expect(await orders.findById("n-1")).toEqual(nested);
    });

    test("a write to a missing collection is refused with 404-11", async () => {
        await expect(it.client.database("data_it").collection("missing").save({ _id: "x" })).rejects.toMatchObject({
            rawCode: "404-11",
        });
    });
});
