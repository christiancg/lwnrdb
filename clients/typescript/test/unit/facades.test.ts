import { describe, expect, test } from "vitest";
import { Admin, Database, field, LocalTime, NotFoundError, TriggerEvent, TriggerRunDecision } from "../../src/index.js";
import type { JsonObject } from "../../src/internal/values-codec.js";
import { RecordingChannel, recordingContext } from "../support/recording-channel.js";

function setup(reply: (request: JsonObject) => JsonObject) {
    const channel = new RecordingChannel();
    channel.replies = (request) => ({ type: request.type ?? null, status: "OK", ...reply(request) });
    const context = recordingContext(channel);
    const shop = new Database(context, "shop");
    return { channel, shop, orders: shop.collection("orders"), admin: new Admin(context) };
}

describe("collection replies", () => {
    test("save and saveAll report what the server did", async () => {
        const { orders } = setup((request) =>
            request.type === "SAVE" ? { _id: "o-9", inserted: true } : { inserted: ["a"], updated: ["b"] },
        );
        expect(await orders.save({ total: 1 })).toEqual({ id: "o-9", inserted: true });
        expect(await orders.saveAll([{ _id: "a" }, { _id: "b" }])).toEqual({ inserted: ["a"], updated: ["b"] });
        expect([orders.databaseName, orders.name]).toEqual(["shop", "orders"]);
    });

    test("deleteWhere sends the filter as the condition and answers the deleted count", async () => {
        const { orders, channel } = setup(() => ({ deletedCount: 3 }));
        expect(await orders.deleteWhere(field("status").eq("archived"))).toBe(3);
        expect(channel.requests[0]).toMatchObject({
            type: "DELETE",
            condition: { fieldOperatorType: "EQUALS", field: "status", value: "archived" },
        });
        const { orders: silent } = setup(() => ({}));
        expect(await silent.deleteWhere(field("status").eq("archived"))).toBe(0);
    });

    test("updateWhere sends the filter as the condition and the patch as the object", async () => {
        const { orders, channel } = setup(() => ({ updatedCount: 2 }));
        expect(await orders.updateWhere(field("status").eq("pending"), { status: "expired" })).toBe(2);
        expect(channel.requests[0]).toEqual({
            type: "SAVE",
            databaseName: "shop",
            collectionName: "orders",
            condition: { fieldOperatorType: "EQUALS", field: "status", value: "pending" },
            object: { status: "expired" },
        });
        const { orders: silent } = setup(() => ({}));
        expect(await silent.updateWhere(field("status").eq("pending"), { status: "expired" })).toBe(0);
    });

    test("an invalid _id is refused before it is sent", async () => {
        const { orders, channel } = setup(() => ({}));
        await expect(orders.save({ _id: "a b" })).rejects.toThrow(/invalid _id/);
        await expect(orders.saveAll([{ _id: "" }])).rejects.toThrow(/invalid _id/);
        await expect(orders.findById("no spaces")).rejects.toThrow(/invalid _id/);
        await expect(orders.delete("")).rejects.toThrow(/invalid _id/);
        expect(channel.requests).toEqual([]);
    });

    test("findById decodes custom values and answers undefined only for 404-2", async () => {
        const channel = new RecordingChannel();
        const orders = new Database(recordingContext(channel), "shop").collection("orders");
        channel.replies = () => ({ status: "OK", object: { _id: "o-1", opensAt: "#time(09:30)" } });
        expect(await orders.findById("o-1")).toEqual({ _id: "o-1", opensAt: LocalTime.of(9, 30) });
        channel.replies = () => ({ status: "OK" });
        expect(await orders.findById("o-1")).toBeUndefined();
        channel.replies = () => ({ status: "NOT_FOUND", errorCode: "404-2" });
        expect(await orders.findById("o-1")).toBeUndefined();
        channel.replies = () => ({ status: "NOT_FOUND", errorCode: "404-11" });
        await expect(orders.findById("o-1")).rejects.toBeInstanceOf(NotFoundError);
    });

    test("a codec maps both ways", async () => {
        const channel = new RecordingChannel();
        channel.replies = () => ({ status: "OK", object: { _id: "o-1", cents: 250 } });
        const orders = new Database(recordingContext(channel), "shop").collection("orders", {
            encode: (order: { id: string; price: number }) => ({ _id: order.id, cents: order.price * 100 }),
            decode: (document) => ({ id: document._id as string, price: Number(document.cents) / 100 }),
        });
        await orders.save({ id: "o-1", price: 2.5 });
        expect(channel.last().object).toEqual({ _id: "o-1", cents: 250 });
        expect(await orders.findById("o-1")).toEqual({ id: "o-1", price: 2.5 });
    });

    test("a Date is stored through the temporal zone", async () => {
        const channel = new RecordingChannel();
        const orders = new Database(recordingContext(channel, "UTC"), "shop").collection<{ at: Date }>("orders");
        await orders.save({ at: new Date(Date.UTC(2024, 0, 1, 10, 0)) });
        expect(channel.last().object).toEqual({ at: "#datetime(2024-01-01T10:00)" });
    });

    test("indexes report what they rebuilt", async () => {
        const { orders } = setup(() => ({ rebuiltFields: ["email"], primaryKeyEntries: 10 }));
        expect(await orders.indexes().reindex()).toEqual({
            rebuiltFields: ["email"],
            primaryKeyEntries: 10,
            unversionedEntries: undefined,
        });
        expect((await orders.indexes().reindexPrimaryKey()).primaryKeyEntries).toBe(10);
    });

    test("schema returns warnings, the schema, or undefined when there is none", async () => {
        const channel = new RecordingChannel();
        const orders = new Database(recordingContext(channel), "shop").collection("orders");
        channel.replies = () => ({ status: "OK", warnings: ["w"], schema: { type: "object" } });
        expect(await orders.schema().save({ type: "object" })).toEqual(["w"]);
        expect(await orders.schema().get()).toEqual({ type: "object" });
        channel.replies = () => ({ status: "NOT_FOUND", errorCode: "404-8" });
        expect(await orders.schema().get()).toBeUndefined();
        channel.replies = () => ({ status: "FORBIDDEN", errorCode: "403-1" });
        await expect(orders.schema().get()).rejects.toThrow(/403-1/);
    });
});

