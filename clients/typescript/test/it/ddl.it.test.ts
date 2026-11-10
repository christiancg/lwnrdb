import { describe, expect, test } from "vitest";
import { ConflictError, field, LwnrdbError } from "../../src/index.js";
import { serverIt } from "./server-it.js";

const it = serverIt();

describe("databases, collections, indexes and schemas", () => {
    test("databases and collections are created, listed and dropped", async () => {
        const database = it.client.database("ddl_it");
        await database.create();
        expect(await it.client.listDatabases()).toContain("ddl_it");
        const orders = database.collection("orders");
        await orders.create();
        expect(await database.listCollections()).toEqual(["orders"]);
        await orders.drop();
        expect(await database.listCollections()).toEqual([]);
        await database.drop();
        expect(await it.client.listDatabases()).not.toContain("ddl_it");
    });

    test("names differing only by case and reserved names are refused", async () => {
        await it.client.database("Case_db").create();
        const conflict = it.client.database("case_DB").create();
        await expect(conflict).rejects.toBeInstanceOf(ConflictError);
        await expect(conflict).rejects.toMatchObject({ rawCode: "409-11" });
        await expect(it.client.database("Admin").create()).rejects.toBeInstanceOf(LwnrdbError);
        expect(() => it.client.database("no")).toThrow(TypeError);
    });

    test("indexes are created several at once, recreated, dropped and rebuilt", async () => {
        const database = it.client.database("index_it");
        await database.create();
        const users = database.collection("users");
        await users.create();
        await users.saveAll([
            { _id: "u1", email: "a@x", status: "on" },
            { _id: "u2", email: "b@x", status: "off" },
        ]);
        await users.indexes().create("email", "status");
        await users.indexes().create("email");
        expect(await users.query().filter(field("email").eq("A@X")).count()).toBe(1);
        expect((await users.indexes().reindex("email")).rebuiltFields).toEqual(["email"]);
        expect((await users.indexes().reindex()).rebuiltFields).toHaveLength(2);
        expect((await users.indexes().reindexPrimaryKey()).primaryKeyEntries).toBe(2);
        await users.indexes().drop("status");
        await users.indexes().drop("status");
        expect(await users.query().filter(field("status").eq("on")).count()).toBe(1);
    });

    test("a schema is enforced until deleted", async () => {
        const database = it.client.database("schema_it");
        await database.create();
        const people = database.collection("people");
        await people.create();
        expect(await people.schema().get()).toBeUndefined();
        await people.schema().save({
            type: "object",
            required: ["name"],
            properties: { name: { type: "string", minLength: 1 } },
        });
        expect((await people.schema().get())?.type).toBe("object");
        await expect(people.save({ _id: "p1" })).rejects.toMatchObject({ rawCode: "400-7" });
        await people.save({ _id: "p1", name: "Ana" });
        await people.schema().delete();
        await people.save({ _id: "p2" });
        expect(await people.findById("p2")).toBeDefined();
    });
});
