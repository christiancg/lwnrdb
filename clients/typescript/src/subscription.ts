import type { ListenEndedError } from "./errors/errors.js";

export interface ListenUpdate<R> {
    readonly listenId: string;
    readonly results: readonly R[];
    readonly resultHash: string;
    readonly initial: boolean;
}

export interface ListenHandler<R> {
    onUpdate(update: ListenUpdate<R>): void | Promise<void>;
    onEnded?(reason: ListenEndedError): void | Promise<void>;
    onError?(error: unknown): void | Promise<void>;
}

export interface Subscription extends AsyncDisposable {
    readonly listenId: string;
    readonly isActive: boolean;
    close(): Promise<void>;
}
