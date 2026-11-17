import type { Permissions, TransactionDecision, TriggerRunDecision, TriggerRunStatus } from "../admin/types.js";
import type { PipelineStep } from "../query/types.js";
import type {
    ProcedureDefinition,
    ScheduleDefinition,
    TriggerDefinition,
    TriggerEvent,
} from "../scripting/definitions.js";
import { request, type RequestEncoder } from "./request-encoder.js";
import type { Json, JsonObject } from "./values-codec.js";

function optional(field: string, value: Json | undefined): JsonObject {
    return value === undefined ? {} : { [field]: value };
}

function permissions(granted: Permissions): JsonObject {
    return {
        admin: granted.admin ?? false,
        globalPermissions: [...(granted.global ?? [])],
        databasePermissions: { ...granted.databases },
        collectionPermissions: { ...granted.collections },
        scriptPermissions: { ...granted.scripts },
    };
}

function checkSchedule(schedule: ScheduleDefinition): void {
    if ((schedule.cron === undefined) === (schedule.intervalMs === undefined)) {
        throw new TypeError("a schedule takes exactly one of cron and intervalMs");
    }
}

function checkTrigger(trigger: TriggerDefinition): void {
    if (trigger.events.length === 0) {
        throw new RangeError("a trigger needs at least one event");
    }
}

export class ScriptRequestEncoder {
    readonly #encoder: RequestEncoder;

    constructor(encoder: RequestEncoder) {
        this.#encoder = encoder;
    }

    runScript(database: string, script: string, args: unknown): JsonObject {
        return { ...request("RUN_SCRIPT", database), script, ...this.#args(args) };
    }

    saveProcedure(database: string, procedure: ProcedureDefinition): JsonObject {
        return {
            ...request("SAVE_PROCEDURE", database),
            name: procedure.name,
            script: procedure.script,
            ...optional("description", procedure.description),
            ...optional("enabled", procedure.enabled),
            ...optional("ifVersion", procedure.ifVersion),
        };
    }

    deleteProcedure(database: string, name: string): JsonObject {
        return { ...request("DELETE_PROCEDURE", database), name };
    }

    listProcedures(database: string, includeSource: boolean | undefined): JsonObject {
        return { ...request("LIST_PROCEDURES", database), ...optional("includeSource", includeSource) };
    }

    callProcedure(database: string, name: string, args: unknown): JsonObject {
        return { ...request("CALL_PROCEDURE", database), procedureName: name, ...this.#args(args) };
    }

    saveTrigger(database: string, collection: string, trigger: TriggerDefinition): JsonObject {
        checkTrigger(trigger);
        return {
            ...request("SAVE_TRIGGER", database, collection),
            name: trigger.name,
            events: [...trigger.events],
            procedureName: trigger.procedureName,
            ...optional("timing", trigger.timing),
            ...optional("mode", trigger.mode),
            ...optional("allowCascade", trigger.allowCascade),
            ...optional("enabled", trigger.enabled),
            ...optional("ifVersion", trigger.ifVersion),
        };
    }

    deleteTrigger(database: string, collection: string, name: string): JsonObject {
        return { ...request("DELETE_TRIGGER", database, collection), name };
    }

    listTriggers(database: string, collection: string | undefined): JsonObject {
        return request("LIST_TRIGGERS", database, collection);
    }

    testTrigger(
        database: string,
        collection: string,
        name: string,
        event: TriggerEvent,
        document: unknown,
    ): JsonObject {
        return {
            ...request("TEST_TRIGGER", database, collection),
            name,
            event,
            document: this.#encoder.document(document),
        };
    }

    saveSchedule(database: string, schedule: ScheduleDefinition): JsonObject {
        checkSchedule(schedule);
        return {
            ...request("SAVE_SCHEDULE", database),
            name: schedule.name,
            procedureName: schedule.procedureName,
            ...optional("cron", schedule.cron),
            ...optional("intervalMs", schedule.intervalMs),
            ...this.#args(schedule.args),
            ...optional("timeoutMs", schedule.timeoutMs),
            ...optional("description", schedule.description),
            ...optional("enabled", schedule.enabled),
            ...optional("ifVersion", schedule.ifVersion),
        };
    }

    deleteSchedule(database: string, name: string): JsonObject {
        return { ...request("DELETE_SCHEDULE", database), name };
    }

    listSchedules(database: string, includeArgs: boolean | undefined): JsonObject {
        return { ...request("LIST_SCHEDULES", database), ...optional("includeArgs", includeArgs) };
    }

    listScripts(): JsonObject {
        return request("LIST_SCRIPTS");
    }

    cancelScript(runId: string): JsonObject {
        return { ...request("CANCEL_SCRIPT"), runId };
    }

    listTriggerRuns(status: TriggerRunStatus | undefined): JsonObject {
        return { ...request("LIST_TRIGGER_RUNS"), ...optional("status", status) };
    }

    resolveTriggerRun(runId: string, decision: TriggerRunDecision): JsonObject {
        return { ...request("RESOLVE_TRIGGER_RUN"), runId, decision };
    }

    listTransactions(): JsonObject {
        return request("LIST_TRANSACTIONS");
    }

    resolveTransaction(dtxId: string, decision: TransactionDecision): JsonObject {
        return { ...request("RESOLVE_TRANSACTION"), dtxId, decision };
    }

    createUser(username: string, password: string, granted: Permissions | undefined): JsonObject {
        return {
            ...request("CREATE_USER"),
            username,
            password,
            ...(granted === undefined ? {} : permissions(granted)),
        };
    }

    deleteUser(username: string): JsonObject {
        return { ...request("DELETE_USER"), username };
    }

    changePermissions(username: string, granted: Permissions): JsonObject {
        return { ...request("CHANGE_PERMISSIONS"), username, ...permissions(granted) };
    }

    setPassword(username: string, currentPassword: string | undefined, newPassword: string): JsonObject {
        return { ...request("SET_PASSWORD"), username, ...optional("currentPassword", currentPassword), newPassword };
    }

    listUsers(steps: readonly PipelineStep[]): JsonObject {
        return steps.length === 0
            ? request("LIST_USERS")
            : { ...request("LIST_USERS"), aggregationSteps: this.#encoder.pipeline.steps(steps) };
    }

    #args(args: unknown): JsonObject {
        return args === undefined ? {} : { args: this.#encoder.document(args) };
    }
}
