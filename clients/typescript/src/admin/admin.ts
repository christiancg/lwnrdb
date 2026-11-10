import type { ClientContext } from "../internal/client-context.js";
import { emptyOn } from "../internal/collection-ops.js";
import { scriptRun, transaction, triggerRun, user } from "../internal/descriptors.js";
import { booleanOf, documents } from "../internal/response-decoder.js";
import { ErrorCode } from "../errors/error-codes.js";
import type { Filter } from "../query/types.js";
import type {
    Permissions,
    ScriptRun,
    TransactionDecision,
    TransactionInfo,
    TriggerRun,
    TriggerRunDecision,
    TriggerRunStatus,
    UserInfo,
} from "./types.js";

export class Users {
    readonly #context: ClientContext;

    constructor(context: ClientContext) {
        this.#context = context;
    }

    async create(username: string, password: string, permissions?: Permissions): Promise<void> {
        await this.#context.channel.send(
            "CREATE_USER",
            this.#context.scripts.createUser(username, password, permissions),
        );
    }

    async delete(username: string): Promise<void> {
        await this.#context.channel.send("DELETE_USER", this.#context.scripts.deleteUser(username));
    }

    async changePermissions(username: string, permissions: Permissions): Promise<void> {
        await this.#context.channel.send(
            "CHANGE_PERMISSIONS",
            this.#context.scripts.changePermissions(username, permissions),
        );
    }

    async setPassword(username: string, newPassword: string): Promise<void> {
        await this.#context.channel.send(
            "SET_PASSWORD",
            this.#context.scripts.setPassword(username, undefined, newPassword),
        );
    }

    async list(filter?: Filter): Promise<UserInfo[]> {
        const request = this.#context.scripts.listUsers(filter === undefined ? [] : [{ type: "FILTER", filter }]);
        const read = async (): Promise<UserInfo[]> =>
            documents(await this.#context.channel.send("LIST_USERS", request), "users").map(user);
        return emptyOn(ErrorCode.NO_USERS_FOUND, [], () => emptyOn(ErrorCode.NO_RESULTS, [], read));
    }
}

export class DistributedTransactions {
    readonly #context: ClientContext;

    constructor(context: ClientContext) {
        this.#context = context;
    }

    async list(): Promise<TransactionInfo[]> {
        const reply = await this.#context.channel.send("LIST_TRANSACTIONS", this.#context.scripts.listTransactions());
        return documents(reply, "transactions").map(transaction);
    }

    async resolve(dtxId: string, decision: TransactionDecision): Promise<void> {
        await this.#context.channel.send(
            "RESOLVE_TRANSACTION",
            this.#context.scripts.resolveTransaction(dtxId, decision),
        );
    }
}

export class Scripts {
    readonly #context: ClientContext;

    constructor(context: ClientContext) {
        this.#context = context;
    }

    async list(): Promise<ScriptRun[]> {
        const reply = await this.#context.channel.send("LIST_SCRIPTS", this.#context.scripts.listScripts());
        return documents(reply, "scripts").map(scriptRun);
    }

    async cancel(runId: string): Promise<boolean> {
        const reply = await this.#context.channel.send("CANCEL_SCRIPT", this.#context.scripts.cancelScript(runId));
        return booleanOf(reply.cancelled, false);
    }
}

export class TriggerRuns {
    readonly #context: ClientContext;

    constructor(context: ClientContext) {
        this.#context = context;
    }

    async list(status?: TriggerRunStatus): Promise<TriggerRun[]> {
        const reply = await this.#context.channel.send(
            "LIST_TRIGGER_RUNS",
            this.#context.scripts.listTriggerRuns(status),
        );
        return documents(reply, "runs").map(triggerRun);
    }

    async resolve(runId: string, decision: TriggerRunDecision): Promise<boolean> {
        const reply = await this.#context.channel.send(
            "RESOLVE_TRIGGER_RUN",
            this.#context.scripts.resolveTriggerRun(runId, decision),
        );
        return booleanOf(reply.resolved, false);
    }
}

export class Admin {
    readonly #context: ClientContext;

    constructor(context: ClientContext) {
        this.#context = context;
    }

    users(): Users {
        return new Users(this.#context);
    }

    transactions(): DistributedTransactions {
        return new DistributedTransactions(this.#context);
    }

    scripts(): Scripts {
        return new Scripts(this.#context);
    }

    triggerRuns(): TriggerRuns {
        return new TriggerRuns(this.#context);
    }
}
