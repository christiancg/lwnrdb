import { describe, expect, test } from "vitest";
import { DEFAULT_RETRY_POLICY } from "../../src/index.js";
import { resolveSettings } from "../../src/settings.js";

describe("resolveSettings", () => {
    test("fills in the Java client's defaults", () => {
        expect(resolveSettings()).toEqual({
            hosts: [{ host: "localhost", port: 8989 }],
            credentials: undefined,
            tls: undefined,
            poolSize: 8,
            connectTimeoutMs: 10_000,
            requestTimeoutMs: 330_000,
            acquireTimeoutMs: 30_000,
            shutdownTimeoutMs: 10_000,
            maxReplyBytes: 256 * 1024 * 1024,
            retryPolicy: DEFAULT_RETRY_POLICY,
            temporalZone: undefined,
        });
        expect(Object.isFrozen(resolveSettings())).toBe(true);
    });

    test("keeps what it was given", () => {
        const settings = resolveSettings({
            hosts: [{ host: "db", port: 1 }],
            credentials: { username: "a", password: "b" },
            tls: { insecure: true },
            poolSize: 2,
            temporalZone: "Europe/Madrid",
        });
        expect(settings.hosts).toEqual([{ host: "db", port: 1 }]);
        expect(settings.credentials).toEqual({ username: "a", password: "b" });
        expect(settings.tls).toEqual({ insecure: true });
        expect(settings.temporalZone).toBe("Europe/Madrid");
    });

    test("rejects bad values", () => {
        expect(() => resolveSettings({ poolSize: 0 })).toThrow(/poolSize/);
        expect(() => resolveSettings({ requestTimeoutMs: -1 })).toThrow(/requestTimeoutMs/);
        expect(() => resolveSettings({ connectTimeoutMs: 1.5 })).toThrow(/connectTimeoutMs/);
        expect(() => resolveSettings({ acquireTimeoutMs: 2 ** 31 })).toThrow(/acquireTimeoutMs/);
        expect(() => resolveSettings({ shutdownTimeoutMs: 0 })).toThrow(/shutdownTimeoutMs/);
        expect(() => resolveSettings({ maxReplyBytes: 0 })).toThrow(/maxReplyBytes/);
        expect(() => resolveSettings({ hosts: [] })).toThrow(/at least one server/);
        expect(() => resolveSettings({ hosts: [{ host: "", port: 1 }] })).toThrow(TypeError);
        expect(() => resolveSettings({ hosts: [{ host: "db", port: 70_000 }] })).toThrow(/port/);
        expect(() => resolveSettings({ temporalZone: "Mars/Olympus" })).toThrow(RangeError);
        expect(() =>
            resolveSettings({ credentials: { username: "a" } as unknown as { username: string; password: string } }),
        ).toThrow(/credentials/);
        expect(() =>
            resolveSettings({ retryPolicy: { maxAttempts: 0, initialBackoffMs: 0, maxBackoffMs: 0 } }),
        ).toThrow(/maxAttempts/);
    });
});
