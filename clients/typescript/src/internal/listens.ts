import type { Document } from "../document.js";
import { LwnrdbError } from "../errors/errors.js";
import { ErrorCode } from "../errors/error-codes.js";
import type { PipelineStep } from "../query/types.js";
import type { ListenHandler, Subscription } from "../subscription.js";
import type { ConnectionFactory } from "./connection-factory.js";
import { ListenChannel } from "./listen-channel.js";
import type { RequestEncoder } from "./request-encoder.js";

export class Listens {
    readonly #factory: ConnectionFactory;
    readonly #encoder: RequestEncoder;
    readonly #requestTimeoutMs: number;
    readonly #channels: ListenChannel[] = [];
    #queue: Promise<unknown> = Promise.resolve();

    constructor(factory: ConnectionFactory, encoder: RequestEncoder, requestTimeoutMs: number) {
        this.#factory = factory;
        this.#encoder = encoder;
        this.#requestTimeoutMs = requestTimeoutMs;
    }

    get openChannels(): number {
        return this.#channels.filter((channel) => channel.isOpen).length;
    }

    listen<R>(
        database: string,
        collection: string,
        steps: readonly PipelineStep[],
        decode: (document: Document) => R,
        handler: ListenHandler<R>,
    ): Promise<Subscription> {
        const request = this.#encoder.listen(database, collection, steps);
        const next = this.#queue.then(() => this.#register(request, decode, handler));
        this.#queue = next.catch(() => undefined);
        return next;
    }

    async close(): Promise<void> {
        const channels = this.#channels.splice(0);
        await Promise.all(channels.map((channel) => channel.close()));
    }

    async #register<R>(
        request: ReturnType<RequestEncoder["listen"]>,
        decode: (document: Document) => R,
        handler: ListenHandler<R>,
    ): Promise<Subscription> {
        for (const channel of [...this.#channels]) {
            if (channel.isOpen) {
                try {
                    const subscription = await channel.register(request, decode, handler);
                    if (subscription !== undefined) {
                        return subscription;
                    }
                } catch (error) {
                    if (!(error instanceof LwnrdbError && error.hasCode(ErrorCode.TOO_MANY_LISTENS))) {
                        throw error;
                    }
                }
            }
        }
        const channel = new ListenChannel(await this.#factory.open(), this.#encoder, this.#requestTimeoutMs, (lost) => {
            this.#remove(lost);
        });
        this.#channels.push(channel);
        const subscription = await channel.register(request, decode, handler).catch(async (error: unknown) => {
            await this.#discard(channel);
            throw error;
        });
        if (subscription === undefined) {
            await this.#discard(channel);
            throw new LwnrdbError("a new listen connection closed before the listen registered");
        }
        return subscription;
    }

    async #discard(channel: ListenChannel): Promise<void> {
        this.#remove(channel);
        await channel.close();
    }

    #remove(channel: ListenChannel): void {
        const index = this.#channels.indexOf(channel);
        if (index !== -1) {
            this.#channels.splice(index, 1);
        }
    }
}
