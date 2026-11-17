import {
    DataPermission,
    field,
    GlobalPermission,
    ScriptPermission,
    TransactionDecision,
    TriggerEvent,
    TriggerMode,
    TriggerRunDecision,
    TriggerRunStatus,
    TriggerTiming,
} from "../../src/index.js";
import type { FixtureCall } from "./recorder.js";

const LISTEN_ID = "550e8400-e29b-41d4-a716-446655440000";
const RUN_ID = "8f0e1a2b";

function ddl(cases: Map<string, FixtureCall>): void {
    cases.set("ddl.list_databases", (r) => r.raw(r.encoder.listDatabases()));
    cases.set("ddl.create_database", (r) => r.shop.create());
    cases.set("ddl.drop_database", (r) => r.shop.drop());
    cases.set("ddl.list_collections", (r) => r.shop.listCollections());
    cases.set("ddl.create_collection", (r) => r.orders.create());
    cases.set("ddl.drop_collection", (r) => r.orders.drop());
    cases.set("indexes.create_one", (r) => r.orders.indexes().create("email"));
    cases.set("indexes.create_many", (r) => r.orders.indexes().create("email", "status"));
    cases.set("indexes.drop", (r) => r.orders.indexes().drop("email"));
    cases.set("indexes.reindex_all", (r) => r.orders.indexes().reindex());
    cases.set("indexes.reindex_fields", (r) => r.orders.indexes().reindex("email"));
    cases.set("indexes.reindex_primary_key", (r) => r.orders.indexes().reindexPrimaryKey());
    cases.set("schema.save", (r) =>
        r.orders.schema().save({ type: "object", required: ["status"], properties: { status: { type: "string" } } }),
    );
    cases.set("schema.get", (r) => r.orders.schema().get());
    cases.set("schema.delete", (r) => r.orders.schema().delete());
    cases.set("info.server_info", (r) => r.raw(r.encoder.serverInfo()));
    cases.set("info.database_stats_all", (r) => r.raw(r.encoder.databaseStats()));
    cases.set("info.database_stats_one", (r) => r.shop.stats());
    cases.set("info.close_connection", (r) => r.raw(r.encoder.closeConnection()));
}

function listenAndTransactions(cases: Map<string, FixtureCall>): void {
    cases.set("listen.filter", (r) =>
        r.raw(r.encoder.listen("shop", "orders", r.orders.query().filter(field("status").eq("pending")).steps)),
    );
    cases.set("listen.all", (r) => r.raw(r.encoder.listen("shop", "orders", r.orders.query().steps)));
    cases.set("listen.stop", (r) => r.raw(r.encoder.stopListen(LISTEN_ID)));
    cases.set("transactions.start", (r) => r.raw(r.encoder.startTransaction()));
    cases.set("transactions.commit", (r) => r.raw(r.encoder.commitTransaction()));
    cases.set("transactions.rollback", (r) => r.raw(r.encoder.rollbackTransaction()));
    cases.set("transactions.list", (r) => r.admin.transactions().list());
    cases.set("transactions.resolve_commit", (r) =>
        r.admin.transactions().resolve(LISTEN_ID, TransactionDecision.COMMIT),
    );
    cases.set("transactions.resolve_abort", (r) =>
        r.admin.transactions().resolve(LISTEN_ID, TransactionDecision.ABORT),
    );
}

function procedures(cases: Map<string, FixtureCall>): void {
    cases.set("scripting.run_script", (r) => r.shop.runScript("return 1;", { id: "o-1" }));
    cases.set("scripting.run_script_no_args", (r) => r.shop.runScript("return 1;"));
    cases.set("scripting.save_procedure", (r) =>
        r.shop
            .procedures()
            .save({ name: "recalc", script: "return 1;", description: "totals", enabled: true, ifVersion: 3 }),
    );
    cases.set("scripting.save_procedure_minimal", (r) =>
        r.shop.procedures().save({ name: "recalc", script: "return 1;" }),
    );
    cases.set("scripting.delete_procedure", (r) => r.shop.procedures().delete("recalc"));
    cases.set("scripting.list_procedures", (r) => r.shop.procedures().list(true));
    cases.set("scripting.list_procedures_default", (r) => r.shop.procedures().list());
    cases.set("scripting.call_procedure", (r) => r.shop.procedures().call("recalc", { id: "o-1" }));
    cases.set("scripting.call_procedure_no_args", (r) => r.shop.procedures().call("recalc"));
}

