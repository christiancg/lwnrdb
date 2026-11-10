import { afterEach, describe, expect, test } from "vitest";
import { connectWith, LwnrdbClient } from "../../src/client.js";
import { AuthenticationError, ConnectionLostError, LwnrdbError, UnknownOutcomeError } from "../../src/index.js";
import type { JsonObject } from "../../src/internal/values-codec.js";
import { FakeServer, type Responder } from "../support/fake-server.js";
import { error, ok } from "../support/recording-channel.js";

let server: FakeServer | undefined;
let client: LwnrdbClient | undefined;

async function start(
    replies: Record<string, (request: JsonObject) => JsonObject> = {},
    credentials = true,
): Promise<LwnrdbClient> {
    const responder: Responder = (request, session) => {
        const reply = replies[request.type as string];
        session.send(reply === undefined ? ok(request) : reply(request));
    };
    server = await FakeServer.start(responder);
    client = await connectWith(
        {
            hosts: server.hosts,
            poolSize: 2,
            ...(credentials ? { credentials: { username: "alice", password: "pw" } } : {}),
        },
        { backoff: () => Promise.resolve() },
    );
    return client;
}

afterEach(async () => {
    await client?.close();
    await server?.close();
    client = undefined;
    server = undefined;
});

describe("LwnrdbClient", () => {
    test("connect opens and authenticates one connection", async () => {
        const started = await start();
        expect(server?.requestsOfType("AUTHENTICATE")).toEqual([
            { type: "AUTHENTICATE", username: "alice", password: "pw" },
        ]);
        expect(started.settings.poolSize).toBe(2);
    });

    test("connect fails and cleans up when the login is refused", async () => {
        server = await FakeServer.start((request, session) => {
            session.send(error(request, "UNAUTHENTICATED", "401-1"));
        });
        await expect(
            LwnrdbClient.connect({ hosts: server.hosts, credentials: { username: "a", password: "b" } }),
        ).rejects.toBeInstanceOf(AuthenticationError);
    });

    test("reads the server-level information", async () => {
        const started = await start({
            LIST_DATABASES: (request) => ok(request, { databases: ["shop", "crm"] }),
            SERVER_INFO: (request) => ok(request, { server: { state: "READY", version: "1.2", node: "n1" } }),
            GET_DATABASE_STATS: (request) => ok(request, { stats: { databases: 2 } }),
        });
        expect(await started.listDatabases()).toEqual(["shop", "crm"]);
        expect(await started.serverInfo()).toEqual({
            state: "READY",
            version: "1.2",
            raw: { state: "READY", version: "1.2", node: "n1" },
        });
        expect(await started.databaseStats()).toEqual({ databases: 2 });
        await started.changePassword("pw", "pw2");
        expect(server?.requestsOfType("SET_PASSWORD")).toEqual([
            { type: "SET_PASSWORD", username: "alice", currentPassword: "pw", newPassword: "pw2" },
        ]);
        expect(started.admin()).toBeDefined();
    });

    test("missing replies fall back to empty values", async () => {
        const started = await start();
        expect(await started.serverInfo()).toEqual({ state: undefined, version: undefined, raw: {} });
        expect(await started.databaseStats()).toEqual({});
    });

    test("changePassword needs credentials", async () => {
        const started = await start({}, false);
        await expect(started.changePassword("a", "b")).rejects.toThrow(/no credentials/);
    });

    test("transaction commits when the body resolves", async () => {
        const started = await start({ START_TRANSACTION: (request) => ok(request, { transactionId: "tx-1" }) });
        const id = await started.transaction(async (tx) => {
            await tx.collection("shop", "orders").save({ _id: "o-1" });
            return tx.id;
        });
        expect(id).toBe("tx-1");
        const types = server?.sessions.at(-1)?.requests.map((request) => request.type);
        expect(types?.slice(-3)).toEqual(["START_TRANSACTION", "SAVE", "COMMIT_TRANSACTION"]);
    });

    test("transaction rolls back and rethrows the body's error, ignoring a failed rollback", async () => {
        const started = await start({ ROLLBACK_TRANSACTION: (request) => error(request, "ERROR", "409-4") });
        const failure = new Error("body failed");
        await expect(
            started.transaction(() => {
                throw failure;
            }),
        ).rejects.toBe(failure);
        expect(server?.requestsOfType("ROLLBACK_TRANSACTION")).toHaveLength(1);
        expect(server?.requestsOfType("COMMIT_TRANSACTION")).toHaveLength(0);
    });

    test("transaction does not commit what the body already ended", async () => {
        const started = await start();
        await started.transaction(async (tx) => {
            await tx.rollback();
        });
        await expect(
            started.transaction(async (tx) => {
                await tx.commit();
                throw new Error("after commit");
            }),
        ).rejects.toThrow(/after commit/);
        expect(server?.requestsOfType("COMMIT_TRANSACTION")).toHaveLength(1);
        expect(server?.requestsOfType("ROLLBACK_TRANSACTION")).toHaveLength(1);
    });

    test("a failed START_TRANSACTION returns the connection", async () => {
        const started = await start({ START_TRANSACTION: (request) => error(request, "ERROR", "409-3") });
        await expect(started.beginTransaction()).rejects.toThrow(/409-3/);
        await expect(started.listDatabases()).resolves.toEqual([]);
    });

    test("an unknown commit outcome discards the connection without a rollback", async () => {
        const started = await start({ COMMIT_TRANSACTION: (request) => error(request, "ERROR", "500-33") });
        const transaction = await started.beginTransaction();
        await expect(transaction.commit()).rejects.toBeInstanceOf(UnknownOutcomeError);
        expect(transaction.isOpen).toBe(false);
        await transaction.close();
        expect(server?.requestsOfType("ROLLBACK_TRANSACTION")).toHaveLength(0);
        expect(() => transaction.collection("shop", "orders")).toThrow(/already committed or rolled back/);
        await expect(transaction.commit()).rejects.toBeInstanceOf(LwnrdbError);
    });

    test("await using rolls back an open transaction", async () => {
        const started = await start();
        {
            await using transaction = await started.beginTransaction();
            expect(transaction.isOpen).toBe(true);
        }
        expect(server?.requestsOfType("ROLLBACK_TRANSACTION")).toHaveLength(1);
    });

    test("a transaction collection reads and writes through the pinned connection", async () => {
        const started = await start({
            FIND_BY_ID: (request) => ok(request, { object: { _id: "o-1", total: 1 } }),
            AGGREGATE: (request) => ok(request, { results: [{ _id: "o-1" }] }),
            BULK_SAVE: (request) => ok(request, { inserted: ["a"], updated: [] }),
        });
        const transaction = await started.beginTransaction();
        const orders = transaction.collection("shop", "orders", {
            encode: (order: { id: string }) => ({ _id: order.id }),
            decode: (document) => ({ id: document._id as string }),
        });
        expect([orders.databaseName, orders.name]).toEqual(["shop", "orders"]);
        await orders.save({ id: "o-1" });
        expect(await orders.saveAll([{ id: "a" }])).toEqual({ inserted: ["a"], updated: [] });
        expect(await orders.findById("o-1")).toEqual({ id: "o-1" });
        expect(await orders.query().toArray()).toEqual([{ id: "o-1" }]);
        await orders.delete("o-1");
        await transaction.commit();
        const session = server?.sessions.find((candidate) =>
            candidate.requests.some((request) => request.type === "START_TRANSACTION"),
        );
        expect(session?.requests.map((request) => request.type).slice(-7)).toEqual([
            "START_TRANSACTION",
            "SAVE",
            "BULK_SAVE",
            "FIND_BY_ID",
            "AGGREGATE",
            "DELETE",
            "COMMIT_TRANSACTION",
        ]);
    });

    test("a lost connection during a transaction discards it", async () => {
        const started = await start();
        const transaction = await started.beginTransaction();
        server?.sessions.forEach((session) => {
            session.close();
        });
        await expect(transaction.commit()).rejects.toBeInstanceOf(ConnectionLostError);
    });

    test("requests after close fail fast", async () => {
        const started = await start();
        await started.close();
        await expect(started.listDatabases()).rejects.toThrow(/the client is closed/);
    });
});
