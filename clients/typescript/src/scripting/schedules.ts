import type { ClientContext } from "../internal/client-context.js";
import { schedule } from "../internal/descriptors.js";
import { documents, numberOf } from "../internal/response-decoder.js";
import type { ScheduleDefinition, ScheduleInfo } from "./definitions.js";

export class Schedules {
    readonly #context: ClientContext;
    readonly #database: string;

    constructor(context: ClientContext, database: string) {
        this.#context = context;
        this.#database = database;
    }

    async save(definition: ScheduleDefinition): Promise<number> {
        const reply = await this.#context.channel.send(
            "SAVE_SCHEDULE",
            this.#context.scripts.saveSchedule(this.#database, definition),
        );
        return numberOf(reply.version) ?? 0;
    }

    async delete(name: string): Promise<void> {
        await this.#context.channel.send("DELETE_SCHEDULE", this.#context.scripts.deleteSchedule(this.#database, name));
    }

    async list(includeArgs?: boolean): Promise<ScheduleInfo[]> {
        const reply = await this.#context.channel.send(
            "LIST_SCHEDULES",
            this.#context.scripts.listSchedules(this.#database, includeArgs),
        );
        return documents(reply, "schedules").map(schedule);
    }
}
