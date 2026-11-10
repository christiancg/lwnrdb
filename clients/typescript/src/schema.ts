import type { Document } from "./document.js";
import { NotFoundError } from "./errors/errors.js";
import type { ClientContext } from "./internal/client-context.js";
import { document, strings } from "./internal/response-decoder.js";

export class Schema {
    readonly #context: ClientContext;
    readonly #database: string;
    readonly #collection: string;

    constructor(context: ClientContext, database: string, collection: string) {
        this.#context = context;
        this.#database = database;
        this.#collection = collection;
    }

    async save(schema: Document): Promise<string[]> {
        const encoder = this.#context.encoder;
        const reply = await this.#context.channel.send(
            "SAVE_SCHEMA",
            encoder.saveSchema(this.#database, this.#collection, encoder.document(schema)),
        );
        return strings(reply.warnings);
    }

    async get(): Promise<Document | undefined> {
        try {
            const reply = await this.#context.channel.send(
                "GET_SCHEMA",
                this.#context.encoder.getSchema(this.#database, this.#collection),
            );
            return document(reply, "schema");
        } catch (error) {
            if (error instanceof NotFoundError) {
                return undefined;
            }
            throw error;
        }
    }

    async delete(): Promise<void> {
        await this.#context.channel.send(
            "DELETE_SCHEMA",
            this.#context.encoder.deleteSchema(this.#database, this.#collection),
        );
    }
}
