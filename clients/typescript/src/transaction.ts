import type { Document, DocumentCodec, Stored } from "./document.js";
import { ConnectionLostError, LwnrdbError, UnknownOutcomeError } from "./errors/errors.js";
import { withChannel, type ClientContext } from "./internal/client-context.js";
import { identityCodec, rowCodec } from "./internal/codecs.js";
import { CollectionOps, type RowCodec } from "./internal/collection-ops.js";
import type { Lease } from "./internal/connection-pool.js";
import { PinnedChannel } from "./internal/request-channel.js";
import { textOf } from "./internal/response-decoder.js";
import { Query } from "./query/query.js";
import type { Filter } from "./query/types.js";
import type { BulkSaveResult, SaveResult } from "./results.js";

export class TransactionCollection<T, R> {
    readonly #ops: CollectionOps<T, R>;

    constructor(ops: CollectionOps<T, R>) {
        this.#ops = ops;
    }

    get databaseName(): string {
        return this.#ops.database;
    }

    get name(): string {
        return this.#ops.collection;
    }

    save(value: T): Promise<SaveResult> {
        return this.#ops.save(value);
    }

    saveAll(values: readonly T[]): Promise<BulkSaveResult> {
        return this.#ops.saveAll(values);
    }

    findById(id: string): Promise<R | undefined> {
        return this.#ops.findById(id, false);
    }

    delete(id: string): Promise<void> {
        return this.#ops.delete(id);
    }

    deleteWhere(condition: Filter): Promise<number> {
        return this.#ops.deleteWhere(condition);
    }

    updateWhere(condition: Filter, patch: Record<string, unknown>): Promise<number> {
        return this.#ops.updateWhere(condition, patch);
    }

    query(): Query<R> {
        return new Query(this.#ops);
    }
}

export class Transaction implements AsyncDisposable {
    readonly id: string;
    readonly #lease: Lease;
    readonly #context: ClientContext;
    #finished = false;

    private constructor(id: string, lease: Lease, context: ClientContext) {
        this.id = id;
        this.#lease = lease;
        this.#context = context;
    }

    static async start(lease: Lease, parent: ClientContext): Promise<Transaction> {
        const context = withChannel(parent, new PinnedChannel(lease.connection, parent.requestTimeoutMs));
        try {
            const reply = await context.channel.send("START_TRANSACTION", context.encoder.startTransaction());
            return new Transaction(textOf(reply.transactionId) ?? "", lease, context);
        } catch (error) {
            if (error instanceof ConnectionLostError) {
                lease.discard();
            }
            lease.release();
            throw error;
        }
    }

    get isOpen(): boolean {
        return !this.#finished;
    }

    collection<T extends object = Document>(database: string, collection: string): TransactionCollection<T, Stored<T>>;
    collection<T, R>(database: string, collection: string, codec: DocumentCodec<T, R>): TransactionCollection<T, R>;
    collection<T, R>(
        database: string,
        collection: string,
        codec?: DocumentCodec<T, R>,
    ): TransactionCollection<T, R | Stored<T>> {
        this.#requireOpen();
        const rows: RowCodec<T, R | Stored<T>> = codec === undefined ? identityCodec<T>() : rowCodec(codec);
        return new TransactionCollection<T, R | Stored<T>>(
            new CollectionOps(this.#context, database, collection, rows),
        );
    }

    commit(): Promise<void> {
        return this.#end("COMMIT_TRANSACTION");
    }

    rollback(): Promise<void> {
        return this.#end("ROLLBACK_TRANSACTION");
    }

    async close(): Promise<void> {
        if (this.isOpen) {
            await this.rollback();
        }
    }

    async [Symbol.asyncDispose](): Promise<void> {
        await this.close();
    }

    async #end(operation: "COMMIT_TRANSACTION" | "ROLLBACK_TRANSACTION"): Promise<void> {
        this.#requireOpen();
        this.#finished = true;
        const encoder = this.#context.encoder;
        try {
            await this.#context.channel.send(
                operation,
                operation === "COMMIT_TRANSACTION" ? encoder.commitTransaction() : encoder.rollbackTransaction(),
            );
        } catch (error) {
            if (error instanceof UnknownOutcomeError || error instanceof ConnectionLostError) {
                this.#lease.discard();
            }
            throw error;
        } finally {
            this.#lease.release();
        }
    }

    #requireOpen(): void {
        if (this.#finished) {
            throw new LwnrdbError("the transaction is already committed or rolled back");
        }
    }
}
