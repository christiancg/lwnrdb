import type { Document } from "../document.js";
import type { ListenEndedError, LwnrdbError } from "../errors/errors.js";
import type { ListenHandler, Subscription } from "../subscription.js";
import { isListenEnd } from "./frame-router.js";
import type { RequestEncoder } from "./request-encoder.js";
import { check, documents, raise, textOf } from "./response-decoder.js";
import type { JsonObject } from "./values-codec.js";
import type { WireConnection } from "./wire-connection.js";

const LISTEN_ID = "listenId";

class Registration<R> {
    readonly listenId: string;
    readonly subscription: ListenSubscription;
    readonly #decode: (document: Document) => R;
    readonly #handler: ListenHandler<R>;

    constructor(
        listenId: string,
        subscription: ListenSubscription,
        decode: (document: Document) => R,
        handler: ListenHandler<R>,
    ) {
        this.listenId = listenId;
        this.subscription = subscription;
        this.#decode = decode;
        this.#handler = handler;
    }

    async update(frame: JsonObject, initial: boolean): Promise<void> {
        try {
            const results = documents(frame, "results").map(this.#decode);
            await this.#handler.onUpdate({
                listenId: this.listenId,
                results,
                resultHash: textOf(frame.resultHash) ?? "",
                initial,
            });
        } catch (error) {
            await this.failed(error);
        }
    }

    async ended(reason: ListenEndedError): Promise<void> {
        try {
            await this.#handler.onEnded?.(reason);
        } catch (error) {
            await this.failed(error);
        }
    }

    async failed(error: unknown): Promise<void> {
        try {
            await this.#handler.onError?.(error);
        } catch {
            return;
        }
    }
}

export class ListenSubscription implements Subscription {
    readonly listenId: string;
    readonly #channel: ListenChannel;
    #closed = false;

    constructor(listenId: string, channel: ListenChannel) {
        this.listenId = listenId;
        this.#channel = channel;
    }

    get isActive(): boolean {
        return !this.#closed && this.#channel.isOpen;
    }

    markEnded(): void {
        this.#closed = true;
    }

    async close(): Promise<void> {
        if (this.#closed) {
            return;
        }
        this.#closed = true;
        if (this.#channel.isOpen) {
            await this.#channel.stop(this.listenId);
        }
    }

    async [Symbol.asyncDispose](): Promise<void> {
        await this.close();
    }
}

export class ListenChannel {
    readonly #connection: WireConnection;
    readonly #encoder: RequestEncoder;
    readonly #requestTimeoutMs: number;
    readonly #registrations = new Map<string, Registration<unknown>>();
    readonly #early = new Map<string, JsonObject[]>();
    #tail: Promise<void> = Promise.resolve();
    #active = 0;
    #closing = false;
    #stopped = false;

    constructor(
        connection: WireConnection,
        encoder: RequestEncoder,
        requestTimeoutMs: number,
        onLost: (channel: ListenChannel) => void,
    ) {
        this.#connection = connection;
        this.#encoder = encoder;
        this.#requestTimeoutMs = requestTimeoutMs;
        connection.hold(true);
        connection.onPush((frame) => {
            this.#dispatch(() => this.#deliver(frame));
        });
        connection.onClose((cause) => {
            this.#dispatch(() => this.#lost(cause));
            onLost(this);
        });
    }

    get isOpen(): boolean {
        return !this.#closing && this.#connection.isOpen;
    }

    async register<R>(
        request: JsonObject,
        decode: (document: Document) => R,
        handler: ListenHandler<R>,
    ): Promise<ListenSubscription | undefined> {
        if (!this.#reserve()) {
            return undefined;
        }
        let reply: JsonObject;
        try {
            reply = check(await this.#connection.request(request, this.#requestTimeoutMs));
        } catch (error) {
            await this.#release();
            throw error;
        }
        const listenId = textOf(reply[LISTEN_ID]) ?? "";
        const subscription = new ListenSubscription(listenId, this);
        const registration = new Registration(listenId, subscription, decode, handler);
        this.#dispatch(() => this.#activate(registration, reply));
        return subscription;
    }

    async stop(listenId: string): Promise<void> {
        try {
            check(await this.#connection.request(this.#encoder.stopListen(listenId), this.#requestTimeoutMs));
        } finally {
            this.#dispatch(() => {
                this.#registrations.delete(listenId);
            });
            await this.#release();
        }
    }

    async close(): Promise<void> {
        this.#closing = true;
        await this.#connection.closeGracefully(this.#requestTimeoutMs);
        this.#stopped = true;
    }

    #dispatch(task: () => void | Promise<void>): void {
        if (this.#stopped) {
            return;
        }
        this.#tail = this.#tail.then(task).catch(() => undefined);
    }

    #reserve(): boolean {
        if (this.#closing || !this.#connection.isOpen) {
            return false;
        }
        this.#active++;
        return true;
    }

    async #release(): Promise<void> {
        this.#active--;
        if (this.#active === 0 && !this.#closing) {
            await this.close();
        }
    }

    async #activate(registration: Registration<unknown>, initial: JsonObject): Promise<void> {
        this.#registrations.set(registration.listenId, registration);
        await registration.update(initial, true);
        const buffered = this.#early.get(registration.listenId) ?? [];
        this.#early.delete(registration.listenId);
        for (const frame of buffered) {
            await this.#deliver(frame);
        }
    }

    async #deliver(frame: JsonObject): Promise<void> {
        const listenId = textOf(frame[LISTEN_ID]) ?? "";
        const registration = this.#registrations.get(listenId);
        if (registration === undefined) {
            this.#early.set(listenId, [...(this.#early.get(listenId) ?? []), frame]);
            return;
        }
        if (isListenEnd(frame)) {
            this.#registrations.delete(listenId);
            registration.subscription.markEnded();
            await registration.ended(raise(frame));
            await this.#release();
            return;
        }
        if (registration.subscription.isActive) {
            await registration.update(frame, false);
        }
    }

    async #lost(cause: LwnrdbError): Promise<void> {
        this.#closing = true;
        const lost = [...this.#registrations.values()];
        this.#registrations.clear();
        this.#early.clear();
        this.#stopped = true;
        for (const registration of lost) {
            registration.subscription.markEnded();
            await registration.failed(cause);
        }
    }
}
