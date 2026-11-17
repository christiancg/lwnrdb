using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Lwnrdb.Client.Admin;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.IntegrationTests.Support;
using Lwnrdb.Client.Scripting;
using Xunit;

namespace Lwnrdb.Client.IntegrationTests;

public sealed class ScriptingIT(ScriptingIT.Server fixture) : IClassFixture<ScriptingIT.Server>
{
    private Database Shop => fixture.Client.Database("script_it");

    [Fact]
    public async Task RunScriptReturnsItsValueAndLogs()
    {
        var result = await Shop.RunScriptAsync(
            "import args from \"args\";\nconsole.log(\"hi \" + args.name);\nreturn { greeting: \"hello \" + args.name };",
            Document.Of("name", "Ana"));
        Assert.Equal("hello Ana", ((Document)result.Value!).GetString("greeting"));
        Assert.Equal("hi Ana", result.Logs[0]);
        Assert.Equal(2.0, (await Shop.RunScriptAsync("return 1 + 1;")).Value);
    }

    [Fact]
    public async Task ProceduresAreSavedListedCalledAndDeleted()
    {
        var procedures = Shop.Procedures();
        var version = await procedures.SaveAsync(new ProcedureDefinition("double_it", "import args from \"args\";\nreturn args.n * 2;") { Description = "doubles" });
        Assert.True(version > 0);
        Assert.Contains(await procedures.ListAsync(), procedure => procedure.Name == "double_it");
        Assert.NotNull((await procedures.ListAsync(true)).Single(procedure => procedure.Name == "double_it").Source);
        Assert.Equal(42.0, (await procedures.CallAsync("double_it", Document.Of("n", 21))).Value);
        await Assert.ThrowsAnyAsync<LwnrdbException>(() => procedures.SaveAsync(new ProcedureDefinition("double_it", "return 0;") { IfVersion = version + 10 }));
        await procedures.DeleteAsync("double_it");
        await Assert.ThrowsAnyAsync<LwnrdbException>(() => procedures.CallAsync("double_it"));
    }

    [Fact]
    public async Task TriggersFireTestRejectAndList()
    {
        await Shop.Procedures().SaveAsync(new ProcedureDefinition(
            "audit_write",
            "import db from \"db\";\nimport args from \"args\";\ndb.save(db.name, \"audit\", { _id: args.id, event: args.event });\nreturn true;"));
        await Shop.Procedures().SaveAsync(new ProcedureDefinition(
            "require_total",
            "export default function (document) {\n  if (document.total == null) { throw new Error(\"total missing\"); }\n  return true;\n}"));
        var orders = Shop.Collection("orders");
        var saved = await orders.Triggers().SaveAsync(new TriggerDefinition("audit", "audit_write", [TriggerEvent.Created]));
        Assert.True(saved.Version > 0);
        await orders.Triggers().SaveAsync(new TriggerDefinition("guard", "require_total", [TriggerEvent.Created]) { Timing = TriggerTiming.Before });
        Assert.Equal(2, (await orders.Triggers().ListAsync()).Count);
        Assert.Contains("guard", (await Shop.ListTriggersAsync()).Select(trigger => trigger.Name));
        Assert.NotEqual(string.Empty, (await orders.Triggers().TestAsync("guard", TriggerEvent.Created, Document.Of("_id", "t1"))).Decision);
        var refused = await Assert.ThrowsAnyAsync<LwnrdbException>(() => orders.SaveAsync(Document.Of("_id", "o1")));
        Assert.Equal("400-21", refused.RawCode);
        Assert.Contains("total missing", refused.Message, System.StringComparison.Ordinal);
        await orders.SaveAsync(Document.Of("_id", "o2", "total", 5));
        await Eventually.Until(() => Shop.Collection("audit").FindByIdAsync("o2"), row => row is not null);
        await orders.Triggers().DeleteAsync("audit");
        await orders.Triggers().DeleteAsync("guard");
        Assert.Empty(await orders.Triggers().ListAsync());
    }

    [Fact]
    public async Task DeadLetteredTriggerRunsAreListedAndResolved()
    {
        await Shop.Procedures().SaveAsync(new ProcedureDefinition("always_fails", "throw new Error(\"boom\");"));
        var notes = Shop.Collection("notes");
        await notes.CreateAsync();
        await notes.Triggers().SaveAsync(new TriggerDefinition("failing", "always_fails", [TriggerEvent.Created]));
        await notes.SaveAsync(Document.Of("_id", "n1"));
        var runs = fixture.Client.Admin().TriggerRuns();
        var dead = await Eventually.Until(() => runs.ListAsync(TriggerRunStatus.Dead), list => list.Count > 0);
        Assert.True((await runs.ListAsync()).Count >= dead.Count);
        Assert.True(await runs.ResolveAsync(dead[0].RunId, TriggerRunDecision.Discard));
    }

    [Fact]
    public async Task SchedulesAreSavedListedAndDeleted()
    {
        await Shop.Procedures().SaveAsync(new ProcedureDefinition("noop", "return 0;"));
        var schedules = Shop.Schedules();
        Assert.True(await schedules.SaveAsync(new ScheduleDefinition("hourly", "noop") { Cron = "0 * * * *", Args = Document.Of("a", 1) }) > 0);
        await schedules.SaveAsync(new ScheduleDefinition("poll", "noop") { Interval = System.TimeSpan.FromMinutes(5) });
        Assert.Equal(2, (await schedules.ListAsync()).Count);
        Assert.Contains(await schedules.ListAsync(true), schedule => schedule.Raw.Has("args"));
        Assert.Contains(await schedules.ListAsync(), schedule => schedule.Interval == System.TimeSpan.FromMinutes(5));
        await schedules.DeleteAsync("hourly");
        await schedules.DeleteAsync("poll");
        Assert.Empty(await schedules.ListAsync());
    }

    [Fact]
    public async Task ALongScriptIsListedAndCancelled()
    {
        var running = Shop.RunScriptAsync("let i = 0;\nwhile (true) { i++; }");
        var scripts = await Eventually.Until(() => fixture.Client.Admin().Scripts().ListAsync(), list => list.Count > 0);
        Assert.True(await fixture.Client.Admin().Scripts().CancelAsync(scripts[0].RunId));
        await Assert.ThrowsAnyAsync<LwnrdbException>(() => running);
    }

    public sealed class Server : ServerFixture
    {
        protected override IReadOnlyDictionary<string, string> Config => new Dictionary<string, string>
        {
            ["triggersEnabled"] = "true",
            ["triggerMaxAttempts"] = "1",
            ["triggerRetryBackoffMs"] = "100",
        };

        protected override async Task SeedAsync()
        {
            var shop = Client.Database("script_it");
            await shop.CreateAsync();
            await shop.Collection("orders").CreateAsync();
            await shop.Collection("audit").CreateAsync();
        }
    }
}
