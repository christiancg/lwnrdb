package io.github.christiancg.lwnrdb.client;

import static io.github.christiancg.lwnrdb.client.query.Filters.field;

import io.github.christiancg.lwnrdb.client.admin.DataPermission;
import io.github.christiancg.lwnrdb.client.admin.GlobalPermission;
import io.github.christiancg.lwnrdb.client.admin.Permissions;
import io.github.christiancg.lwnrdb.client.admin.ScriptPermission;
import io.github.christiancg.lwnrdb.client.admin.TransactionDecision;
import io.github.christiancg.lwnrdb.client.admin.TriggerRunDecision;
import io.github.christiancg.lwnrdb.client.admin.TriggerRunStatus;
import io.github.christiancg.lwnrdb.client.scripting.ProcedureDefinition;
import io.github.christiancg.lwnrdb.client.scripting.ScheduleDefinition;
import io.github.christiancg.lwnrdb.client.scripting.TriggerDefinition;
import io.github.christiancg.lwnrdb.client.scripting.TriggerEvent;
import io.github.christiancg.lwnrdb.client.scripting.TriggerMode;
import io.github.christiancg.lwnrdb.client.scripting.TriggerTiming;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

final class WireFixtureAdminCases {
    private static final String LISTEN_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String RUN_ID = "8f0e1a2b";

    private WireFixtureAdminCases() {
    }

    static void add(Map<String, Consumer<Recorder>> cases) {
        ddl(cases);
        listenAndTransactions(cases);
        scripting(cases);
        users(cases);
    }

    private static void ddl(Map<String, Consumer<Recorder>> cases) {
        cases.put("ddl.list_databases", r -> r.channel.send(null, r.encoder.listDatabases()));
        cases.put("ddl.create_database", r -> r.shop.create());
        cases.put("ddl.drop_database", r -> r.shop.drop());
        cases.put("ddl.list_collections", r -> r.shop.listCollections());
        cases.put("ddl.create_collection", r -> r.orders.create());
        cases.put("ddl.drop_collection", r -> r.orders.drop());
        cases.put("indexes.create_one", r -> r.orders.indexes().create("email"));
        cases.put("indexes.create_many", r -> r.orders.indexes().create("email", "status"));
        cases.put("indexes.drop", r -> r.orders.indexes().drop("email"));
        cases.put("indexes.reindex_all", r -> r.orders.indexes().reindex());
        cases.put("indexes.reindex_fields", r -> r.orders.indexes().reindex("email"));
        cases.put("indexes.reindex_primary_key", r -> r.orders.indexes().reindexPrimaryKey());
        cases.put("schema.save", r -> r.orders.schema().save(Document.of("type", "object", "required",
                List.of("status"), "properties", Document.of("status", Document.of("type", "string")))));
        cases.put("schema.get", r -> r.orders.schema().get());
        cases.put("schema.delete", r -> r.orders.schema().delete());
        cases.put("info.server_info", r -> r.channel.send(null, r.encoder.serverInfo()));
        cases.put("info.database_stats_all", r -> r.channel.send(null, r.encoder.databaseStats(Optional.empty())));
        cases.put("info.database_stats_one", r -> r.shop.stats());
        cases.put("info.close_connection", r -> r.channel.send(null, r.encoder.closeConnection()));
    }

    private static void listenAndTransactions(Map<String, Consumer<Recorder>> cases) {
        cases.put("listen.filter", r -> r.channel.send(null,
                r.encoder.listen("shop", "orders", r.orders.query().filter(field("status").eq("pending")).steps())));
        cases.put("listen.all",
                r -> r.channel.send(null, r.encoder.listen("shop", "orders", r.orders.query().steps())));
        cases.put("listen.stop", r -> r.channel.send(null, r.encoder.stopListen(LISTEN_ID)));
        cases.put("transactions.start", r -> r.channel.send(null, r.encoder.startTransaction()));
        cases.put("transactions.commit", r -> r.channel.send(null, r.encoder.commitTransaction()));
        cases.put("transactions.rollback", r -> r.channel.send(null, r.encoder.rollbackTransaction()));
        cases.put("transactions.list", r -> r.admin.transactions().list());
        cases.put("transactions.resolve_commit",
                r -> r.admin.transactions().resolve(LISTEN_ID, TransactionDecision.COMMIT));
        cases.put("transactions.resolve_abort",
                r -> r.admin.transactions().resolve(LISTEN_ID, TransactionDecision.ABORT));
    }