describe("scripting replies", () => {
    test("triggers decode their descriptors and test results", async () => {
        const { orders } = setup((request) => {
            switch (request.type) {
                case "SAVE_TRIGGER":
                    return { version: 3, definer: "alice" };
                case "LIST_TRIGGERS":
                    return { triggers: [{ name: "audit", events: ["CREATED"], procedureName: "p", version: 2 }] };
                default:
                    return { decision: "accept", document: { _id: "o-1" }, logs: ["hi"], logsTruncated: true };
            }
        });
        expect(
            await orders.triggers().save({ name: "audit", procedureName: "p", events: [TriggerEvent.CREATED] }),
        ).toEqual({ version: 3, definer: "alice" });
        expect(await orders.triggers().list()).toEqual([
            {
                name: "audit",
                collection: undefined,
                events: ["CREATED"],
                procedureName: "p",
                timing: "after",
                mode: "document",
                enabled: true,
                version: 2,
                definer: undefined,
                raw: { name: "audit", events: ["CREATED"], procedureName: "p", version: 2 },
            },
        ]);
        expect(await orders.triggers().test("audit", TriggerEvent.CREATED, { _id: "o-1" })).toEqual({
            decision: "accept",
            document: { _id: "o-1" },
            logs: ["hi"],
            logsTruncated: true,
        });
        await expect(orders.triggers().save({ name: "a", procedureName: "p", events: [] })).rejects.toThrow(
            /at least one event/,
        );
    });

    test("defaults fill in for a sparse trigger reply", async () => {
        const { orders } = setup(() => ({}));
        expect(await orders.triggers().save({ name: "a", procedureName: "p", events: [TriggerEvent.DELETED] })).toEqual(
            {
                version: 0,
                definer: "",
            },
        );
        expect(await orders.triggers().test("a", TriggerEvent.DELETED, {})).toEqual({
            decision: "",
            document: undefined,
            logs: [],
            logsTruncated: false,
        });
    });

    test("procedures and schedules decode versions, lists and results", async () => {
        const { shop } = setup((request) => {
            switch (request.type) {
                case "LIST_PROCEDURES":
                    return { procedures: [{ name: "p", version: 1, enabled: false, source: "x" }] };
                case "LIST_SCHEDULES":
                    return { schedules: [{ name: "s", procedureName: "p", cron: "* * * * *", nextRunAt: 5 }] };
                case "CALL_PROCEDURE":
                case "RUN_SCRIPT":
                    return { result: { at: "#time(09:30)" }, logs: ["l"], runId: "r1" };
                case "LIST_TRIGGERS":
                    return { triggers: [{ name: "t", collection: "orders" }] };
                case "LIST_COLLECTIONS":
                    return { collections: ["orders"] };
                case "GET_DATABASE_STATS":
                    return { stats: { size: 1 } };
                default:
                    return { version: 4 };
            }
        });
        expect(await shop.procedures().save({ name: "p", script: "x" })).toBe(4);
        expect((await shop.procedures().list())[0]).toMatchObject({ name: "p", enabled: false, source: "x" });
        expect(await shop.procedures().call("p")).toEqual({
            value: { at: LocalTime.of(9, 30) },
            logs: ["l"],
            logsTruncated: false,
            runId: "r1",
        });
        expect(await shop.schedules().save({ name: "s", procedureName: "p", intervalMs: 5 })).toBe(4);
        expect((await shop.schedules().list())[0]).toMatchObject({
            cron: "* * * * *",
            nextRunAt: 5,
            intervalMs: undefined,
        });
        expect((await shop.runScript("x")).runId).toBe("r1");
        expect((await shop.listTriggers())[0]?.collection).toBe("orders");
        expect(await shop.listCollections()).toEqual(["orders"]);
        expect(await shop.stats()).toEqual({ size: 1 });
        await shop.procedures().delete("p");
        await shop.schedules().delete("s");
        await shop.create();
        await shop.drop();
    });

    test("a script with no result has an undefined value, and stats default to empty", async () => {
        const { shop } = setup(() => ({}));
        expect((await shop.runScript("x")).value).toBeUndefined();
        expect(await shop.stats()).toEqual({});
        expect(await shop.procedures().save({ name: "p", script: "x" })).toBe(0);
        expect(await shop.schedules().save({ name: "s", procedureName: "p", cron: "x" })).toBe(0);
    });

    test("a schedule needs exactly one of cron and intervalMs", async () => {
        const { shop } = setup(() => ({}));
        await expect(shop.schedules().save({ name: "s", procedureName: "p" })).rejects.toThrow(/exactly one/);
        await expect(
            shop.schedules().save({ name: "s", procedureName: "p", cron: "x", intervalMs: 1 }),
        ).rejects.toThrow(/exactly one/);
    });
});

