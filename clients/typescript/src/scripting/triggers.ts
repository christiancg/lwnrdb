import type { Document } from "../document.js";
import type { ClientContext } from "../internal/client-context.js";
import { trigger } from "../internal/descriptors.js";
import { booleanOf, document, documents, numberOf, strings, textOf } from "../internal/response-decoder.js";
import type { TriggerDefinition, TriggerEvent, TriggerInfo, TriggerSaved, TriggerTestResult } from "./definitions.js";

export class Triggers {
    readonly #context: ClientContext;
    readonly #database: string;
    readonly #collection: string;

    constructor(context: ClientContext, database: string, collection: string) {
        this.#context = context;
        this.#database = database;
        this.#collection = collection;
    }

    async save(definition: TriggerDefinition): Promise<TriggerSaved> {
        const reply = await this.#context.channel.send(
            "SAVE_TRIGGER",
            this.#context.scripts.saveTrigger(this.#database, this.#collection, definition),
        );
        return { version: numberOf(reply.version) ?? 0, definer: textOf(reply.definer) ?? "" };
    }

    async delete(name: string): Promise<void> {
        await this.#context.channel.send(
            "DELETE_TRIGGER",
            this.#context.scripts.deleteTrigger(this.#database, this.#collection, name),
        );
    }

    async list(): Promise<TriggerInfo[]> {
        const reply = await this.#context.channel.send(
            "LIST_TRIGGERS",
            this.#context.scripts.listTriggers(this.#database, this.#collection),
        );
        return documents(reply, "triggers").map(trigger);
    }

    async test(name: string, event: TriggerEvent, sample: Document): Promise<TriggerTestResult> {
        const reply = await this.#context.channel.send(
            "TEST_TRIGGER",
            this.#context.scripts.testTrigger(this.#database, this.#collection, name, event, sample),
        );
        return {
            decision: textOf(reply.decision) ?? "",
            document: document(reply, "document"),
            logs: strings(reply.logs),
            logsTruncated: booleanOf(reply.logsTruncated, false),
        };
    }
}
