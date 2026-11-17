import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import type { JsonObject } from "../../src/internal/values-codec.js";

export const PROTOCOL_ROOT = join(import.meta.dirname, "..", "..", "..", "protocol");

export interface FixtureCase {
    readonly name: string;
    readonly body: JsonObject;
}

export function read(file: string): JsonObject {
    return JSON.parse(readFileSync(join(PROTOCOL_ROOT, file), "utf8")) as JsonObject;
}

function cases(folder: string): FixtureCase[] {
    const directory = join(PROTOCOL_ROOT, "fixtures", folder);
    return readdirSync(directory)
        .filter((file) => file.endsWith(".json"))
        .sort()
        .flatMap((file) => {
            const area = read(join("fixtures", folder, file));
            return (area.cases as JsonObject[]).map((body) => ({ name: body.name as string, body }));
        });
}

export function requests(): FixtureCase[] {
    return cases("requests");
}

export function responses(): FixtureCase[] {
    return cases("responses");
}