    private static void scripting(Map<String, Consumer<Recorder>> cases) {
        cases.put("scripting.run_script", r -> r.shop.runScript("return 1;", Document.of("id", "o-1")));
        cases.put("scripting.run_script_no_args", r -> r.shop.runScript("return 1;"));
        cases.put("scripting.save_procedure", r -> r.shop.procedures().save(ProcedureDefinition
                .builder("recalc", "return 1;").description("totals").enabled(true).ifVersion(3).build()));
        cases.put("scripting.save_procedure_minimal",
                r -> r.shop.procedures().save(ProcedureDefinition.builder("recalc", "return 1;").build()));
        cases.put("scripting.delete_procedure", r -> r.shop.procedures().delete("recalc"));
        cases.put("scripting.list_procedures", r -> r.shop.procedures().list(true));
        cases.put("scripting.list_procedures_default", r -> r.shop.procedures().list());
        cases.put("scripting.call_procedure", r -> r.shop.procedures().call("recalc", Document.of("id", "o-1")));
        cases.put("scripting.call_procedure_no_args", r -> r.shop.procedures().call("recalc"));
        cases.put("scripting.save_trigger", r -> r.orders.triggers().save(TriggerDefinition.builder("audit", "recalc")
                .events(TriggerEvent.CREATED, TriggerEvent.UPDATED, TriggerEvent.DELETED).timing(TriggerTiming.AFTER)
                .mode(TriggerMode.DOCUMENT).allowCascade(false).enabled(true).ifVersion(1).build()));
        cases.put("scripting.save_trigger_before", r -> r.orders.triggers().save(TriggerDefinition
                .builder("normalize", "normalize").events(TriggerEvent.CREATED).timing(TriggerTiming.BEFORE).build()));
        cases.put("scripting.save_trigger_batch", r -> r.orders.triggers().save(TriggerDefinition
                .builder("rollup", "rollup").events(TriggerEvent.CREATED).mode(TriggerMode.BATCH).build()));
        cases.put("scripting.delete_trigger", r -> r.orders.triggers().delete("audit"));
        cases.put("scripting.list_triggers_collection", r -> r.orders.triggers().list());
        cases.put("scripting.list_triggers_database", r -> r.shop.listTriggers());
        cases.put("scripting.test_trigger",
                r -> r.orders.triggers().test("normalize", TriggerEvent.CREATED, Document.of("_id", "o-1", "qty", 2)));
        cases.put("scripting.save_schedule_cron",
                r -> r.shop.schedules()
                        .save(ScheduleDefinition.builder("nightly", "rollup").cron("0 3 * * *")
                                .args(Document.of("days", 1)).timeout(Duration.ofSeconds(60)).description("rollup")
                                .enabled(true).ifVersion(2).build()));
        cases.put("scripting.save_schedule_interval", r -> r.shop.schedules()
                .save(ScheduleDefinition.builder("poll", "poll").interval(Duration.ofMinutes(1)).build()));
        cases.put("scripting.delete_schedule", r -> r.shop.schedules().delete("nightly"));
        cases.put("scripting.list_schedules", r -> r.shop.schedules().list(true));
        cases.put("scripting.list_schedules_default", r -> r.shop.schedules().list());
        cases.put("scripting.list_scripts", r -> r.admin.scripts().list());
        cases.put("scripting.cancel_script", r -> r.admin.scripts().cancel("3f6c1e2a-8b47-4d90-9a11-5c2e7d0b4f83"));
        cases.put("scripting.list_trigger_runs", r -> r.admin.triggerRuns().list());
        cases.put("scripting.list_trigger_runs_dead", r -> r.admin.triggerRuns().list(TriggerRunStatus.DEAD));
        cases.put("scripting.resolve_trigger_run_replay",
                r -> r.admin.triggerRuns().resolve(RUN_ID, TriggerRunDecision.REPLAY));
        cases.put("scripting.resolve_trigger_run_discard",
                r -> r.admin.triggerRuns().resolve(RUN_ID, TriggerRunDecision.DISCARD));
    }

    private static void users(Map<String, Consumer<Recorder>> cases) {
        cases.put("users.authenticate", r -> r.channel.send(null, r.encoder.authenticate("alice", "Secret-1234")));
        cases.put("users.create", r -> r.admin.users().create("bob", "Secret-1234",
                Permissions.builder().admin(false).global(GlobalPermission.CREATE_DATABASE)
                        .database("shop", DataPermission.READ_WRITE).collection("shop", "orders", DataPermission.READ)
                        .scripts("shop", ScriptPermission.MANAGE).build()));
        cases.put("users.create_minimal", r -> r.admin.users().create("bob", "Secret-1234"));
        cases.put("users.delete", r -> r.admin.users().delete("bob"));
        cases.put("users.change_permissions", r -> r.admin.users().changePermissions("bob", Permissions.builder()
                .database("shop", DataPermission.READ).scripts("shop", ScriptPermission.RUN).build()));
        cases.put("users.set_password_own",
                r -> r.channel.send(null, r.scripts.setPassword("alice", Optional.of("Secret-1234"), "Secret-5678")));
        cases.put("users.set_password_admin", r -> r.admin.users().setPassword("bob", "Secret-5678"));
        cases.put("users.list", r -> r.admin.users().list());
        cases.put("users.list_filtered", r -> r.admin.users().list(field("admin").eq(true)));
        cases.put("users.set_database_owners", r -> r.shop.setOwners(List.of("alice", "bob")));
    }
}
