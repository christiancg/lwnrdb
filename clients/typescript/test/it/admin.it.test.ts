import { describe, expect, test } from "vitest";
import { AuthenticationError, DataPermission, field, ForbiddenError, LwnrdbClient } from "../../src/index.js";
import { serverIt } from "./server-it.js";

const BOB_PASSWORD = "Bob-Secret-1234";
const it = serverIt();

function asBob(password = BOB_PASSWORD): Promise<LwnrdbClient> {
    return LwnrdbClient.connect(it.settings({ credentials: { username: "bob", password } }));
}

describe("administration", () => {
    test("users, permissions, owners and passwords", async () => {
        await it.client.database("admin_it").create();
        await it.client.database("admin_it").collection("orders").create();
        await it.client
            .admin()
            .users()
            .create("bob", BOB_PASSWORD, { databases: { admin_it: DataPermission.READ } });
        const bob = await asBob();
        try {
            const orders = bob.database("admin_it").collection("orders");
            expect(await orders.query().toArray()).toEqual([]);
            const refused = orders.save({ _id: "x" });
            await expect(refused).rejects.toBeInstanceOf(ForbiddenError);
            await expect(refused).rejects.toMatchObject({ rawCode: expect.stringMatching(/^403/) as string });
            await expect(bob.admin().users().list()).rejects.toBeInstanceOf(ForbiddenError);
            await bob.changePassword(BOB_PASSWORD, "Bob-Secret-5678");
        } finally {
            await bob.close();
        }
        await expect(asBob()).rejects.toBeInstanceOf(AuthenticationError);
        await it.client
            .admin()
            .users()
            .changePermissions("bob", { databases: { admin_it: DataPermission.READ_WRITE } });
        await it.client.admin().users().setPassword("bob", BOB_PASSWORD);
        const writer = await asBob();
        try {
            await writer.database("admin_it").collection("orders").save({ _id: "y" });
        } finally {
            await writer.close();
        }
        await it.client.database("admin_it").setOwners(["bob"]);
        const listed = await it.client.admin().users().list(field("_id").eq("bob"));
        expect(listed).toHaveLength(1);
        expect(listed[0]?.ownedDatabases).toEqual(["admin_it"]);
        expect((await it.client.admin().users().list()).length).toBeGreaterThanOrEqual(2);
        await it.client.admin().users().delete("bob");
        expect(await it.client.admin().users().list(field("_id").eq("bob"))).toEqual([]);
    });

    test("server info, stats and transactions", async () => {
        const info = await it.client.serverInfo();
        expect(info.state).toBe("READY");
        expect(info.version).toBeDefined();
        expect(info.raw).toHaveProperty("node");
        await it.client.database("stats_it").create();
        expect(Object.keys(await it.client.databaseStats()).length).toBeGreaterThan(0);
        expect(await it.client.database("stats_it").stats()).toBeDefined();
        expect(await it.client.admin().transactions().list()).toEqual([]);
    });
});
