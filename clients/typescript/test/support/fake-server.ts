import { createServer, type Server, type Socket } from "node:net";
import type { JsonObject } from "../../src/internal/values-codec.js";
import { ok } from "./recording-channel.js";

export type Responder = (request: JsonObject, session: Session) => void;

export const okToEverything: Responder = (request, session) => {
    session.send(ok(request));
};

export class Session {
    readonly requests: JsonObject[] = [];
    readonly socket: Socket;

    constructor(socket: Socket) {
        this.socket = socket;
    }

    send(frame: JsonObject): void {
        this.sendRaw(`${JSON.stringify(frame)}\n`);
    }

    sendRaw(text: string | Buffer): void {
        if (!this.socket.destroyed) {
            this.socket.write(text);
        }
    }

    close(): void {
        this.socket.destroy();
    }
}

interface Waiter<V> {
    readonly matches: (value: V) => boolean;
    readonly resolve: (value: V) => void;
}

export class FakeServer {
    readonly sessions: Session[] = [];
    readonly requests: JsonObject[] = [];
    responder: Responder;
    readonly #server: Server;
    readonly #requestWaiters: Waiter<JsonObject>[] = [];
    readonly #sessionWaiters: Waiter<Session>[] = [];
    #consumed = 0;

    private constructor(server: Server, responder: Responder) {
        this.#server = server;
        this.responder = responder;
    }

    static async start(responder: Responder = okToEverything): Promise<FakeServer> {
        const server = createServer();
        const fake = new FakeServer(server, responder);
        server.on("connection", (socket) => {
            fake.accept(socket);
        });
        await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
        return fake;
    }

    get port(): number {
        const address = this.#server.address();
        return typeof address === "object" && address !== null ? address.port : 0;
    }

    get hosts(): { host: string; port: number }[] {
        return [{ host: "127.0.0.1", port: this.port }];
    }

    accept(socket: Socket): void {
        const session = new Session(socket);
        this.sessions.push(session);
        this.#notify(this.#sessionWaiters, session);
        let buffered = "";
        socket.on("error", () => undefined);
        socket.on("data", (chunk: Buffer) => {
            buffered += chunk.toString("utf8");
            let newline = buffered.indexOf("\n");
            while (newline !== -1) {
                const line = buffered.slice(0, newline);
                buffered = buffered.slice(newline + 1);
                const request = FakeServer.parse(line);
                newline = buffered.indexOf("\n");
                if (request === undefined) {
                    continue;
                }
                session.requests.push(request);
                this.requests.push(request);
                this.#notify(this.#requestWaiters, request);
                this.responder(request, session);
            }
        });
    }

    static parse(line: string): JsonObject | undefined {
        try {
            const parsed: unknown = JSON.parse(line);
            return typeof parsed === "object" && parsed !== null ? (parsed as JsonObject) : undefined;
        } catch {
            return undefined;
        }
    }

    awaitSession(index: number): Promise<Session> {
        const existing = this.sessions[index];
        if (existing !== undefined) {
            return Promise.resolve(existing);
        }
        return new Promise((resolve) => {
            this.#sessionWaiters.push({
                matches: () => this.sessions.length > index,
                resolve: () => {
                    const session = this.sessions[index];
                    if (session !== undefined) {
                        resolve(session);
                    }
                },
            });
        });
    }

    nextRequestOfType(type: string): Promise<JsonObject> {
        const found = this.requests.slice(this.#consumed).find((request) => request.type === type);
        if (found !== undefined) {
            this.#consumed = this.requests.indexOf(found) + 1;
            return Promise.resolve(found);
        }
        return new Promise((resolve) => {
            this.#requestWaiters.push({
                matches: (request) => request.type === type,
                resolve: (request) => {
                    this.#consumed = this.requests.indexOf(request) + 1;
                    resolve(request);
                },
            });
        });
    }

    requestsOfType(type: string): JsonObject[] {
        return this.requests.filter((request) => request.type === type);
    }

    async close(): Promise<void> {
        this.sessions.forEach((session) => {
            session.close();
        });
        await new Promise<void>((resolve) =>
            this.#server.close(() => {
                resolve();
            }),
        );
    }

    #notify<V>(waiters: Waiter<V>[], value: V): void {
        for (const waiter of [...waiters]) {
            if (waiter.matches(value)) {
                waiters.splice(waiters.indexOf(waiter), 1);
                waiter.resolve(value);
            }
        }
    }
}
