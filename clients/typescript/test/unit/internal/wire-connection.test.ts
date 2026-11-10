import { connect } from "node:net";
import { afterEach, describe, expect, test } from "vitest";
import {
    ConnectionLostError,
    LwnrdbError,
    RequestTimeoutError,
    ServerClosedConnectionError,
} from "../../../src/index.js";
import type { JsonObject } from "../../../src/internal/values-codec.js";
import { WireConnection } from "../../../src/internal/wire-connection.js";
import { FakeServer, type Responder } from "../../support/fake-server.js";
import { ok } from "../../support/recording-channel.js";

let server: FakeServer | undefined;

async function open(responder: Responder, maxReplyBytes = 1024): Promise<WireConnection> {
    server = await FakeServer.start(responder);
    const port = server.port;
    const socket = connect({ host: "127.0.0.1", port });
    await new Promise((resolve) => socket.once("connect", resolve));
    return new WireConnection(socket, maxReplyBytes);
}

const silent: Responder = () => undefined;

afterEach(async () => {
    await server?.close();
    server = undefined;
});

describe("WireConnection", () => {
    test("completes requests in FIFO order", async () => {
        const connection = await open((request, session) => {
            session.send(ok(request, { echo: request.n ?? null }));
        });
        const replies = await Promise.all([1, 2, 3].map((n) => connection.send({ type: "SAVE", n })));
        expect(replies.map((reply) => reply.echo)).toEqual([1, 2, 3]);
        connection.close();
    });

    test("a non-JSON line fails only the oldest request", async () => {
        const connection = await open((request, session) => {
            if (request.n === 1) {
                session.sendRaw("The command is not valid\n");
            } else {
                session.send(ok(request));
            }
        });
        await expect(connection.send({ type: "SAVE", n: 1 })).rejects.toThrow(/the server refused the request/);
        await expect(connection.send({ type: "SAVE", n: 2 })).resolves.toMatchObject({ status: "OK" });
        expect(connection.isOpen).toBe(true);
        connection.close();
    });

    test("a JSON line that is not an object fails only the oldest request", async () => {
        const connection = await open((request, session) => {
            session.sendRaw(request.n === 1 ? "[1]\n\n" : `${JSON.stringify(ok(request))}\n`);
        });
        await expect(connection.send({ type: "SAVE", n: 1 })).rejects.toBeInstanceOf(LwnrdbError);
        await expect(connection.send({ type: "SAVE", n: 2 })).resolves.toMatchObject({ status: "OK" });
        connection.close();
    });

    test("a reply over maxReplyBytes fails its request and keeps the connection", async () => {
        const connection = await open((request, session) => {
            session.send(ok(request, { padding: request.n === 1 ? "x".repeat(2048) : "" }));
        });
        await expect(connection.send({ type: "AGGREGATE", n: 1 })).rejects.toThrow(/exceeded maxReplyBytes/);
        await expect(connection.send({ type: "AGGREGATE", n: 2 })).resolves.toMatchObject({ status: "OK" });
        connection.close();
    });

    test("a timeout rejects with RequestTimeoutError and closes the connection", async () => {
        const connection = await open(silent);
        const closed = new Promise<LwnrdbError>((resolve) => {
            connection.onClose(resolve);
        });
        await expect(connection.request({ type: "SAVE" }, 50)).rejects.toBeInstanceOf(RequestTimeoutError);
        expect(await closed).toBeInstanceOf(RequestTimeoutError);
        expect(connection.isOpen).toBe(false);
        await expect(connection.send({ type: "SAVE" })).rejects.toBeInstanceOf(RequestTimeoutError);
    });

    test("an unrequested close frame fails every pending request", async () => {
        const connection = await open((request, session) => {
            if (request.n === 2) {
                session.send({ type: "CLOSE_CONNECTION", status: "ERROR", message: "too slow", errorCode: "408-4" });
            }
        });
        const first = connection.send({ type: "SAVE", n: 1 });
        const second = connection.send({ type: "SAVE", n: 2 });
        await expect(first).rejects.toBeInstanceOf(ServerClosedConnectionError);
        await expect(second).rejects.toThrow(/the server closed the connection: too slow/);
        expect(connection.isOpen).toBe(false);
    });

    test("closeGracefully sends CLOSE_CONNECTION and takes its reply as a response", async () => {
        const connection = await open((request, session) => {
            session.send(ok(request));
        });
        await connection.closeGracefully(1000);
        expect(server?.requestsOfType("CLOSE_CONNECTION")).toHaveLength(1);
        expect(connection.isOpen).toBe(false);
        await connection.closeGracefully(1000);
    });

    test("closeGracefully still closes when the server never answers", async () => {
        const connection = await open(silent);
        await connection.closeGracefully(30);
        expect(connection.isOpen).toBe(false);
    });

    test("a server that drops the socket fails pending requests with ConnectionLostError", async () => {
        const connection = await open((_, session) => {
            session.close();
        });
        await expect(connection.send({ type: "SAVE" })).rejects.toBeInstanceOf(ConnectionLostError);
    });

    test("a reply nobody asked for closes the connection", async () => {
        const connection = await open(silent);
        const closed = new Promise<LwnrdbError>((resolve) => {
            connection.onClose(resolve);
        });
        const session = await server?.awaitSession(0);
        session?.send({ type: "SAVE", status: "OK" });
        expect((await closed).message).toMatch(/a reply nobody asked for/);
    });

    test("pushes go to the push sink, and a throwing sink closes the connection", async () => {
        const connection = await open(silent);
        const pushes: JsonObject[] = [];
        connection.onPush((frame) => {
            pushes.push(frame);
            if (pushes.length === 2) {
                throw new Error("handler broke");
            }
        });
        const closed = new Promise<LwnrdbError>((resolve) => {
            connection.onClose(resolve);
        });
        const pending = connection.send({ type: "LISTEN" });
        const session = await server?.awaitSession(0);
        session?.send({ type: "LISTEN", isUpdate: true, listenId: "a" });
        session?.send({ type: "LISTEN", isUpdate: true, listenId: "b" });
        expect((await closed).message).toMatch(/the reply reader failed: handler broke/);
        await expect(pending).rejects.toBeInstanceOf(ConnectionLostError);
        expect(pushes).toHaveLength(2);
    });

    test("hold keeps the socket referenced while nothing is pending", async () => {
        const connection = await open(silent);
        connection.hold(true);
        connection.hold(false);
        connection.close();
        connection.close();
        expect(connection.isOpen).toBe(false);
    });
});
