import { ConnectionLostError, PoolExhaustedError } from "../errors/errors.js";
import type { ConnectionFactory } from "./connection-factory.js";
import type { WireConnection } from "./wire-connection.js";

interface Waiter {
    readonly grant: () => void;
    readonly refuse: (error: Error) => void;
}

export class Lease {
    readonly connection: WireConnection;
    readonly #pool: ConnectionPool;
    #discard = false;
    #released = false;

    constructor(connection: WireConnection, pool: ConnectionPool) {
        this.connection = connection;
        this.#pool = pool;
    }

    discard(): void {
        this.#discard = true;
    }

    release(): void {
        if (this.#released) {
            return;
        }
        this.#released = true;
        this.#pool.giveBack(this.connection, this.#discard);
    }
}

export class ConnectionPool {
    readonly #factory: ConnectionFactory;
    readonly #size: number;
    readonly #acquireTimeoutMs: number;
    readonly #shutdownTimeoutMs: number;
    readonly #idle: WireConnection[] = [];
    readonly #waiters: Waiter[] = [];
    #inUse = 0;
    #closed = false;
    #onDrained: (() => void) | undefined;

    constructor(factory: ConnectionFactory) {
        this.#factory = factory;
        this.#size = factory.settings.poolSize;
        this.#acquireTimeoutMs = factory.settings.acquireTimeoutMs;
        this.#shutdownTimeoutMs = factory.settings.shutdownTimeoutMs;
    }

    get idleConnections(): number {
        return this.#idle.length;
    }

    get connectionsInUse(): number {
        return this.#inUse;
    }

    async borrow(): Promise<Lease> {
        if (this.#closed) {
            throw new ConnectionLostError("the client is closed");
        }
        await this.#acquirePermit();
        try {
            return new Lease(await this.#connection(), this);
        } catch (error) {
            this.#releasePermit();
            throw error;
        }
    }

    giveBack(connection: WireConnection, discard: boolean): void {
        if (discard || this.#closed || !connection.isOpen) {
            connection.close();
        } else {
            this.#idle.push(connection);
        }
        this.#releasePermit();
    }

    async close(): Promise<void> {
        if (this.#closed) {
            return;
        }
        this.#closed = true;
        this.#waiters.splice(0).forEach((waiter) => {
            waiter.refuse(new ConnectionLostError("the client is closed"));
        });
        await this.#drainIdle();
        await this.#awaitInUse();
        await this.#drainIdle();
    }

    async #connection(): Promise<WireConnection> {
        for (let connection = this.#idle.pop(); connection !== undefined; connection = this.#idle.pop()) {
            if (connection.isOpen) {
                return connection;
            }
        }
        return this.#factory.open();
    }

    #acquirePermit(): Promise<void> {
        if (this.#inUse < this.#size) {
            this.#inUse++;
            return Promise.resolve();
        }
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                const index = this.#waiters.indexOf(waiter);
                if (index !== -1) {
                    this.#waiters.splice(index, 1);
                }
                reject(
                    new PoolExhaustedError(
                        `no connection became free within ${this.#acquireTimeoutMs} ms; all ${this.#size} are in use`,
                    ),
                );
            }, this.#acquireTimeoutMs);
            const waiter: Waiter = {
                grant: () => {
                    clearTimeout(timer);
                    resolve();
                },
                refuse: (error) => {
                    clearTimeout(timer);
                    reject(error);
                },
            };
            this.#waiters.push(waiter);
        });
    }

    #releasePermit(): void {
        const next = this.#waiters.shift();
        if (next !== undefined) {
            next.grant();
            return;
        }
        this.#inUse--;
        if (this.#inUse === 0) {
            this.#onDrained?.();
        }
    }

    async #awaitInUse(): Promise<void> {
        if (this.#inUse === 0) {
            return;
        }
        let timer: NodeJS.Timeout | undefined;
        await new Promise<void>((resolve) => {
            this.#onDrained = resolve;
            timer = setTimeout(resolve, this.#shutdownTimeoutMs);
        });
        clearTimeout(timer);
        this.#onDrained = undefined;
    }

    async #drainIdle(): Promise<void> {
        const idle = this.#idle.splice(0);
        await Promise.all(idle.map((connection) => connection.closeGracefully(this.#shutdownTimeoutMs)));
    }
}
