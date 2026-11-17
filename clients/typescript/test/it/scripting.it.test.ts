import { beforeAll, describe, expect, test } from "vitest";
import {
    type Database,
    LwnrdbError,
    TriggerEvent,
    TriggerRunDecision,
    TriggerRunStatus,
    TriggerTiming,
} from "../../src/index.js";
import { eventually, serverIt } from "./server-it.js";

const it = serverIt({ triggersEnabled: "true", triggerMaxAttempts: "1", triggerRetryBackoffMs: "100" });
let shop: Database;

beforeAll(async () => {
    shop = it.client.database("script_it");
    await shop.create();
    await shop.collection("orders").create();
    await shop.collection("audit").create();
});

describe("scripting", () => {
    test("runScript returns its value and logs", async () => {
        const result = await shop.runScript(
            'import args from "args";\nconsole.log("hi " + args.name);\nreturn { greeting: "hello " + args.name };',
            { name: "Ana" },
        );
        expect(result.value).toEqual({ greeting: "hello Ana" });
        expect(result.logs[0]).toBe("hi Ana");
        expect((await shop.runScript("return 1 + 1;")).value).toBe(2);
    });

    test("procedures are saved, listed, called and deleted", async () => {
        const version = await shop.procedures().save({
            name: "double_it",
            script: 'import args from "args";\nreturn args.n * 2;',
            description: "doubles",
        });
        expect(version).toBeGreaterThan(0);
        expect((await shop.procedures().list()).some((procedure) => procedure.name === "double_it")).toBe(true);
        const withSource = (await shop.procedures().list(true)).find((procedure) => procedure.name === "double_it");
        expect(withSource?.source).toBeDefined();
        expect((await shop.procedures().call("double_it", { n: 21 })).value).toBe(42);
        await expect(
            shop.procedures().save({ name: "double_it", script: "return 0;", ifVersion: version + 10 }),
        ).rejects.toBeInstanceOf(LwnrdbError);
        await shop.procedures().delete("double_it");
        await expect(shop.procedures().call("double_it")).rejects.toBeInstanceOf(LwnrdbError);
    });

    test("triggers fire, test, reject and list", async () => {
        await shop.procedures().save({
            name: "audit_write",
            script:
                'import db from "db";\nimport args from "args";\n' +
                'db.save(db.name, "audit", { _id: args.id, event: args.event });\nreturn true;',
        });
        await shop.procedures().save({
            name: "require_total",
            script:
                "export default function (document) {\n" +
                '  if (document.total == null) { throw new Error("total missing"); }\n  return true;\n}',
        });
        const orders = shop.collection("orders");
        const saved = await orders
            .triggers()
            .save({ name: "audit", procedureName: "audit_write", events: [TriggerEvent.CREATED] });
        expect(saved.version).toBeGreaterThan(0);
        await orders.triggers().save({
            name: "guard",
            procedureName: "require_total",
            events: [TriggerEvent.CREATED],
            timing: TriggerTiming.BEFORE,
        });
        expect(await orders.triggers().list()).toHaveLength(2);
        expect((await shop.listTriggers()).map((trigger) => trigger.name)).toEqual(
            expect.arrayContaining(["audit", "guard"]) as string[],
        );
        expect((await orders.triggers().test("guard", TriggerEvent.CREATED, { _id: "t1" })).decision).not.toBe("");
        const refused = orders.save({ _id: "o1" });
        await expect(refused).rejects.toMatchObject({ rawCode: "400-21" });
        await expect(refused).rejects.toThrow(/total missing/);
        await orders.save({ _id: "o2", total: 5 });
        const audit = shop.collection("audit");
        expect(
            await eventually(
                () => audit.findById("o2"),
                (row) => row !== undefined,
            ),
        ).toBeDefined();
        await orders.triggers().delete("audit");
        await orders.triggers().delete("guard");
        expect(await orders.triggers().list()).toEqual([]);
    });

    test("dead-lettered trigger runs are listed and resolved", async () => {
        await shop.procedures().save({ name: "always_fails", script: 'throw new Error("boom");' });
        const notes = shop.collection("notes");
        await notes.create();
        await notes.triggers().save({ name: "failing", procedureName: "always_fails", events: [TriggerEvent.CREATED] });
        await notes.save({ _id: "n1" });
        const runs = it.client.admin().triggerRuns();
        const dead = await eventually(
            () => runs.list(TriggerRunStatus.DEAD),
            (list) => list.length > 0,
        );
        expect(dead.length).toBeGreaterThan(0);
        expect((await runs.list()).length).toBeGreaterThanOrEqual(dead.length);
        expect(await runs.resolve(dead[0]?.runId ?? "", TriggerRunDecision.DISCARD)).toBe(true);
    });

    test("schedules are saved, listed and deleted", async () => {
        await shop.procedures().save({ name: "noop", script: "return 0;" });
        expect(
            await shop.schedules().save({ name: "hourly", procedureName: "noop", cron: "0 * * * *", args: { a: 1 } }),
        ).toBeGreaterThan(0);
        await shop.schedules().save({ name: "poll", procedureName: "noop", intervalMs: 300_000 });
        expect(await shop.schedules().list()).toHaveLength(2);
        expect((await shop.schedules().list(true)).some((schedule) => "args" in schedule.raw)).toBe(true);
        await shop.schedules().delete("hourly");
        await shop.schedules().delete("poll");
        expect(await shop.schedules().list()).toEqual([]);
    });

    test("a long script is listed and cancelled", async () => {
        const running = shop.runScript("let i = 0;\nwhile (true) { i++; }").catch(() => undefined);
        const scripts = await eventually(
            () => it.client.admin().scripts().list(),
            (list) => list.length > 0,
        );
        expect(scripts.length).toBeGreaterThan(0);
        expect(
            await it.client
                .admin()
                .scripts()
                .cancel(scripts[0]?.runId ?? ""),
        ).toBe(true);
        await running;
    });
});
