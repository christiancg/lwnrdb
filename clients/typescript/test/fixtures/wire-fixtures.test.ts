import { describe, expect, test } from "vitest";
import { OPERATIONS } from "../../src/index.js";
import { equivalent } from "../support/json-trees.js";
import { requests } from "../support/protocol-fixtures.js";
import { Recorder } from "./recorder.js";
import { allCases } from "./wire-fixture-cases.js";

const cases = allCases();
const fixtures = requests();

describe("request fixtures", () => {
    test.each(fixtures.map((fixture) => [fixture.name, fixture] as const))("%s", async (name, fixture) => {
        const call = cases.get(name);
        expect(call, `${name} has no builder mapping`).toBeDefined();
        const recorder = new Recorder();
        await call?.(recorder);
        const expected = fixture.body.request;
        const actual = recorder.channel.last();
        expect(
            equivalent(expected, actual),
            `${name}\nexpected ${JSON.stringify(expected)}\nactual   ${JSON.stringify(actual)}`,
        ).toBe(true);
    });

    test("every mapping names an existing fixture", () => {
        const names = new Set(fixtures.map((fixture) => fixture.name));
        expect([...cases.keys()].filter((name) => !names.has(name))).toEqual([]);
    });

    test("every operation is produced by some mapping", async () => {
        const produced = new Set<string>();
        for (const call of cases.values()) {
            const recorder = new Recorder();
            await call(recorder);
            recorder.channel.requests.forEach((request) => produced.add(request.type as string));
        }
        expect([...produced].sort()).toEqual(OPERATIONS.map((operation) => operation.name).sort());
    });
});
