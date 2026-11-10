import { createServer } from "node:net";
import { afterEach, describe, expect, test, vi } from "vitest";
import { ConnectionLostError } from "../../../src/index.js";
import { ConnectionFactory, openSocket } from "../../../src/internal/connection-factory.js";
import { RequestEncoder } from "../../../src/internal/request-encoder.js";
import { resolveSettings } from "../../../src/settings.js";
import { FakeServer } from "../../support/fake-server.js";

let server: FakeServer | undefined;

afterEach(async () => {
    await server?.close();
    server = undefined;
    vi.restoreAllMocks();
});

async function closedPort(): Promise<number> {
    const probe = createServer();
    await new Promise<void>((resolve) => probe.listen(0, "127.0.0.1", resolve));
    const address = probe.address();
    const port = typeof address === "object" && address !== null ? address.port : 0;
    await new Promise((resolve) => probe.close(resolve));
    return port;
}

describe("ConnectionFactory", () => {
    test("tries the next host when one refuses", async () => {
        server = await FakeServer.start();
        const settings = resolveSettings({
            hosts: [{ host: "127.0.0.1", port: await closedPort() }, ...server.hosts],
        });
        const connection = await new ConnectionFactory(settings, new RequestEncoder()).open();
        expect(connection.isOpen).toBe(true);
        connection.close();
    });

    test("reports the last failure when no host accepts", async () => {
        const settings = resolveSettings({ hosts: [{ host: "127.0.0.1", port: await closedPort() }] });
        await expect(new ConnectionFactory(settings, new RequestEncoder()).open()).rejects.toThrow(
            /could not connect to 127.0.0.1:\d+/,
        );
    });

    test("a non-Error rejection from the opener is still reported", async () => {
        const settings = resolveSettings({});
        const factory = new ConnectionFactory(settings, new RequestEncoder(), () => Promise.reject(new Error("boom")));
        await expect(factory.open()).rejects.toBeInstanceOf(ConnectionLostError);
        // eslint-disable-next-line @typescript-eslint/prefer-promise-reject-errors
        const odd = new ConnectionFactory(settings, new RequestEncoder(), () => Promise.reject("plain"));
        await expect(odd.open()).rejects.toThrow(/plain/);
    });

    test("openSocket gives up after connectTimeoutMs when the TLS handshake never completes", async () => {
        server = await FakeServer.start(() => undefined);
        const settings = resolveSettings({ hosts: server.hosts, connectTimeoutMs: 50, tls: { ca: "" } });
        await expect(openSocket({ host: "127.0.0.1", port: server.port }, settings)).rejects.toThrow(
            /no connection within 50 ms/,
        );
    });

    test("an insecure TLS setting warns once", () => {
        const warning = vi.spyOn(process, "emitWarning").mockImplementation(() => undefined);
        new ConnectionFactory(resolveSettings({ tls: { insecure: true } }), new RequestEncoder());
        expect(warning).toHaveBeenCalledTimes(1);
    });
});
