using System;
using System.Collections.Generic;
using Lwnrdb.Client.Admin;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Scripting;
using static Lwnrdb.Client.Query.Filters;

namespace Lwnrdb.Client.Tests.Fixtures;

internal static class WireFixtureAdminCases
{
    private const string ListenId = "550e8400-e29b-41d4-a716-446655440000";
    private const string RunId = "8f0e1a2b";
    private static readonly string[] RequiredFields = ["status"];

    public static void Add(Dictionary<string, FixtureCall> cases)
    {
        Ddl(cases);
        ListenAndTransactions(cases);
        Procedures(cases);
        TriggersAndSchedules(cases);
        AdminScripts(cases);
        Users(cases);
    }

    private static void Ddl(Dictionary<string, FixtureCall> cases)
    {
        cases["ddl.list_databases"] = r => r.Raw(RequestEncoder.Request("LIST_DATABASES"));
        cases["ddl.create_database"] = r => r.Shop.CreateAsync();
        cases["ddl.drop_database"] = r => r.Shop.DropAsync();
        cases["ddl.list_collections"] = r => r.Shop.ListCollectionsAsync();
        cases["ddl.create_collection"] = r => r.Orders.CreateAsync();
        cases["ddl.drop_collection"] = r => r.Orders.DropAsync();
        cases["indexes.create_one"] = r => r.Orders.Indexes().CreateAsync("email");
        cases["indexes.create_many"] = r => r.Orders.Indexes().CreateAsync("email", "status");
        cases["indexes.drop"] = r => r.Orders.Indexes().DropAsync("email");
        cases["indexes.reindex_all"] = r => r.Orders.Indexes().ReindexAsync();
        cases["indexes.reindex_fields"] = r => r.Orders.Indexes().ReindexAsync("email");
        cases["indexes.reindex_primary_key"] = r => r.Orders.Indexes().ReindexPrimaryKeyAsync();
        cases["schema.save"] = r => r.Orders.Schema().SaveAsync(Document.Of(
            "type", "object",
            "required", RequiredFields,
            "properties", Document.Of("status", Document.Of("type", "string"))));
        cases["schema.get"] = r => r.Orders.Schema().GetAsync();
        cases["schema.delete"] = r => r.Orders.Schema().DeleteAsync();
        cases["info.server_info"] = r => r.Raw(RequestEncoder.Request("SERVER_INFO"));
        cases["info.database_stats_all"] = r => r.Raw(RequestEncoder.Request("GET_DATABASE_STATS"));
        cases["info.database_stats_one"] = r => r.Shop.StatsAsync();
        cases["info.close_connection"] = r => r.Raw(RequestEncoder.Request("CLOSE_CONNECTION"));
    }

    private static void ListenAndTransactions(Dictionary<string, FixtureCall> cases)
    {
        cases["listen.filter"] = r => r.Raw(r.Encoder.Listen("shop", "orders", r.Orders.Query().Filter(Field("status").Eq("pending")).Steps));
        cases["listen.all"] = r => r.Raw(r.Encoder.Listen("shop", "orders", r.Orders.Query().Steps));
        cases["listen.stop"] = r => r.Raw(RequestEncoder.StopListen(ListenId));
        cases["transactions.start"] = r => r.Raw(RequestEncoder.Request("START_TRANSACTION"));
        cases["transactions.commit"] = r => r.Raw(RequestEncoder.Request("COMMIT_TRANSACTION"));
        cases["transactions.rollback"] = r => r.Raw(RequestEncoder.Request("ROLLBACK_TRANSACTION"));
        cases["transactions.list"] = r => r.Admin.Transactions().ListAsync();
        cases["transactions.resolve_commit"] = r => r.Admin.Transactions().ResolveAsync(ListenId, TransactionDecision.Commit);
        cases["transactions.resolve_abort"] = r => r.Admin.Transactions().ResolveAsync(ListenId, TransactionDecision.Abort);
    }

    private static void Procedures(Dictionary<string, FixtureCall> cases)
    {
        cases["scripting.run_script"] = r => r.Shop.RunScriptAsync("return 1;", Document.Of("id", "o-1"));
        cases["scripting.run_script_no_args"] = r => r.Shop.RunScriptAsync("return 1;");
        cases["scripting.save_procedure"] = r => r.Shop.Procedures().SaveAsync(
            new ProcedureDefinition("recalc", "return 1;") { Description = "totals", Enabled = true, IfVersion = 3 });
        cases["scripting.save_procedure_minimal"] = r => r.Shop.Procedures().SaveAsync(new ProcedureDefinition("recalc", "return 1;"));
        cases["scripting.delete_procedure"] = r => r.Shop.Procedures().DeleteAsync("recalc");
        cases["scripting.list_procedures"] = r => r.Shop.Procedures().ListAsync(true);
        cases["scripting.list_procedures_default"] = r => r.Shop.Procedures().ListAsync();
        cases["scripting.call_procedure"] = r => r.Shop.Procedures().CallAsync("recalc", Document.Of("id", "o-1"));
        cases["scripting.call_procedure_no_args"] = r => r.Shop.Procedures().CallAsync("recalc");
    }

