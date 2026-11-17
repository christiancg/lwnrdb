import { afterEach, describe, expect, test } from "vitest";
import { RETRYABLE_CODES } from "../../../src/errors/error-codes.js";
import { ConnectionLostError, LwnrdbError, UnavailableError, UnknownOutcomeError } from "../../../src/index.js";
import { ConnectionFactory } from "../../../src/internal/connection-factory.js";
import { ConnectionPool } from "../../../src/internal/connection-pool.js";
import { isRetryable, jitteredBackoff, PinnedChannel, PooledChannel } from "../../../src/internal/request-channel.js";
import { RequestEncoder } from "../../../src/internal/request-encoder.js";
import { backoffBefore, checkRetryPolicy, DEFAULT_RETRY_POLICY, NO_RETRIES } from "../../../src/retry-policy.js";
import { resolveSettings } from "../../../src/settings.js";
import type { JsonObject } from "../../../src/internal/values-codec.js";
import { FakeServer, type Responder } from "../../support/fake-server.js";
import { error, ok } from "../../support/recording-channel.js";

let server: FakeServer | undefined;
let pool: ConnectionPool | undefined;
const pauses: number[] = [];

async function channelFor(responder: Responder, maxAttempts = 5): Promise<PooledChannel> {
    server = await FakeServer.start(responder);
    const settings = resolveSettings({ hosts: server.hosts, requestTimeoutMs: 200 });
    pool = new ConnectionPool(new ConnectionFactory(settings, new RequestEncoder()));
    const policy = checkRetryPolicy({ maxAttempts, initialBackoffMs: 50, maxBackoffMs: 120 });
    return new PooledChannel(pool, policy, settings.requestTimeoutMs, (delay) => {
        pauses.push(delay);
        return Promise.resolve();
    });
}

function failingFirst(times: number, failure: (request: JsonObject) => JsonObject | undefined): Responder {
    let calls = 0;
    return (request, session) => {
        calls++;
        if (calls <= times) {
            const frame = failure(request);
            if (frame === undefined) {
                session.close();
            } else {
                session.send(frame);
            }
            return;
        }
        session.send(ok(request));
    };
}

afterEach(async () => {
    await pool?.close();
    await server?.close();
    server = undefined;
    pool = undefined;
    pauses.length = 0;
});

describe("PooledChannel retries", () => {
    test.each([...RETRYABLE_CODES])("retries %s with doubling, capped backoff", async (code) => {
        const channel = await channelFor(failingFirst(3, (request) => error(request, "ERROR", code)));
        await expect(channel.send("SAVE", { type: "SAVE" })).resolves.toMatchObject({ status: "OK" });
        expect(pauses).toEqual([50, 100, 120]);
    });

    test("gives up after maxAttempts", async () => {
        const channel = await channelFor(
            failingFirst(9, (request) => error(request, "ERROR", "503-2")),
            3,
        );
        await expect(channel.send("SAVE", { type: "SAVE" })).rejects.toBeInstanceOf(UnavailableError);
        expect(pauses).toHaveLength(2);
    });

    test.each(["503-3", "500-33", "500-34", "400-1"])("never retries %s", async (code) => {
        const channel = await channelFor(failingFirst(1, (request) => error(request, "ERROR", code)));
        await expect(channel.send("SAVE", { type: "SAVE" })).rejects.toBeInstanceOf(LwnrdbError);
        expect(pauses).toEqual([]);
    });

    test("retries a read after a lost connection but never a write", async () => {
        const reads = await channelFor(failingFirst(1, () => undefined));
        await expect(reads.send("AGGREGATE", { type: "AGGREGATE" })).resolves.toMatchObject({ status: "OK" });
        expect(pauses).toHaveLength(1);
        await pool?.close();
        await server?.close();
        pauses.length = 0;
        const writes = await channelFor(failingFirst(1, () => undefined));
        await expect(writes.send("SAVE", { type: "SAVE" })).rejects.toBeInstanceOf(ConnectionLostError);
        expect(pauses).toEqual([]);
        expect(pool?.idleConnections).toBe(0);
    });

    test("isRetryable ignores errors that are not the client's", () => {
        expect(isRetryable("SAVE", new Error("x"))).toBe(false);
        expect(isRetryable("SAVE", new UnavailableError("x"))).toBe(false);
        expect(isRetryable("FIND_BY_ID", new ConnectionLostError("x"))).toBe(true);
        expect(isRetryable("SAVE", new UnknownOutcomeError("x", { code: "500-33" }))).toBe(false);
    });
});

describe("the retry policy", () => {
    test("validates its bounds", () => {
        expect(() => checkRetryPolicy({ maxAttempts: 0, initialBackoffMs: 0, maxBackoffMs: 0 })).toThrow(RangeError);
        expect(() => checkRetryPolicy({ maxAttempts: 1, initialBackoffMs: 10, maxBackoffMs: 5 })).toThrow(RangeError);
        expect(() => checkRetryPolicy({ maxAttempts: 1, initialBackoffMs: -1, maxBackoffMs: 5 })).toThrow(RangeError);
        expect(checkRetryPolicy(NO_RETRIES)).toEqual(NO_RETRIES);
    });

    test("doubles from the first attempt and caps at maxBackoffMs", () => {
        expect([1, 2, 3, 4, 5, 6, 7, 99].map((attempt) => backoffBefore(DEFAULT_RETRY_POLICY, attempt))).toEqual([
            50, 100, 200, 400, 800, 1600, 2000, 2000,
        ]);
        expect(backoffBefore(DEFAULT_RETRY_POLICY, 0)).toBe(50);
    });

    test("the jittered backoff waits between half and all of the delay", async () => {
        const started = Date.now();
        await jitteredBackoff(20);
        expect(Date.now() - started).toBeGreaterThanOrEqual(9);
    });
});

describe("PinnedChannel", () => {
    test("refuses an operation a transaction cannot run, and checks replies", async () => {
        server = await FakeServer.start((request, session) => {
            session.send(request.type === "SAVE" ? ok(request) : error(request, "ERROR", "409-4"));
        });
        pool = new ConnectionPool(
            new ConnectionFactory(resolveSettings({ hosts: server.hosts }), new RequestEncoder()),
        );
        const lease = await pool.borrow();
        const pinned = new PinnedChannel(lease.connection, 500);
        await expect(pinned.send("CREATE_INDEX", { type: "CREATE_INDEX" })).rejects.toThrow(
            /CREATE_INDEX is not allowed inside a transaction/,
        );
        await expect(pinned.send("SAVE", { type: "SAVE" })).resolves.toMatchObject({ status: "OK" });
        await expect(pinned.send("COMMIT_TRANSACTION", { type: "COMMIT_TRANSACTION" })).rejects.toThrow(/409-4/);
        lease.release();
    });
});
