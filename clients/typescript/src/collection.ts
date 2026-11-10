import type { CollectionOps } from "./internal/collection-ops.js";
import { Indexes } from "./indexes.js";
import { Query } from "./query/query.js";
import type { BulkSaveResult, ReadOptions, SaveResult } from "./results.js";
import { Schema } from "./schema.js";
import { Triggers } from "./scripting/triggers.js";
import type { ListenHandler, Subscription } from "./subscription.js";
import { LwnrdbError } from "./errors/errors.js";

export class Collection<T, R> {
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

    async create(): Promise<void> {
        await this.#ops.context.channel.send(
            "CREATE_COLLECTION",
            this.#ops.context.encoder.createCollection(this.#ops.database, this.#ops.collection),
        );
    }

    async drop(): Promise<void> {
        await this.#ops.context.channel.send(
            "DROP_COLLECTION",
            this.#ops.context.encoder.dropCollection(this.#ops.database, this.#ops.collection),
        );
    }

    save(value: T): Promise<SaveResult> {
        return this.#ops.save(value);
    }

    saveAll(values: readonly T[]): Promise<BulkSaveResult> {
        return this.#ops.saveAll(values);
    }

    findById(id: string, options: ReadOptions = {}): Promise<R | undefined> {
        return this.#ops.findById(id, options.dirtyRead === true);
    }

    delete(id: string): Promise<void> {
        return this.#ops.delete(id);
    }

    query(): Query<R> {
        return new Query(this.#ops);
    }

    indexes(): Indexes {
        return new Indexes(this.#ops.context, this.#ops.database, this.#ops.collection);
    }

    schema(): Schema {
        return new Schema(this.#ops.context, this.#ops.database, this.#ops.collection);
    }

    triggers(): Triggers {
        return new Triggers(this.#ops.context, this.#ops.database, this.#ops.collection);
    }

    listen(handler: ListenHandler<R>): Promise<Subscription>;
    listen(query: Query<R>, handler: ListenHandler<R>): Promise<Subscription>;
    listen(queryOrHandler: Query<R> | ListenHandler<R>, maybeHandler?: ListenHandler<R>): Promise<Subscription> {
        const query = queryOrHandler instanceof Query ? queryOrHandler : this.query();
        const handler = queryOrHandler instanceof Query ? maybeHandler : queryOrHandler;
        const listens = this.#ops.context.listens;
        if (handler === undefined || listens === undefined) {
            return Promise.reject(
                new LwnrdbError(handler === undefined ? "listen needs a handler" : "listens are not available here"),
            );
        }
        return listens.listen(
            this.#ops.database,
            this.#ops.collection,
            query.steps,
            (document) => this.#ops.codec.decode(document),
            handler,
        );
    }
}
