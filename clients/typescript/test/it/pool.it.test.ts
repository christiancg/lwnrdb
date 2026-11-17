import { describe, expect, test } from "vitest";
import { LwnrdbClient, LwnrdbError } from "../../src/index.js";
import { serverIt } from "./server-it.js";

const it = serverIt();

describe("the connection pool", () => {
    test("many concurrent writers share a small pool and every write lands", async () => {
        await it.client.database("pool_it").create();
        await it.client.database("pool_it").collection("events").create();
        const pooled = await LwnrdbClient.connect(it.settings({ poolSize: 4 }));
        try {
            const events = pooled.database("pool_it").collection("events");
            const writers = Array.from({ length: 32 }, async (_, writer) => {
                for (let i = 0; i < 200; i++) {
                    await events.save({ _id: `t${writer}-${i}`, n: i });
                }
                return 200;
            });
            const written = (await Promise.all(writers)).reduce((total, count) => total + count, 0);
            expect(written).toBe(6400);
            expect(await events.query().count()).toBe(6400);
        } finally {
            await pooled.close();
        }
    });

    test("closing the client while requests run fails them cleanly", async () => {
        await it.client.database("pool_close_it").create();
        await it.client.database("pool_close_it").collection("events").create();
        const closing = await LwnrdbClient.connect(it.settings({ poolSize: 2 }));
        const events = closing.database("pool_close_it").collection("events");
        const writers = Array.from({ length: 4 }, async (_, writer) => {
            let done = 0;
            try {
                for (let i = 0; i < 10_000; i++) {
                    await events.save({ _id: `c${writer}-${i}` });
                    done++;
                }
            } catch (error) {
                expect(error).toBeInstanceOf(LwnrdbError);
            }
            return done;
        });
        await new Promise((resolve) => setTimeout(resolve, 300));
        await closing.close();
        const done = await Promise.all(writers);
        expect(done.every((count) => count < 10_000)).toBe(true);
    });
});
