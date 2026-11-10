import type { Document } from "../document.js";
import type { ClientContext } from "../internal/client-context.js";
import { procedure, scriptResult } from "../internal/descriptors.js";
import { documents, numberOf } from "../internal/response-decoder.js";
import type { ScriptResult } from "../results.js";
import type { ProcedureDefinition, ProcedureInfo } from "./definitions.js";

export class Procedures {
    readonly #context: ClientContext;
    readonly #database: string;

    constructor(context: ClientContext, database: string) {
        this.#context = context;
        this.#database = database;
    }

    async save(definition: ProcedureDefinition): Promise<number> {
        const reply = await this.#context.channel.send(
            "SAVE_PROCEDURE",
            this.#context.scripts.saveProcedure(this.#database, definition),
        );
        return numberOf(reply.version) ?? 0;
    }

    async delete(name: string): Promise<void> {
        await this.#context.channel.send(
            "DELETE_PROCEDURE",
            this.#context.scripts.deleteProcedure(this.#database, name),
        );
    }

    async list(includeSource?: boolean): Promise<ProcedureInfo[]> {
        const reply = await this.#context.channel.send(
            "LIST_PROCEDURES",
            this.#context.scripts.listProcedures(this.#database, includeSource),
        );
        return documents(reply, "procedures").map(procedure);
    }

    async call(name: string, args?: Document): Promise<ScriptResult> {
        return scriptResult(
            await this.#context.channel.send(
                "CALL_PROCEDURE",
                this.#context.scripts.callProcedure(this.#database, name, args),
            ),
        );
    }
}