describe("admin replies", () => {
    test("users decode and an empty listing is a 404 on the wire", async () => {
        const channel = new RecordingChannel();
        const admin = new Admin(recordingContext(channel));
        channel.replies = () => ({
            status: "OK",
            users: [{ _id: "bob", admin: true, databasePermissions: { shop: "READ" }, ownedDatabases: ["shop"] }],
        });
        expect(await admin.users().list()).toEqual([
            {
                username: "bob",
                admin: true,
                globalPermissions: [],
                databasePermissions: { shop: "READ" },
                collectionPermissions: {},
                scriptPermissions: {},
                ownedDatabases: ["shop"],
                raw: { _id: "bob", admin: true, databasePermissions: { shop: "READ" }, ownedDatabases: ["shop"] },
            },
        ]);
        channel.replies = () => ({ status: "NOT_FOUND", errorCode: "404-5" });
        expect(await admin.users().list()).toEqual([]);
        channel.replies = () => ({ status: "NOT_FOUND", errorCode: "404-3" });
        expect(await admin.users().list()).toEqual([]);
        channel.replies = () => ({ status: "NOT_FOUND", errorCode: "404-1" });
        await expect(admin.users().list()).rejects.toBeInstanceOf(NotFoundError);
        channel.replies = () => ({ status: "OK" });
        await admin.users().delete("bob");
        await admin.users().changePermissions("bob", {});
        expect(channel.last()).toEqual({
            type: "CHANGE_PERMISSIONS",
            username: "bob",
            admin: false,
            globalPermissions: [],
            databasePermissions: {},
            collectionPermissions: {},
            scriptPermissions: {},
        });
    });

    test("transactions, scripts and trigger runs decode their rows", async () => {
        const { admin } = setup((request) => {
            switch (request.type) {
                case "LIST_TRANSACTIONS":
                    return { transactions: [{ dtxId: "d", participants: ["n1"], ageMs: 4 }] };
                case "LIST_SCRIPTS":
                    return { scripts: [{ runId: "r", kind: "RUN_SCRIPT", ageMs: 2, node: "n1" }] };
                case "CANCEL_SCRIPT":
                    return { cancelled: true };
                case "LIST_TRIGGER_RUNS":
                    return { runs: [{ runId: "t", status: "DEAD", attempts: 3, lastError: "boom" }] };
                default:
                    return { resolved: true };
            }
        });
        expect((await admin.transactions().list())[0]).toMatchObject({
            dtxId: "d",
            coordinator: undefined,
            coordinatorReachable: false,
            participants: ["n1"],
            ageMs: 4,
        });
        expect((await admin.scripts().list())[0]).toMatchObject({ runId: "r", node: "n1", database: undefined });
        expect(await admin.scripts().cancel("r")).toBe(true);
        expect((await admin.triggerRuns().list())[0]).toMatchObject({ status: "DEAD", attempts: 3, lastError: "boom" });
        expect(await admin.triggerRuns().resolve("t", TriggerRunDecision.REPLAY)).toBe(true);
    });

    test("missing flags read as false", async () => {
        const { admin } = setup(() => ({}));
        expect(await admin.scripts().cancel("r")).toBe(false);
        expect(await admin.triggerRuns().resolve("t", TriggerRunDecision.DISCARD)).toBe(false);
        expect(await admin.transactions().list()).toEqual([]);
    });
});
