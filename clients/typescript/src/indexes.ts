import type { ClientContext } from "./internal/client-context.js";
import { numberOf, strings } from "./internal/response-decoder.js";
import type { JsonObject } from "./internal/values-codec.js";
import type { ReindexResult } from "./results.js";

function result(reply: JsonObject): ReindexResult {
    return {
        rebuiltFields: strings(reply.rebuiltFields),
        primaryKeyEntries: numberOf(reply.primaryKeyEntries),
        unversionedEntries: numberOf(reply.unversionedEntries),
    };
}

export class Indexes {
    readonly #context: ClientContext;
    readonly #database: string;
    readonly #collection: string;

    constructor(context: ClientContext, database: string, collection: string) {
        this.#context = context;
        this.#database = database;
        this.#collection = collection;
    }

    async create(field: string, ...moreFields: string[]): Promise<void> {
        await this.#context.channel.send(
            "CREATE_INDEX",
            this.#context.encoder.createIndex(this.#database, this.#collection, [field, ...moreFields]),
        );
    }

    async drop(field: string): Promise<void> {
        await this.#context.channel.send(
            "DROP_INDEX",
            this.#context.encoder.dropIndex(this.#database, this.#collection, field),
        );
    }

    async reindex(...fields: string[]): Promise<ReindexResult> {
        return result(
            await this.#context.channel.send(
                "REINDEX",
                this.#context.encoder.reindex(this.#database, this.#collection, fields),
            ),
        );
    }

    async reindexPrimaryKey(): Promise<ReindexResult> {
        return result(
            await this.#context.channel.send(
                "REINDEX",
                this.#context.encoder.reindexPrimaryKey(this.#database, this.#collection),
            ),
        );
    }
}
