import { Admin } from "./admin/admin.js";
import type { ServerInfo } from "./admin/types.js";
import { Database } from "./database.js";
import type { Document } from "./document.js";
import { LwnrdbError } from "./errors/errors.js";
import type { ClientContext } from "./internal/client-context.js";
import { ConnectionFactory, openSocket, type SocketOpener } from "./internal/connection-factory.js";
import { ConnectionPool } from "./internal/connection-pool.js";
import { server } from "./internal/descriptors.js";
import { Listens } from "./internal/listens.js";
import { jitteredBackoff, PooledChannel, type Backoff } from "./internal/request-channel.js";
import { RequestEncoder } from "./internal/request-encoder.js";
import { document, strings } from "./internal/response-decoder.js";
import { ScriptRequestEncoder } from "./internal/script-request-encoder.js";
import { resolveSettings, type ClientSettings, type ResolvedSettings } from "./settings.js";
import { Transaction } from "./transaction.js";

export interface ClientDependencies {
    readonly backoff?: Backoff;
    readonly opener?: SocketOpener;
}

export type TransactionBody<V> = (transaction: Transaction) => Promise<V> | V;

let connectClient: (settings: ClientSettings, dependencies: ClientDependencies) => Promise<LwnrdbClient>;

export function connectWith(settings: ClientSettings, dependencies: ClientDependencies): Promise<LwnrdbClient> {
    return connectClient(settings, dependencies);
}

export class LwnrdbClient implements AsyncDisposable {
    readonly settings: ResolvedSettings;
    readonly #pool: ConnectionPool;
    readonly #listens: Listens;
    readonly #context: ClientContext;

    private constructor(settings: ResolvedSettings, dependencies: ClientDependencies) {
        this.settings = settings;
        const encoder = new RequestEncoder({ temporalZone: settings.temporalZone });
        const factory = new ConnectionFactory(settings, encoder, dependencies.opener ?? openSocket);
        this.#pool = new ConnectionPool(factory);
        this.#listens = new Listens(factory, encoder, settings.requestTimeoutMs);
        this.#context = {
            channel: new PooledChannel(
                this.#pool,
                settings.retryPolicy,
                settings.requestTimeoutMs,
                dependencies.backoff ?? jitteredBackoff,
            ),
            encoder,
            scripts: new ScriptRequestEncoder(encoder),
            listens: this.#listens,
            username: settings.credentials?.username,
            requestTimeoutMs: settings.requestTimeoutMs,
        };
    }

    static {
        connectClient = async (settings, dependencies) => {
            const client = new LwnrdbClient(resolveSettings(settings), dependencies);
            try {
                const lease = await client.#pool.borrow();
                lease.release();
                return client;
            } catch (error) {
                await client.close();
                throw error;
            }
        };
    }

    static connect(settings: ClientSettings = {}): Promise<LwnrdbClient> {
        return connectClient(settings, {});
    }

    database(name: string): Database {
        return new Database(this.#context, name);
    }

    async listDatabases(): Promise<string[]> {
        const reply = await this.#context.channel.send("LIST_DATABASES", this.#context.encoder.listDatabases());
        return strings(reply.databases);
    }

    async serverInfo(): Promise<ServerInfo> {
        const reply = await this.#context.channel.send("SERVER_INFO", this.#context.encoder.serverInfo());
        return server(document(reply, "server") ?? {});
    }

    async databaseStats(): Promise<Document> {
        const reply = await this.#context.channel.send("GET_DATABASE_STATS", this.#context.encoder.databaseStats());
        return document(reply, "stats") ?? {};
    }

    async changePassword(currentPassword: string, newPassword: string): Promise<void> {
        const username = this.#context.username;
        if (username === undefined) {
            throw new LwnrdbError("the client has no credentials to change");
        }
        await this.#context.channel.send(
            "SET_PASSWORD",
            this.#context.scripts.setPassword(username, currentPassword, newPassword),
        );
    }

    admin(): Admin {
        return new Admin(this.#context);
    }

    async beginTransaction(): Promise<Transaction> {
        return Transaction.start(await this.#pool.borrow(), this.#context);
    }

    async transaction<V>(body: TransactionBody<V>): Promise<V> {
        const transaction = await this.beginTransaction();
        let result: V;
        try {
            result = await body(transaction);
        } catch (error) {
            await LwnrdbClient.#rollbackQuietly(transaction);
            throw error;
        }
        if (transaction.isOpen) {
            await transaction.commit();
        }
        return result;
    }

    async close(): Promise<void> {
        await this.#listens.close();
        await this.#pool.close();
    }

    async [Symbol.asyncDispose](): Promise<void> {
        await this.close();
    }

    static async #rollbackQuietly(transaction: Transaction): Promise<void> {
        if (!transaction.isOpen) {
            return;
        }
        await transaction.rollback().catch(() => undefined);
    }
}
