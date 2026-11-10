import { ConnectionLostError, LwnrdbError, UnavailableError } from "../errors/errors.js";
import { RETRYABLE_CODES } from "../errors/error-codes.js";
import { operationDetails, type Operation } from "../protocol/operations.js";
import { backoffBefore, type RetryPolicy } from "../retry-policy.js";
import type { ConnectionPool } from "./connection-pool.js";
import { check } from "./response-decoder.js";
import type { JsonObject } from "./values-codec.js";
import type { WireConnection } from "./wire-connection.js";

export interface RequestChannel {
    send(operation: Operation, request: JsonObject): Promise<JsonObject>;
}

export type Backoff = (delayMs: number) => Promise<void>;

export const jitteredBackoff: Backoff = (delayMs) =>
    new Promise((resolve) => setTimeout(resolve, delayMs / 2 + Math.random() * (delayMs / 2)));

export function isRetryable(operation: Operation, error: unknown): boolean {
    if (error instanceof ConnectionLostError) {
        return operationDetails(operation).read;
    }
    return error instanceof UnavailableError && error.rawCode !== undefined && RETRYABLE_CODES.has(error.rawCode);
}

export class PooledChannel implements RequestChannel {
    readonly #pool: ConnectionPool;
    readonly #retryPolicy: RetryPolicy;
    readonly #requestTimeoutMs: number;
    readonly #backoff: Backoff;

    constructor(pool: ConnectionPool, retryPolicy: RetryPolicy, requestTimeoutMs: number, backoff: Backoff) {
        this.#pool = pool;
        this.#retryPolicy = retryPolicy;
        this.#requestTimeoutMs = requestTimeoutMs;
        this.#backoff = backoff;
    }

    async send(operation: Operation, request: JsonObject): Promise<JsonObject> {
        for (let attempt = 1; ; attempt++) {
            try {
                return await this.#once(request);
            } catch (error) {
                if (
                    !(error instanceof LwnrdbError) ||
                    attempt >= this.#retryPolicy.maxAttempts ||
                    !isRetryable(operation, error)
                ) {
                    throw error;
                }
                await this.#backoff(backoffBefore(this.#retryPolicy, attempt));
            }
        }
    }

    async #once(request: JsonObject): Promise<JsonObject> {
        const lease = await this.#pool.borrow();
        try {
            return check(await lease.connection.request(request, this.#requestTimeoutMs));
        } catch (error) {
            if (error instanceof ConnectionLostError) {
                lease.discard();
            }
            throw error;
        } finally {
            lease.release();
        }
    }
}

export class PinnedChannel implements RequestChannel {
    readonly #connection: WireConnection;
    readonly #requestTimeoutMs: number;

    constructor(connection: WireConnection, requestTimeoutMs: number) {
        this.#connection = connection;
        this.#requestTimeoutMs = requestTimeoutMs;
    }

    async send(operation: Operation, request: JsonObject): Promise<JsonObject> {
        if (!operationDetails(operation).allowedInTransaction) {
            throw new LwnrdbError(`${operation} is not allowed inside a transaction`, { operation });
        }
        return check(await this.#connection.request(request, this.#requestTimeoutMs));
    }
}