    private static void TriggersAndSchedules(Dictionary<string, FixtureCall> cases)
    {
        cases["scripting.save_trigger"] = r => r.Orders.Triggers().SaveAsync(
            new TriggerDefinition("audit", "recalc", [TriggerEvent.Created, TriggerEvent.Updated, TriggerEvent.Deleted])
            {
                Timing = TriggerTiming.After,
                Mode = TriggerMode.Document,
                AllowCascade = false,
                Enabled = true,
                IfVersion = 1,
            });
        cases["scripting.save_trigger_before"] = r => r.Orders.Triggers().SaveAsync(
            new TriggerDefinition("normalize", "normalize", [TriggerEvent.Created]) { Timing = TriggerTiming.Before });
        cases["scripting.save_trigger_batch"] = r => r.Orders.Triggers().SaveAsync(
            new TriggerDefinition("rollup", "rollup", [TriggerEvent.Created]) { Mode = TriggerMode.Batch });
        cases["scripting.delete_trigger"] = r => r.Orders.Triggers().DeleteAsync("audit");
        cases["scripting.list_triggers_collection"] = r => r.Orders.Triggers().ListAsync();
        cases["scripting.list_triggers_database"] = r => r.Shop.ListTriggersAsync();
        cases["scripting.test_trigger"] = r => r.Orders.Triggers().TestAsync("normalize", TriggerEvent.Created, Document.Of("_id", "o-1", "qty", 2));
        cases["scripting.save_schedule_cron"] = r => r.Shop.Schedules().SaveAsync(new ScheduleDefinition("nightly", "rollup")
        {
            Cron = "0 3 * * *",
            Args = Document.Of("days", 1),
            Timeout = TimeSpan.FromSeconds(60),
            Description = "rollup",
            Enabled = true,
            IfVersion = 2,
        });
        cases["scripting.save_schedule_interval"] = r => r.Shop.Schedules().SaveAsync(
            new ScheduleDefinition("poll", "poll") { Interval = TimeSpan.FromSeconds(60) });
        cases["scripting.delete_schedule"] = r => r.Shop.Schedules().DeleteAsync("nightly");
        cases["scripting.list_schedules"] = r => r.Shop.Schedules().ListAsync(true);
        cases["scripting.list_schedules_default"] = r => r.Shop.Schedules().ListAsync();
    }

    private static void AdminScripts(Dictionary<string, FixtureCall> cases)
    {
        cases["scripting.list_scripts"] = r => r.Admin.Scripts().ListAsync();
        cases["scripting.cancel_script"] = r => r.Admin.Scripts().CancelAsync("3f6c1e2a-8b47-4d90-9a11-5c2e7d0b4f83");
        cases["scripting.list_trigger_runs"] = r => r.Admin.TriggerRuns().ListAsync();
        cases["scripting.list_trigger_runs_dead"] = r => r.Admin.TriggerRuns().ListAsync(TriggerRunStatus.Dead);
        cases["scripting.resolve_trigger_run_replay"] = r => r.Admin.TriggerRuns().ResolveAsync(RunId, TriggerRunDecision.Replay);
        cases["scripting.resolve_trigger_run_discard"] = r => r.Admin.TriggerRuns().ResolveAsync(RunId, TriggerRunDecision.Discard);
    }

    private static void Users(Dictionary<string, FixtureCall> cases)
    {
        cases["users.authenticate"] = r => r.Raw(RequestEncoder.Authenticate("alice", "Secret-1234"));
        cases["users.create"] = r => r.Admin.Users().CreateAsync("bob", "Secret-1234", new Permissions
        {
            Admin = false,
            Global = [GlobalPermission.CreateDatabase],
            Databases = new Dictionary<string, DataPermission> { ["shop"] = DataPermission.ReadWrite },
            Collections = new Dictionary<string, DataPermission> { [Permissions.CollectionKey("shop", "orders")] = DataPermission.Read },
            Scripts = new Dictionary<string, ScriptPermission> { ["shop"] = ScriptPermission.Manage },
        });
        cases["users.create_minimal"] = r => r.Admin.Users().CreateAsync("bob", "Secret-1234");
        cases["users.delete"] = r => r.Admin.Users().DeleteAsync("bob");
        cases["users.change_permissions"] = r => r.Admin.Users().ChangePermissionsAsync("bob", new Permissions
        {
            Databases = new Dictionary<string, DataPermission> { ["shop"] = DataPermission.Read },
            Scripts = new Dictionary<string, ScriptPermission> { ["shop"] = ScriptPermission.Run },
        });
        cases["users.set_password_own"] = r => r.Raw(ScriptRequestEncoder.SetPassword("alice", "Secret-1234", "Secret-5678"));
        cases["users.set_password_admin"] = r => r.Admin.Users().SetPasswordAsync("bob", "Secret-5678");
        cases["users.list"] = r => r.Admin.Users().ListAsync();
        cases["users.list_filtered"] = r => r.Admin.Users().ListAsync(Field("admin").Eq(true));
        cases["users.set_database_owners"] = r => r.Shop.SetOwnersAsync(["alice", "bob"]);
    }
}
