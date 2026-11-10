import { afterEach, describe, expect, test } from "vitest";
import { AuthenticationError, ConnectionLostError, PoolExhaustedError } from "../../../src/index.js";
import { ConnectionFactory } from "../../../src/internal/connection-factory.js";
import { ConnectionPool } from "../../../src/internal/connection-pool.js";
import { RequestEncoder } from "../../../src/internal/request-encoder.js";
import { resolveSettings, type ClientSettings } from "../../../src/settings.js";
import { FakeServer, type Responder } from "../../support/fake-server.js";
import { error, ok } from "../../support/recording-channel.js";

let server: FakeServer | undefined;

async function poolFor(settings: ClientSettings, responder?: Responder): Promise<ConnectionPool> {
    server = await FakeServer.start(responder);
    const resolved = resolveSettings({ hosts: server.hosts, ...settings });
    return new ConnectionPool(new ConnectionFactory(resolved, new RequestEncoder()));
}

afterEach(async () => {
    await server?.close();
    server = undefined;
});

describe("ConnectionPool", () => {
    test("grows lazily up to poolSize and reuses released connections", async () => {
        const pool = await poolFor({ poolSize: 2 });
        const first = await pool.borrow();
        const second = await pool.borrow();
        expect(pool.connectionsInUse).toBe(2);
        first.release();
        first.release();
        const third = await pool.borrow();
        expect(third.connection).toBe(first.connection);
        await server?.awaitSession(1);
        expect(server?.sessions).toHaveLength(2);
        second.release();
        third.release();
        expect(pool.idleConnections).toBe(2);
        await pool.close();
    });

    test("a waiter gets the next released connection", async () => {
        const pool = await poolFor({ poolSize: 1 });
        const held = await pool.borrow();
        const waiting = pool.borrow();
        held.release();
        const next = await waiting;
        expect(next.connection).toBe(held.connection);
        next.release();
        await pool.close();
    });

    test("a waiter gives up after acquireTimeoutMs", async () => {
        const pool = await poolFor({ poolSize: 1, acquireTimeoutMs: 30 });
        const held = await pool.borrow();
        await expect(pool.borrow()).rejects.toBeInstanceOf(PoolExhaustedError);
        held.release();
        await pool.close();
    });

    test("a discarded or dead connection is not reused", async () => {
        const pool = await poolFor({ poolSize: 1 });
        const first = await pool.borrow();
        first.discard();
        first.release();
        expect(first.connection.isOpen).toBe(false);
        const second = await pool.borrow();
        expect(second.connection).not.toBe(first.connection);
        second.release();
        second.connection.close();
        const third = await pool.borrow();
        expect(third.connection).not.toBe(second.connection);
        third.release();
        await pool.close();
    });

    test("close sends CLOSE_CONNECTION to idle connections and refuses later borrows", async () => {
        const pool = await poolFor({ poolSize: 2, shutdownTimeoutMs: 50 });
        const idle = await pool.borrow();
        const held = await pool.borrow();
        idle.release();
        const closing = pool.close();
        held.release();
        await closing;
        expect(server?.requestsOfType("CLOSE_CONNECTION")).toHaveLength(1);
        expect(held.connection.isOpen).toBe(false);
        await expect(pool.borrow()).rejects.toThrow(/the client is closed/);
        await pool.close();
    });

    test("close refuses a waiter", async () => {
        const pool = await poolFor({ poolSize: 1, shutdownTimeoutMs: 50 });
        const held = await pool.borrow();
        const waiting = pool.borrow();
        const closing = pool.close();
        await expect(waiting).rejects.toBeInstanceOf(ConnectionLostError);
        held.release();
        await closing;
    });

    test("close waits at most shutdownTimeoutMs for a connection in use", async () => {
        const pool = await poolFor({ poolSize: 1, shutdownTimeoutMs: 30 });
        const held = await pool.borrow();
        await pool.close();
        expect(held.connection.isOpen).toBe(true);
        held.release();
        expect(held.connection.isOpen).toBe(false);
    });

    test("close resolves within shutdownTimeoutMs even when the server never answers", async () => {
        const silent: Responder = () => undefined;
        server = await FakeServer.start(silent);
        const pool = new ConnectionPool(
            new ConnectionFactory(
                resolveSettings({ hosts: server.hosts, shutdownTimeoutMs: 30 }),
                new RequestEncoder(),
            ),
        );
        (await pool.borrow()).release();
        const started = Date.now();
        await pool.close();
        expect(Date.now() - started).toBeLessThan(1000);
    });

    test("authenticates each new connection and frees the permit when it fails", async () => {
        let attempts = 0;
        const pool = await poolFor(
            { poolSize: 1, credentials: { username: "alice", password: "pw" } },
            (request, session) => {
                attempts++;
                session.send(attempts === 1 ? error(request, "UNAUTHENTICATED", "401-1") : ok(request));
            },
        );
        await expect(pool.borrow()).rejects.toBeInstanceOf(AuthenticationError);
        expect(server?.requestsOfType("AUTHENTICATE")[0]).toEqual({
            type: "AUTHENTICATE",
            username: "alice",
            password: "pw",
        });
        const lease = await pool.borrow();
        expect(pool.connectionsInUse).toBe(1);
        lease.release();
        await pool.close();
    });
});
