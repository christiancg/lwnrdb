import type { Document } from "../document.js";
import { NotFoundError } from "../errors/errors.js";
import { ErrorCode } from "../errors/error-codes.js";
import type { Filter, PipelineStep } from "../query/types.js";
import type { AnalyzeResult, BulkSaveResult, SaveResult } from "../results.js";
import type { ClientContext } from "./client-context.js";
import { collectionName, databaseName, documentId } from "./names.js";
import { booleanOf, document, documents, numberOf, strings, textOf } from "./response-decoder.js";
import type { JsonObject } from "./values-codec.js";

export interface RowCodec<T, R> {
    encode(value: T): unknown;
    decode(document: Document): R;
}

export interface QueryTarget<R> {
    readonly codec: RowCodec<never, R>;
    aggregate(steps: readonly PipelineStep[], dirtyRead: boolean): Promise<Document[]>;
    analyze(steps: readonly PipelineStep[], dirtyRead: boolean): Promise<AnalyzeResult>;
}

export async function emptyOn<V>(code: string, empty: V, read: () => Promise<V>): Promise<V> {
    try {
        return await read();
    } catch (error) {
        if (error instanceof NotFoundError && error.hasCode(code)) {
            return empty;
        }
        throw error;
    }
}

export class CollectionOps<T, R> implements QueryTarget<R> {
    readonly context: ClientContext;
    readonly database: string;
    readonly collection: string;
    readonly codec: RowCodec<T, R>;

    constructor(context: ClientContext, database: string, collection: string, codec: RowCodec<T, R>) {
        this.context = context;
        this.database = databaseName(database);
        this.collection = collectionName(collection);
        this.codec = codec;
    }

    async save(value: T): Promise<SaveResult> {
        const reply = await this.context.channel.send(
            "SAVE",
            this.context.encoder.save(this.database, this.collection, this.#encode(value)),
        );
        return { id: textOf(reply._id), inserted: booleanOf(reply.inserted, false) };
    }

    async insert(value: T): Promise<SaveResult> {
        const reply = await this.context.channel.send(
            "SAVE",
            this.context.encoder.insert(this.database, this.collection, this.#encode(value)),
        );
        return { id: textOf(reply._id), inserted: booleanOf(reply.inserted, false) };
    }

    async saveAll(values: readonly T[]): Promise<BulkSaveResult> {
        const encoded = values.map((value) => this.#encode(value));
        const reply = await this.context.channel.send(
            "BULK_SAVE",
            this.context.encoder.bulkSave(this.database, this.collection, encoded),
        );
        return { inserted: strings(reply.inserted), updated: strings(reply.updated) };
    }

    async findById(id: string, dirtyRead: boolean): Promise<R | undefined> {
        const request = this.context.encoder.findById(this.database, this.collection, documentId(id), dirtyRead);
        return emptyOn(ErrorCode.ENTRY_NOT_FOUND, undefined, async () => {
            const found = document(await this.context.channel.send("FIND_BY_ID", request), "object");
            return found === undefined ? undefined : this.codec.decode(found);
        });
    }

    async delete(id: string): Promise<void> {
        await this.context.channel.send(
            "DELETE",
            this.context.encoder.delete(this.database, this.collection, documentId(id)),
        );
    }

    async deleteWhere(condition: Filter): Promise<number> {
        const reply = await this.context.channel.send(
            "DELETE",
            this.context.encoder.deleteWhere(this.database, this.collection, condition),
        );
        return numberOf(reply.deletedCount) ?? 0;
    }

    async updateWhere(condition: Filter, patch: Record<string, unknown>): Promise<number> {
        const reply = await this.context.channel.send(
            "SAVE",
            this.context.encoder.updateWhere(
                this.database,
                this.collection,
                condition,
                this.context.encoder.document(patch),
            ),
        );
        return numberOf(reply.updatedCount) ?? 0;
    }

    async aggregate(steps: readonly PipelineStep[], dirtyRead: boolean): Promise<Document[]> {
        const request = this.context.encoder.aggregate(this.database, this.collection, steps, dirtyRead, false);
        return emptyOn(ErrorCode.NO_RESULTS, [], async () =>
            documents(await this.context.channel.send("AGGREGATE", request), "results"),
        );
    }

    async analyze(steps: readonly PipelineStep[], dirtyRead: boolean): Promise<AnalyzeResult> {
        const reply = await this.context.channel.send(
            "AGGREGATE",
            this.context.encoder.aggregate(this.database, this.collection, steps, dirtyRead, true),
        );
        return { results: documents(reply, "results"), analysis: document(reply, "analyzeResult") ?? {} };
    }

    #encode(value: T): JsonObject {
        const encoded = this.context.encoder.document(this.codec.encode(value));
        const id = encoded._id;
        if (id !== undefined) {
            documentId(id);
        }
        return encoded;
    }
}
