import { execFileSync } from "node:child_process";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, test } from "vitest";
import { type ClientSettings, LwnrdbClient, LwnrdbError, NO_RETRIES } from "../../src/index.js";
import { ServerProcess } from "../support/server-process.js";
import { settingsFor } from "./server-it.js";

const STORE_PASSWORD = "change_it";
let server: ServerProcess;

function keytool(...args: string[]): string {
    return execFileSync("keytool", args, { encoding: "utf8" });
}

function exportedCertificate(): string {
    const keystore = join(server.workDir, "certs", "lwnrdb.p12");
    const listing = keytool("-list", "-keystore", keystore, "-storepass", STORE_PASSWORD, "-storetype", "PKCS12");
    const alias = /^([^,\s]+),.*PrivateKeyEntry/m.exec(listing)?.[1];
    if (alias === undefined) {
        throw new Error(`no key entry in ${listing}`);
    }
    return keytool(
        "-exportcert",
        "-rfc",
        "-alias",
        alias,
        "-keystore",
        keystore,
        "-storepass",
        STORE_PASSWORD,
        "-storetype",
        "PKCS12",
    );
}

async function connectAndClose(settings: ClientSettings): Promise<void> {
    const client = await LwnrdbClient.connect(settings);
    await client.close();
}

beforeAll(async () => {
    server = await ServerProcess.start({
        tlsEnabled: "true",
        tlsKeystorePath: "certs/lwnrdb.p12",
        tlsKeystorePassword: STORE_PASSWORD,
    });
});

afterAll(async () => {
    await server.stop();
});

describe("TLS", () => {
    test("an insecure client connects over TLS", async () => {
        const client = await LwnrdbClient.connect(settingsFor(server, { tls: { insecure: true } }));
        try {
            expect((await client.serverInfo()).state).toBeDefined();
        } finally {
            await client.close();
        }
    });

    test("a client trusting the exported certificate connects", async () => {
        const client = await LwnrdbClient.connect(settingsFor(server, { tls: { ca: exportedCertificate() } }));
        try {
            expect((await client.serverInfo()).version).toBeDefined();
        } finally {
            await client.close();
        }
    });

    test("system trust and plaintext clients are refused", async () => {
        await expect(connectAndClose(settingsFor(server, { tls: {} }))).rejects.toBeInstanceOf(LwnrdbError);
        await expect(
            connectAndClose(settingsFor(server, { requestTimeoutMs: 5000, retryPolicy: NO_RETRIES })),
        ).rejects.toBeInstanceOf(LwnrdbError);
    });
});
