import { describe, expect, test } from "vitest";
import { ServerClosedConnectionError } from "../../src/index.js";
import { serverIt } from "./server-it.js";

const it = serverIt({ maxRequestSize: "128Kb", maxEntrySize: "64Kb" });

describe("connection limits", () => {
    test("an oversized request closes its connection and the pool recovers", async () => {
        await it.client.database("limits_it").create();
        const notes = it.client.database("limits_it").collection("notes");
        await notes.create();
        const refused = notes.save({ _id: "big", text: "x".repeat(200 * 1024) });
        await expect(refused).rejects.toBeInstanceOf(ServerClosedConnectionError);
        await expect(refused).rejects.toMatchObject({ rawCode: "400-22" });
        await notes.save({ _id: "small", text: "ok" });
        expect(await notes.findById("small")).toBeDefined();
    });
});
