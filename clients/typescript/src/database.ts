import { Collection } from "./collection.js";
import type { Document, DocumentCodec, Stored } from "./document.js";
import type { ClientContext } from "./internal/client-context.js";
import { identityCodec, rowCodec } from "./internal/codecs.js";
import { CollectionOps, type RowCodec } from "./internal/collection-ops.js";
import { scriptResult, trigger } from "./internal/descriptors.js";
import { databaseName } from "./internal/names.js";
import { document, documents, strings } from "./internal/response-decoder.js";
import type { ScriptResult } from "./results.js";
import type { TriggerInfo } from "./scripting/definitions.js";
import { Procedures } from "./scripting/procedures.js";
import { Schedules } from "./scripting/schedules.js";

export class Database {
    readonly name: string;
    readonly #context: ClientContext;

    constructor(context: ClientContext, name: string) {
        this.#context = context;
        this.name = databaseName(name);
    }

    async create(): Promise<void> {
        await this.#context.channel.send("CREATE_DATABASE", this.#context.encoder.createDatabase(this.name));
    }

    async drop(): Promise<void> {
        await this.#context.channel.send("DROP_DATABASE", this.#context.encoder.dropDatabase(this.name));
    }

    async listCollections(): Promise<string[]> {
        const reply = await this.#context.channel.send(
            "LIST_COLLECTIONS",
            this.#context.encoder.listCollections(this.name),
        );
        return strings(reply.collections);
    }

    collection<T extends object = Document>(name: string): Collection<T, Stored<T>>;
    collection<T, R>(name: string, codec: DocumentCodec<T, R>): Collection<T, R>;
    collection<T, R>(name: string, codec?: DocumentCodec<T, R>): Collection<T, R | Stored<T>> {
        const rows: RowCodec<T, R | Stored<T>> = codec === undefined ? identityCodec<T>() : rowCodec(codec);
        return new Collection<T, R | Stored<T>>(new CollectionOps(this.#context, this.name, name, rows));
    }

    async stats(): Promise<Document> {
        const reply = await this.#context.channel.send(
            "GET_DATABASE_STATS",
            this.#context.encoder.databaseStats(this.name),
        );
        return document(reply, "stats") ?? {};
    }

    async setOwners(owners: readonly string[]): Promise<void> {
        await this.#context.channel.send(
            "SET_DATABASE_OWNERS",
            this.#context.encoder.setDatabaseOwners(this.name, owners),
        );
    }

    async runScript(script: string, args?: Document): Promise<ScriptResult> {
        return scriptResult(
            await this.#context.channel.send("RUN_SCRIPT", this.#context.scripts.runScript(this.name, script, args)),
        );
    }

    procedures(): Procedures {
        return new Procedures(this.#context, this.name);
    }

    schedules(): Schedules {
        return new Schedules(this.#context, this.name);
    }

    async listTriggers(): Promise<TriggerInfo[]> {
        const reply = await this.#context.channel.send(
            "LIST_TRIGGERS",
            this.#context.scripts.listTriggers(this.name, undefined),
        );
        return documents(reply, "triggers").map(trigger);
    }
}
