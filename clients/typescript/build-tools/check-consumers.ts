import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const CONSUMERS = join(ROOT, "consumers");
const TSC = join(ROOT, "node_modules", "typescript", "bin", "tsc");
const NPM_CLI = process.env.npm_execpath;
const NPM: readonly string[] = NPM_CLI === undefined ? ["npm"] : [process.execPath, NPM_CLI];

const CHECKS: Readonly<Record<string, readonly string[]>> = {
    esm: [process.execPath, "index.mjs"],
    cjs: [process.execPath, "index.cjs"],
    typescript: [process.execPath, TSC, "-p", "tsconfig.json"],
};

function run(command: readonly string[], cwd: string): void {
    const [program = "", ...args] = command;
    execFileSync(program, args, { cwd, stdio: "inherit" });
}

const packDirectory = mkdtempSync(join(tmpdir(), "lwnrdb-client-pack-"));
try {
    run([...NPM, "pack", "--silent", "--pack-destination", packDirectory], ROOT);
    const tarball = readdirSync(packDirectory).find((file) => file.endsWith(".tgz"));
    if (tarball === undefined) {
        throw new Error("npm pack produced no tarball");
    }
    for (const [consumer, check] of Object.entries(CHECKS)) {
        const folder = join(CONSUMERS, consumer);
        run([...NPM, "install", "--no-save", "--no-package-lock", "--silent", join(packDirectory, tarball)], folder);
        run(check, folder);
        process.stdout.write(`${consumer} consumer passed\n`);
    }
} finally {
    rmSync(packDirectory, { recursive: true, force: true });
}
