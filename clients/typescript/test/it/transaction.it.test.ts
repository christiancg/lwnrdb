import { beforeAll, describe, expect, test } from "vitest";
import { type Collection, type Document, field, LwnrdbClient, type Stored } from "../../src/index.js";
import { serverIt } from "./server-it.js";

const it = serverIt({ transactionIdleTimeoutMs: "4000" });
let accounts: Collection<Document, Stored<Document>>;

beforeAll(async () => {
    const database = it.client.database("tx_it");
    await database.create();
    await database.collection("accounts").create();
    await database.collection("ledger").create();
    accounts = database.collection("accounts");
});

describe("transactions", () => {
    test("a commit is visible to others and reads inside see their own writes", async () => {
        const seen = await it.client.transaction(async (transaction) => {
            const inside = transaction.collection("tx_it", "accounts");
            await inside.save({ _id: "a1", balance: 100 });
            await inside.saveAll([{ _id: "a2", balance: 50 }]);
            expect(await accounts.findById("a1", { dirtyRead: true })).toBeUndefined();
            expect(await inside.query().filter(field("_id").eq("a2")).count()).toBe(1);
            return (await inside.findById("a1"))?.balance;
        });
        expect(seen).toBe(100);
        expect(await accounts.findById("a1")).toBeDefined();
        expect(await accounts.findById("a2")).toBeDefined();
    });

    test("a rollback and a failing body leave nothing", async () => {
        {
            await using transaction = await it.client.beginTransaction();
            await transaction.collection("tx_it", "accounts").save({ _id: "r1" });
            await transaction.rollback();
        }
        expect(await accounts.findById("r1")).toBeUndefined();
        await expect(
            it.client.transaction(async (transaction) => {
                await transaction.collection("tx_it", "accounts").save({ _id: "r2" });
                throw new Error("abort");
            }),
        ).rejects.toThrow("abort");
        expect(await accounts.findById("r2")).toBeUndefined();
        {
            await using disposed = await it.client.beginTransaction();
            await disposed.collection("tx_it", "accounts").save({ _id: "r3" });
        }
        expect(await accounts.findById("r3")).toBeUndefined();
    });

    test("an idle transaction is rolled back by the server", async () => {
        const transaction = await it.client.beginTransaction();
        const inside = transaction.collection("tx_it", "accounts");
        await inside.save({ _id: "idle" });
        await new Promise((resolve) => setTimeout(resolve, 6000));
        await expect(inside.save({ _id: "idle2" })).rejects.toMatchObject({ rawCode: "409-16" });
        await transaction.close().catch(() => undefined);
        expect(await accounts.findById("idle")).toBeUndefined();
    });

    test("transactions on different collections run concurrently", async () => {
        const other = await LwnrdbClient.connect(it.settings());
        try {
            await Promise.all([
                it.client.transaction((transaction) => transaction.collection("tx_it", "accounts").save({ _id: "c1" })),
                other.transaction((transaction) => transaction.collection("tx_it", "ledger").save({ _id: "l1" })),
            ]);
        } finally {
            await other.close();
        }
        expect(await accounts.findById("c1")).toBeDefined();
    });

    test("a second begin uses another connection and is independent", async () => {
        const first = await it.client.beginTransaction();
        const second = await it.client.beginTransaction();
        await first.collection("tx_it", "accounts").save({ _id: "s1" });
        await second.rollback();
        await first.commit();
        expect(await accounts.findById("s1")).toBeDefined();
    });
});
