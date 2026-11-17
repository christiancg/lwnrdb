import { spawn, type ChildProcess } from "node:child_process";
import { existsSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { connect, createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

export const ADMIN = "admin";
export const PASSWORD = "Tst-Admin-9xQ2mV";
const STARTUP_MS = 90_000;
const STOP_MS = 30_000;
const DEFAULT_JAR = join(import.meta.dirname, "..", "..", "..", "..", "target", "lwnrdb.jar");

function freePort(): Promise<number> {
    return new Promise((resolvePort, reject) => {
        const probe = createServer();
        probe.once("error", reject);
        probe.listen(0, "127.0.0.1", () => {
            const address = probe.address();
            const port = typeof address === "object" && address !== null ? address.port : 0;
            probe.close(() => {
                resolvePort(port);
            });
        });
    });
}

function accepts(port: number): Promise<boolean> {
    return new Promise((resolveAccepts) => {
        const socket = connect({ host: "localhost", port });
        socket.once("connect", () => {
            socket.destroy();
            resolveAccepts(true);
        });
        socket.once("error", () => {
            socket.destroy();
            resolveAccepts(false);
        });
    });
}

export class ServerProcess {
    readonly port: number;
    readonly workDir: string;
    readonly #process: ChildProcess;
    #exited = false;

    private constructor(port: number, workDir: string, process: ChildProcess) {
        this.port = port;
        this.workDir = workDir;
        this.#process = process;
        process.once("exit", () => {
            this.#exited = true;
        });
    }

    static async start(overrides: Record<string, string> = {}): Promise<ServerProcess> {
        const jar = resolve(process.env.LWNRDB_JAR ?? DEFAULT_JAR);
        if (!existsSync(jar)) {
            throw new Error(`no server jar at ${jar}; run mvn package -DskipTests at the repository root`);
        }
        const workDir = mkdtempSync(join(tmpdir(), "lwnrdb-it-"));
        const port = await freePort();
        const config: Record<string, string> = {
            port: String(port),
            filePath: "db",
            logPath: "logs",
            defaultAdminPassword: PASSWORD,
            maxMemory: "128Mb",
            ...overrides,
        };
        writeFileSync(
            join(workDir, "lwnrdb.cfg"),
            Object.entries(config)
                .map(([key, value]) => `${key}=${value}\n`)
                .join(""),
        );
        const output = openSync(join(workDir, "server.out"), "w");
        const child = spawn("java", ["-Xmx512m", "-XX:+UseG1GC", "-jar", jar], {
            cwd: workDir,
            stdio: ["ignore", output, output],
        });
        const server = new ServerProcess(port, workDir, child);
        await server.#awaitPort();
        return server;
    }

    output(): string {
        return readFileSync(join(this.workDir, "server.out"), "utf8");
    }

    async stop(): Promise<void> {
        if (!this.#exited) {
            const exited = new Promise((resolveExit) => this.#process.once("exit", resolveExit));
            this.#process.kill("SIGTERM");
            const timer = setTimeout(() => this.#process.kill("SIGKILL"), STOP_MS);
            await exited;
            clearTimeout(timer);
        }
        rmSync(this.workDir, { recursive: true, force: true });
    }

    async #awaitPort(): Promise<void> {
        const deadline = Date.now() + STARTUP_MS;
        while (Date.now() < deadline) {
            if (this.#exited) {
                throw new Error(`the server exited: ${this.output()}`);
            }
            if (await accepts(this.port)) {
                return;
            }
            await new Promise((resolveWait) => setTimeout(resolveWait, 200));
        }
        this.#process.kill("SIGKILL");
        throw new Error(`the server did not open port ${this.port} within ${STARTUP_MS} ms`);
    }
}
