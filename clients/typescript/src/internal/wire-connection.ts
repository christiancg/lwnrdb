import type { Socket } from "node:net";
import {
    ConnectionLostError,
    LwnrdbError,
    RequestTimeoutError,
    ServerClosedConnectionError,
} from "../errors/errors.js";
import { Status } from "../protocol/status.js";
import { Route, route } from "./frame-router.js";
import { LineReader, type LineEvent } from "./line-reader.js";
import { textOf } from "./response-decoder.js";
import type { JsonObject } from "./values-codec.js";

interface PendingReply {
    readonly resolve: (frame: JsonObject) => void;
    readonly reject: (error: LwnrdbError) => void;
    readonly awaitsClose: boolean;
}

export type PushSink = (frame: JsonObject) => void;
export type CloseListener = (cause: LwnrdbError) => void;

const CLOSE_CONNECTION = "CLOSE_CONNECTION";

function isJsonObject(value: unknown): value is JsonObject {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

export class WireConnection {
    readonly #socket: Socket;
    readonly #reader: LineReader;
    readonly #pending: PendingReply[] = [];
    readonly #label: string;
    #pushSink: PushSink = () => undefined;
    #closeListener: CloseListener = () => undefined;
    #closeCause: LwnrdbError | undefined;
    #held = false;

    constructor(socket: Socket, maxReplyBytes: number) {
        this.#socket = socket;
        this.#reader = new LineReader(maxReplyBytes);
        this.#label = `${socket.remoteAddress ?? "server"}:${socket.remotePort ?? ""}`;
        socket.on("data", (chunk: Buffer) => {
            this.#guarded(() => {
                this.#reader.push(chunk).forEach((event) => {
                    this.#onLine(event);
                });
            });
        });
        socket.on("error", (error) => {
            this.close(
                new ConnectionLostError(`lost the connection to ${this.#label}: ${error.message}`, { cause: error }),
            );
        });
        socket.on("close", () => {
            this.close(new ConnectionLostError("the server closed the connection"));
        });
        this.#updateRef();
    }

    get isOpen(): boolean {
        return this.#closeCause === undefined;
    }

    onPush(sink: PushSink): void {
        this.#pushSink = sink;
    }

    onClose(listener: CloseListener): void {
        this.#closeListener = listener;
    }

    hold(held: boolean): void {
        this.#held = held;
        this.#updateRef();
    }

    send(request: JsonObject): Promise<JsonObject> {
        return new Promise((resolve, reject) => {
            if (this.#closeCause !== undefined) {
                reject(this.#closeCause);
                return;
            }
            this.#pending.push({ resolve, reject, awaitsClose: request.type === CLOSE_CONNECTION });
            this.#updateRef();
            this.#socket.write(`${JSON.stringify(request)}\n`, (error) => {
                if (error) {
                    this.close(
                        new ConnectionLostError(`could not send the request to ${this.#label}`, { cause: error }),
                    );
                }
            });
        });
    }

    async request(request: JsonObject, timeoutMs: number): Promise<JsonObject> {
        let timer: NodeJS.Timeout | undefined;
        const timeout = new Promise<never>((_, reject) => {
            timer = setTimeout(() => {
                const timedOut = new RequestTimeoutError(
                    `no reply within ${timeoutMs} ms; the connection was closed, so the request's outcome is unknown`,
                );
                this.close(timedOut);
                reject(timedOut);
            }, timeoutMs);
        });
        try {
            return await Promise.race([this.send(request), timeout]);
        } finally {
            clearTimeout(timer);
        }
    }

    async closeGracefully(timeoutMs: number): Promise<void> {
        if (!this.isOpen) {
            return;
        }
        try {
            await this.request({ type: CLOSE_CONNECTION }, timeoutMs);
        } catch {
            this.close();
            return;
        }
        this.close();
    }

    close(cause: LwnrdbError = new ConnectionLostError("connection closed by the client")): void {
        if (this.#closeCause !== undefined) {
            return;
        }
        this.#closeCause = cause;
        this.#socket.destroy();
        this.#pending.splice(0).forEach((reply) => {
            reply.reject(cause);
        });
        this.#closeListener(cause);
    }

    #updateRef(): void {
        if (this.#held || this.#pending.length > 0) {
            this.#socket.ref();
        } else {
            this.#socket.unref();
        }
    }

    #guarded(task: () => void): void {
        try {
            task();
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            this.close(new ConnectionLostError(`the reply reader failed: ${message}`, { cause: error }));
        }
    }

    #onLine(event: LineEvent): void {
        if (event.tooLarge) {
            this.#failHead(new LwnrdbError("the reply exceeded maxReplyBytes and was discarded"));
            return;
        }
        if (event.text.length === 0) {
            return;
        }
        let frame: unknown;
        try {
            frame = JSON.parse(event.text);
        } catch {
            frame = undefined;
        }
        if (!isJsonObject(frame)) {
            this.#failHead(new LwnrdbError(`the server refused the request: ${event.text}`));
            return;
        }
        this.#dispatch(frame);
    }

    #dispatch(frame: JsonObject): void {
        switch (route(frame, this.#pending[0]?.awaitsClose ?? false)) {
            case Route.PUSH:
                this.#pushSink(frame);
                return;
            case Route.SERVER_CLOSE:
                this.close(
                    new ServerClosedConnectionError(
                        `the server closed the connection: ${textOf(frame.message) ?? ""}`,
                        { code: textOf(frame.errorCode), status: Status.ERROR },
                    ),
                );
                return;
            case Route.RESPONSE:
                this.#completeHead(frame);
        }
    }

    #completeHead(frame: JsonObject): void {
        const head = this.#pending.shift();
        if (head === undefined) {
            this.close(new ConnectionLostError(`the server sent a reply nobody asked for: ${JSON.stringify(frame)}`));
            return;
        }
        this.#updateRef();
        head.resolve(frame);
    }

    #failHead(cause: LwnrdbError): void {
        const head = this.#pending.shift();
        this.#updateRef();
        head?.reject(cause);
    }
}
