import { afterAll, beforeAll, expect } from "vitest";
import { type ClientSettings, LwnrdbClient } from "../../src/index.js";
import { ADMIN, PASSWORD, ServerProcess } from "../support/server-process.js";

export interface ServerIt {
    readonly server: ServerProcess;
    readonly client: LwnrdbClient;
    settings(overrides?: ClientSettings): ClientSettings;
}

export function settingsFor(server: ServerProcess, overrides: ClientSettings = {}): ClientSettings {
    return {
        hosts: [{ host: "localhost", port: server.port }],
        credentials: { username: ADMIN, password: PASSWORD },
        requestTimeoutMs: 60_000,
        ...overrides,
    };
}

export function serverIt(config: Record<string, string> = {}): ServerIt {
    let server: ServerProcess | undefined;
    let client: LwnrdbClient | undefined;
    beforeAll(async () => {
        server = await ServerProcess.start(config);
        client = await LwnrdbClient.connect(settingsFor(server));
    });
    afterAll(async () => {
        await client?.close();
        await server?.stop();
    });
    return {
        get server(): ServerProcess {
            if (server === undefined) {
                throw new Error("the server is not started");
            }
            return server;
        },
        get client(): LwnrdbClient {
            if (client === undefined) {
                throw new Error("the client is not connected");
            }
            return client;
        },
        settings(overrides: ClientSettings = {}): ClientSettings {
            return settingsFor(this.server, overrides);
        },
    };
}

export async function eventually<V>(attempt: () => Promise<V>, done: (value: V) => boolean): Promise<V> {
    const deadline = Date.now() + 20_000;
    let last = await attempt();
    while (!done(last) && Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 100));
        last = await attempt();
    }
    return last;
}

export function ids(rows: readonly { _id?: unknown }[]): string[] {
    return rows.map((row) => (typeof row._id === "string" ? row._id : "?"));
}

export function sortedIds(rows: readonly { _id?: unknown }[]): string[] {
    return ids(rows).sort();
}

export async function sameWithAndWithoutIndex<V>(
    collection: { indexes(): { create(field: string): Promise<void>; drop(field: string): Promise<void> } },
    field: string,
    query: () => Promise<V>,
): Promise<V> {
    const scanned = await query();
    await collection.indexes().create(field);
    try {
        expect(await query(), `index and scan disagree on ${field}`).toEqual(scanned);
    } finally {
        await collection.indexes().drop(field);
    }
    return scanned;
}
