import { afterEach, describe, expect, test } from "vitest";
import { connectWith, type LwnrdbClient } from "../../src/client.js";
import {
    ConnectionLostError,
    type Document,
    field,
    GeoPoint,
    ListenEndedError,
    type ListenHandler,
    type ListenUpdate,
    LwnrdbError,
    type Stored,
} from "../../src/index.js";
import type { JsonObject } from "../../src/internal/values-codec.js";
import { FakeServer, type Session } from "../support/fake-server.js";
import { error, ok } from "../support/recording-channel.js";

let server: FakeServer | undefined;
let client: LwnrdbClient | undefined;
let nextListen = 0;

function listenReply(request: JsonObject, listenId: string): JsonObject {
    return ok(request, { listenId, results: [{ _id: "o-1", at: "#geo(1,2)" }], resultHash: "h0", isUpdate: false });
}

function push(listenId: string, id: string): JsonObject {
    return { type: "LISTEN", status: "OK", listenId, results: [{ _id: id }], resultHash: id, isUpdate: true };
}

async function start(
    onListen?: (request: JsonObject, session: Session, listenId: string) => void,
): Promise<LwnrdbClient> {
    server = await FakeServer.start((request, session) => {
        if (request.type === "LISTEN") {
            const listenId = `listen-${++nextListen}`;
            if (onListen === undefined) {
                session.send(listenReply(request, listenId));
            } else {
                onListen(request, session, listenId);
            }
            return;
        }
        session.send(ok(request));
    });
    client = await connectWith({ hosts: server.hosts, poolSize: 1 }, {});
    return client;
}

function recorder(): {
    handler: ListenHandler<Stored<Document>>;
    updates: ListenUpdate<Stored<Document>>[];
    events: string[];
} {
    const updates: ListenUpdate<Stored<Document>>[] = [];
    const events: string[] = [];
    return {
        updates,
        events,
        handler: {
            onUpdate: (update) => {
                updates.push(update);
                events.push(`update:${update.resultHash}`);
            },
            onEnded: (reason) => {
                events.push(`ended:${reason.rawCode ?? ""}`);
            },
            onError: (failure) => {
                events.push(`error:${failure instanceof Error ? failure.message : String(failure)}`);
            },
        },
    };
}

async function until(condition: () => boolean): Promise<void> {
    for (let tries = 0; tries < 200 && !condition(); tries++) {
        await new Promise((resolve) => setTimeout(resolve, 5));
    }
    expect(condition()).toBe(true);
}

afterEach(async () => {
    await client?.close();
    await server?.close();
    client = undefined;
    server = undefined;
});