function triggersAndSchedules(cases: Map<string, FixtureCall>): void {
    cases.set("scripting.save_trigger", (r) =>
        r.orders.triggers().save({
            name: "audit",
            procedureName: "recalc",
            events: [TriggerEvent.CREATED, TriggerEvent.UPDATED, TriggerEvent.DELETED],
            timing: TriggerTiming.AFTER,
            mode: TriggerMode.DOCUMENT,
            allowCascade: false,
            enabled: true,
            ifVersion: 1,
        }),
    );
    cases.set("scripting.save_trigger_before", (r) =>
        r.orders.triggers().save({
            name: "normalize",
            procedureName: "normalize",
            events: [TriggerEvent.CREATED],
            timing: TriggerTiming.BEFORE,
        }),
    );
    cases.set("scripting.save_trigger_batch", (r) =>
        r.orders
            .triggers()
            .save({ name: "rollup", procedureName: "rollup", events: [TriggerEvent.CREATED], mode: TriggerMode.BATCH }),
    );
    cases.set("scripting.delete_trigger", (r) => r.orders.triggers().delete("audit"));
    cases.set("scripting.list_triggers_collection", (r) => r.orders.triggers().list());
    cases.set("scripting.list_triggers_database", (r) => r.shop.listTriggers());
    cases.set("scripting.test_trigger", (r) =>
        r.orders.triggers().test("normalize", TriggerEvent.CREATED, { _id: "o-1", qty: 2 }),
    );
    cases.set("scripting.save_schedule_cron", (r) =>
        r.shop.schedules().save({
            name: "nightly",
            procedureName: "rollup",
            cron: "0 3 * * *",
            args: { days: 1 },
            timeoutMs: 60_000,
            description: "rollup",
            enabled: true,
            ifVersion: 2,
        }),
    );
    cases.set("scripting.save_schedule_interval", (r) =>
        r.shop.schedules().save({ name: "poll", procedureName: "poll", intervalMs: 60_000 }),
    );
    cases.set("scripting.delete_schedule", (r) => r.shop.schedules().delete("nightly"));
    cases.set("scripting.list_schedules", (r) => r.shop.schedules().list(true));
    cases.set("scripting.list_schedules_default", (r) => r.shop.schedules().list());
}

function adminScripts(cases: Map<string, FixtureCall>): void {
    cases.set("scripting.list_scripts", (r) => r.admin.scripts().list());
    cases.set("scripting.cancel_script", (r) => r.admin.scripts().cancel("3f6c1e2a-8b47-4d90-9a11-5c2e7d0b4f83"));
    cases.set("scripting.list_trigger_runs", (r) => r.admin.triggerRuns().list());
    cases.set("scripting.list_trigger_runs_dead", (r) => r.admin.triggerRuns().list(TriggerRunStatus.DEAD));
    cases.set("scripting.resolve_trigger_run_replay", (r) =>
        r.admin.triggerRuns().resolve(RUN_ID, TriggerRunDecision.REPLAY),
    );
    cases.set("scripting.resolve_trigger_run_discard", (r) =>
        r.admin.triggerRuns().resolve(RUN_ID, TriggerRunDecision.DISCARD),
    );
}

function users(cases: Map<string, FixtureCall>): void {
    cases.set("users.authenticate", (r) => r.raw(r.encoder.authenticate("alice", "Secret-1234")));
    cases.set("users.create", (r) =>
        r.admin.users().create("bob", "Secret-1234", {
            admin: false,
            global: [GlobalPermission.CREATE_DATABASE],
            databases: { shop: DataPermission.READ_WRITE },
            collections: { "shop|orders": DataPermission.READ },
            scripts: { shop: ScriptPermission.MANAGE },
        }),
    );
    cases.set("users.create_minimal", (r) => r.admin.users().create("bob", "Secret-1234"));
    cases.set("users.delete", (r) => r.admin.users().delete("bob"));
    cases.set("users.change_permissions", (r) =>
        r.admin.users().changePermissions("bob", {
            databases: { shop: DataPermission.READ },
            scripts: { shop: ScriptPermission.RUN },
        }),
    );
    cases.set("users.set_password_own", (r) => r.raw(r.scripts.setPassword("alice", "Secret-1234", "Secret-5678")));
    cases.set("users.set_password_admin", (r) => r.admin.users().setPassword("bob", "Secret-5678"));
    cases.set("users.list", (r) => r.admin.users().list());
    cases.set("users.list_filtered", (r) => r.admin.users().list(field("admin").eq(true)));
    cases.set("users.set_database_owners", (r) => r.shop.setOwners(["alice", "bob"]));
}

export function addAdminCases(cases: Map<string, FixtureCall>): void {
    ddl(cases);
    listenAndTransactions(cases);
    procedures(cases);
    triggersAndSchedules(cases);
    adminScripts(cases);
    users(cases);
}