describe("listens", () => {
    test("delivers the initial result decoded, then pushes in order", async () => {
        const orders = (await start()).database("shop").collection("orders");
        const { handler, updates } = recorder();
        const subscription = await orders.listen(orders.query().filter(field("status").eq("pending")), handler);
        expect(server?.requestsOfType("LISTEN")[0]?.aggregationSteps).toHaveLength(1);
        const session = server?.sessions.at(-1);
        session?.send(push(subscription.listenId, "a"));
        session?.send(push(subscription.listenId, "b"));
        await until(() => updates.length === 3);
        expect(updates[0]?.initial).toBe(true);
        expect(updates[0]?.results[0]?.at).toEqual(GeoPoint.of(1, 2));
        expect(updates.slice(1).map((update) => [update.initial, update.resultHash])).toEqual([
            [false, "a"],
            [false, "b"],
        ]);
        expect(subscription.isActive).toBe(true);
        await subscription.close();
        expect(server?.requestsOfType("STOP_LISTEN")).toEqual([
            { type: "STOP_LISTEN", listenId: subscription.listenId },
        ]);
        expect(subscription.isActive).toBe(false);
        await subscription.close();
    });

    test("a push that arrives before the initial reply waits for it", async () => {
        const orders = (
            await start((request, session, listenId) => {
                session.send(push(listenId, "early"));
                session.send(listenReply(request, listenId));
            })
        )
            .database("shop")
            .collection("orders");
        const { handler, events } = recorder();
        await orders.listen(handler);
        await until(() => events.length === 2);
        expect(events).toEqual(["update:h0", "update:early"]);
    });

    test("an async handler finishes before the next update starts", async () => {
        const orders = (await start()).database("shop").collection("orders");
        const seen: string[] = [];
        const subscription = await orders.listen({
            onUpdate: async (update) => {
                seen.push(`start:${update.resultHash}`);
                await new Promise((resolve) => setTimeout(resolve, update.initial ? 30 : 1));
                seen.push(`end:${update.resultHash}`);
            },
        });
        server?.sessions.at(-1)?.send(push(subscription.listenId, "a"));
        await until(() => seen.length === 4);
        expect(seen).toEqual(["start:h0", "end:h0", "start:a", "end:a"]);
    });

    test("a full connection opens another one", async () => {
        let listens = 0;
        const orders = (
            await start((request, session, listenId) => {
                listens++;
                session.send(listens === 2 ? error(request, "ERROR", "400-25") : listenReply(request, listenId));
            })
        )
            .database("shop")
            .collection("orders");
        const first = await orders.listen(recorder().handler);
        const second = await orders.listen(recorder().handler);
        expect(first.listenId).not.toBe(second.listenId);
        expect(server?.sessions).toHaveLength(3);
    });

    test("a refusal other than a full connection is thrown", async () => {
        const orders = (
            await start((request, session) => {
                session.send(error(request, "ERROR", "400-19"));
            })
        )
            .database("shop")
            .collection("orders");
        await expect(orders.listen(recorder().handler)).rejects.toThrow(/400-19/);
        await expect(orders.listen(recorder().handler)).rejects.toBeInstanceOf(LwnrdbError);
    });

    test("an ended listen calls onEnded and sends no STOP_LISTEN on close", async () => {
        const orders = (await start()).database("shop").collection("orders");
        const { handler, events } = recorder();
        const subscription = await orders.listen(handler);
        server?.sessions.at(-1)?.send({
            type: "LISTEN",
            status: "NOT_FOUND",
            errorCode: "410-1",
            message: "dropped",
            listenId: subscription.listenId,
        });
        await until(() => events.includes("ended:410-1"));
        expect(subscription.isActive).toBe(false);
        await subscription.close();
        expect(server?.requestsOfType("STOP_LISTEN")).toEqual([]);
    });

    test("a throwing handler reports to onError and keeps the subscription", async () => {
        const orders = (await start()).database("shop").collection("orders");
        const errors: unknown[] = [];
        const subscription = await orders.listen({
            onUpdate: (update) => {
                if (!update.initial) {
                    throw new Error("render failed");
                }
            },
            onError: (failure) => {
                errors.push(failure);
                throw new Error("onError failed too");
            },
        });
        server?.sessions.at(-1)?.send(push(subscription.listenId, "a"));
        await until(() => errors.length === 1);
        expect((errors[0] as Error).message).toBe("render failed");
        expect(subscription.isActive).toBe(true);
    });

    test("a throwing onEnded reports to onError", async () => {
        const orders = (await start()).database("shop").collection("orders");
        const errors: unknown[] = [];
        const subscription = await orders.listen({
            onUpdate: () => undefined,
            onEnded: (reason: ListenEndedError) => {
                throw new Error(`could not handle ${reason.rawCode ?? ""}`);
            },
            onError: (failure) => {
                errors.push(failure);
            },
        });
        server?.sessions.at(-1)?.send({ type: "LISTEN", errorCode: "410-1", listenId: subscription.listenId });
        await until(() => errors.length === 1);
        expect((errors[0] as Error).message).toBe("could not handle 410-1");
    });

    test("a lost connection ends every subscription on it with onError", async () => {
        const orders = (await start()).database("shop").collection("orders");
        const failures: unknown[] = [];
        const subscription = await orders.listen({
            onUpdate: () => undefined,
            onError: (failure) => {
                failures.push(failure);
            },
        });
        server?.sessions.at(-1)?.close();
        await until(() => failures.length === 1);
        expect(failures[0]).toBeInstanceOf(ConnectionLostError);
        expect(subscription.isActive).toBe(false);
        await subscription.close();
        const replacement = await orders.listen(recorder().handler);
        expect(replacement.isActive).toBe(true);
    });

    test("disposing the client closes listen connections", async () => {
        const started = await start();
        const orders = started.database("shop").collection("orders");
        const { handler, events } = recorder();
        const subscription = await orders.listen(handler);
        await started[Symbol.asyncDispose]();
        await until(() => events.some((event) => event.startsWith("error:")));
        expect(subscription.isActive).toBe(false);
        await subscription[Symbol.asyncDispose]();
    });

    test("listen needs a handler", async () => {
        const orders = (await start()).database("shop").collection("orders");
        await expect(
            orders.listen(orders.query(), undefined as unknown as ListenHandler<Stored<Document>>),
        ).rejects.toThrow(/listen needs a handler/);
    });
});
