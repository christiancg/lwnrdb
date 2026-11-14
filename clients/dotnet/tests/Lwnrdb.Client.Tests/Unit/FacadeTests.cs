using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Lwnrdb.Client.Admin;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit;

public sealed class FacadeTests
{
    private readonly Recorder _recorder = new();

    private void Reply(string field, JsonNode value) =>
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, new JsonObject { [field] = value.DeepClone() });

    [Fact]
    public async Task DeleteWhereSendsTheConditionAndReadsTheCount()
    {
        Reply("deletedCount", JsonValue.Create(3));
        Assert.Equal(3, await _recorder.Orders.DeleteWhereAsync(Filters.Field("status").Eq("archived")));
        var condition = _recorder.Channel.Last["condition"]!;
        Assert.Equal("EQUALS", condition["fieldOperatorType"]!.GetValue<string>());
        Assert.Equal("status", condition["field"]!.GetValue<string>());
        Assert.Equal(3, await _recorder.TypedOrders.DeleteWhereAsync(order => order.Total < 100));
        Assert.Equal("SMALLER_THAN", _recorder.Channel.Last["condition"]!["fieldOperatorType"]!.GetValue<string>());
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request);
        Assert.Equal(0, await _recorder.Orders.DeleteWhereAsync(Filters.Field("status").Eq("archived")));
        await Assert.ThrowsAsync<ArgumentNullException>(() => _recorder.Orders.DeleteWhereAsync((Filter)null!));
        await Assert.ThrowsAsync<NotSupportedException>(
            () => _recorder.TypedOrders.DeleteWhereAsync(order => order.Total < order.Discount));
    }

    [Fact]
    public async Task UpdateWhereSendsTheConditionAndThePatchAndReadsTheCount()
    {
        Reply("updatedCount", JsonValue.Create(2));
        Assert.Equal(2, await _recorder.Orders.UpdateWhereAsync(Filters.Field("status").Eq("pending"), new { Status = "expired", Id = "x" }));
        Assert.Equal("SAVE", _recorder.Channel.Last["type"]!.GetValue<string>());
        Assert.Equal("EQUALS", _recorder.Channel.Last["condition"]!["fieldOperatorType"]!.GetValue<string>());
        Assert.Equal("""{"status":"expired","_id":"x"}""", _recorder.Channel.Last["object"]!.ToJsonString());
        Assert.Equal(2, await _recorder.TypedOrders.UpdateWhereAsync(order => order.Total < 100, Document.Of("paid", true)));
        Assert.Equal("SMALLER_THAN", _recorder.Channel.Last["condition"]!["fieldOperatorType"]!.GetValue<string>());
        Assert.Equal("""{"paid":true}""", _recorder.Channel.Last["object"]!.ToJsonString());
        await _recorder.Orders.UpdateWhereAsync(Filters.Field("paid").Eq(true), new Dictionary<string, object?> { ["Status"] = "done" });
        Assert.Equal("""{"Status":"done"}""", _recorder.Channel.Last["object"]!.ToJsonString());
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request);
        Assert.Equal(0, await _recorder.Orders.UpdateWhereAsync(Filters.Field("status").Eq("pending"), new { Paid = true }));
    }

    [Fact]
    public async Task UpdateWhereRefusesWhatCannotBeSent()
    {
        var pending = Filters.Field("status").Eq("pending");
        await Assert.ThrowsAsync<ArgumentNullException>(() => _recorder.Orders.UpdateWhereAsync((Filter)null!, new { Paid = true }));
        await Assert.ThrowsAsync<ArgumentNullException>(() => _recorder.Orders.UpdateWhereAsync(pending, null!));
        await Assert.ThrowsAsync<MappingException>(() => _recorder.Orders.UpdateWhereAsync(pending, "expired"));
        var cycle = new Dictionary<string, object?>();
        cycle["self"] = cycle;
        await Assert.ThrowsAsync<MappingException>(() => _recorder.Orders.UpdateWhereAsync(pending, cycle));
        await Assert.ThrowsAsync<MappingException>(() => _recorder.Orders.UpdateWhereAsync(pending, new { Big = 9007199254740993L }));
        await Assert.ThrowsAsync<NotSupportedException>(
            () => _recorder.TypedOrders.UpdateWhereAsync(order => order.Total < order.Discount, new { Paid = true }));
        Assert.Empty(_recorder.Channel.Requests);
    }

    [Fact]
    public async Task AdminListsDecodeTheirRows()
    {
        Reply("users", new JsonArray(new JsonObject
        {
            ["_id"] = "bob",
            ["admin"] = true,
            ["globalPermissions"] = new JsonArray("CREATE_DATABASE"),
            ["databasePermissions"] = new JsonObject { ["shop"] = "READ" },
            ["ownedDatabases"] = new JsonArray("shop"),
        }));
        var user = (await _recorder.Admin.Users().ListAsync()).Single();
        Assert.Equal("bob", user.Username);
        Assert.True(user.IsAdmin);
        Assert.Equal(["CREATE_DATABASE"], user.GlobalPermissions);
        Assert.Equal("READ", user.DatabasePermissions.GetString("shop"));
        Assert.Equal(Document.Empty, user.CollectionPermissions);
        Assert.Equal(["shop"], user.OwnedDatabases);
        Reply("transactions", new JsonArray(new JsonObject { ["dtxId"] = "d", ["coordinator"] = "n1", ["participants"] = new JsonArray("n2"), ["ageMs"] = 5 }));
        var transaction = (await _recorder.Admin.Transactions().ListAsync()).Single();
        Assert.Equal(("d", "n1", false, 5L), (transaction.DtxId, transaction.Coordinator, transaction.CoordinatorReachable, transaction.AgeMs));
        Reply("scripts", new JsonArray(new JsonObject { ["runId"] = "r", ["kind"] = "RUN_SCRIPT", ["database"] = "shop", ["username"] = "bob" }));
        var run = (await _recorder.Admin.Scripts().ListAsync()).Single();
        Assert.Equal(("r", "RUN_SCRIPT", "shop", "bob", 0L), (run.RunId, run.Kind, run.Database, run.Username, run.AgeMs));
        Reply("runs", new JsonArray(new JsonObject { ["runId"] = "t", ["status"] = "DEAD", ["attempts"] = 3, ["lastError"] = "boom", ["trigger"] = "audit" }));
        var triggerRun = (await _recorder.Admin.TriggerRuns().ListAsync()).Single();
        Assert.Equal(("t", "DEAD", 3L, "boom", "audit"), (triggerRun.RunId, triggerRun.Status, triggerRun.Attempts, triggerRun.LastError, triggerRun.Trigger));
        Reply("resolved", true);
        Assert.True(await _recorder.Admin.TriggerRuns().ResolveAsync("t", TriggerRunDecision.Replay));
        Reply("cancelled", true);
        Assert.True(await _recorder.Admin.Scripts().CancelAsync("r"));
    }

    [Fact]
    public async Task DescriptorsFillDefaultsForMissingFields()
    {
        Reply("triggers", new JsonArray(new JsonObject { ["name"] = "t", ["timing"] = "before", ["mode"] = "batch", ["enabled"] = false, ["collection"] = "orders" }));
        var trigger = (await _recorder.Shop.ListTriggersAsync()).Single();
        Assert.Equal(("before", "batch", false, "orders"), (trigger.Timing, trigger.Mode, trigger.Enabled, trigger.Collection));
        Assert.Equal(string.Empty, trigger.ProcedureName);
        Reply("schedules", new JsonArray(new JsonObject { ["name"] = "s", ["cron"] = "* * * * *", ["enabled"] = false }));
        var schedule = (await _recorder.Shop.Schedules().ListAsync(true)).Single();
        Assert.Equal(("* * * * *", false, null, null), (schedule.Cron, schedule.Enabled, schedule.Interval, schedule.NextRunAt));
        Reply("procedures", new JsonArray(new JsonObject()));
        var procedure = (await _recorder.Shop.Procedures().ListAsync()).Single();
        Assert.Equal((string.Empty, 0L, true, null), (procedure.Name, procedure.Version, procedure.Enabled, procedure.Description));
        Reply("users", new JsonArray(new JsonObject { ["_id"] = "x", ["globalPermissions"] = new JsonArray(1, null) }));
        var user = (await _recorder.Admin.Users().ListAsync()).Single();
        Assert.False(user.IsAdmin);
        Assert.Equal(["1", "null"], user.GlobalPermissions);
        Reply("transactions", new JsonArray(new JsonObject { ["coordinatorReachable"] = true }));
        Assert.True((await _recorder.Admin.Transactions().ListAsync()).Single().CoordinatorReachable);
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request);
        Assert.Empty(await _recorder.Admin.Scripts().ListAsync());
        Assert.False(await _recorder.Admin.Scripts().CancelAsync("r"));
        Assert.Equal(0, await _recorder.Shop.Procedures().SaveAsync(new Scripting.ProcedureDefinition("p", "s")));
        Assert.Equal(string.Empty, (await _recorder.Orders.Triggers().SaveAsync(new Scripting.TriggerDefinition("t", "p", [Scripting.TriggerEvent.Created]))).Definer);
        var result = await _recorder.Shop.Procedures().CallAsync("p");
        Assert.Null(result.Value);
        Assert.Empty(result.Logs);
    }

    [Fact]
    public async Task AnEmptyUserListIsAnEmptyList()
    {
        _recorder.Channel.Replies = request => RecordingChannel.Error(request, "NOT_FOUND", "404-5");
        Assert.Empty(await _recorder.Admin.Users().ListAsync());
        _recorder.Channel.Replies = request => RecordingChannel.Error(request, "FORBIDDEN", "403-1");
        await Assert.ThrowsAsync<ForbiddenException>(() => _recorder.Admin.Users().ListAsync());
    }

    [Fact]
    public async Task ScriptingDescriptorsDecodeTheirRows()
    {
        Reply("procedures", new JsonArray(new JsonObject { ["name"] = "p", ["version"] = 2, ["enabled"] = false, ["source"] = "x" }));
        var procedure = (await _recorder.Shop.Procedures().ListAsync(true)).Single();
        Assert.Equal(("p", 2L, false, "x"), (procedure.Name, procedure.Version, procedure.Enabled, procedure.Source));
        Reply("triggers", new JsonArray(new JsonObject { ["name"] = "t", ["events"] = new JsonArray("CREATED"), ["procedureName"] = "p" }));
        var trigger = (await _recorder.Orders.Triggers().ListAsync()).Single();
        Assert.Equal(("t", "after", "document", true), (trigger.Name, trigger.Timing, trigger.Mode, trigger.Enabled));
        Assert.Equal(["CREATED"], trigger.Events);
        Reply("schedules", new JsonArray(new JsonObject { ["name"] = "s", ["procedureName"] = "p", ["intervalMs"] = 60000, ["nextRunAt"] = 1_700_000_000_000 }));
        var schedule = (await _recorder.Shop.Schedules().ListAsync()).Single();
        Assert.Equal(TimeSpan.FromMinutes(1), schedule.Interval);
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000), schedule.NextRunAt);
        Reply("version", 4);
        Assert.Equal(4, await _recorder.Shop.Procedures().SaveAsync(new Scripting.ProcedureDefinition("p", "s")));
        Assert.Equal(4, await _recorder.Shop.Schedules().SaveAsync(new Scripting.ScheduleDefinition("s", "p") { Cron = "* * * * *" }));
        Assert.Equal(4, (await _recorder.Orders.Triggers().SaveAsync(new Scripting.TriggerDefinition("t", "p", [Scripting.TriggerEvent.Created]))).Version);
    }

    [Fact]
    public async Task ScriptResultsDecodeTheirValue()
    {
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, new JsonObject
        {
            ["result"] = new JsonObject { ["at"] = "#geo(1,2)" },
            ["logs"] = new JsonArray("hello"),
            ["logsTruncated"] = true,
            ["runId"] = "r-1",
        });
        var result = await _recorder.Shop.RunScriptAsync("return 1;");
        Assert.Equal(new GeoPoint(1, 2), ((Document)result.Value!).GetGeoPoint("at"));
        Assert.Equal(["hello"], result.Logs);
        Assert.True(result.LogsTruncated);
        Assert.Equal("r-1", result.RunId);
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, new JsonObject
        {
            ["decision"] = "allow",
            ["document"] = new JsonObject { ["_id"] = "o" },
            ["logs"] = new JsonArray(),
        });
        var test = await _recorder.Orders.Triggers().TestAsync("t", Scripting.TriggerEvent.Updated, Document.Of("_id", "o"));
        Assert.Equal("allow", test.Decision);
        Assert.Equal("o", test.Document!.Id);
    }

    [Fact]
    public async Task DataCallsDecodeTheirReplies()
    {
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, new JsonObject
        {
            ["_id"] = "o-1",
            ["inserted"] = true,
            ["updated"] = new JsonArray("a"),
            ["object"] = new JsonObject { ["_id"] = "o-1", ["total"] = 1 },
            ["rebuiltFields"] = new JsonArray("email"),
            ["primaryKeyEntries"] = 9,
            ["warnings"] = new JsonArray("w"),
            ["schema"] = new JsonObject { ["type"] = "object" },
            ["collections"] = new JsonArray("orders"),
            ["stats"] = new JsonObject { ["documents"] = 3 },
        });
        Assert.Equal(new SaveResult("o-1", true), await _recorder.Orders.SaveAsync(Document.Empty));
        Assert.Equal(["a"], (await _recorder.Orders.SaveAllAsync([Document.Empty])).Updated);
        Assert.Equal(1, (await _recorder.TypedOrders.FindByIdAsync("o-1"))!.Total);
        Assert.Equal(9, (await _recorder.Orders.Indexes().ReindexPrimaryKeyAsync()).PrimaryKeyEntries);
        Assert.Equal(["email"], (await _recorder.Orders.Indexes().ReindexAsync("email")).RebuiltFields);
        Assert.Equal(["w"], await _recorder.Orders.Schema().SaveAsync(Document.Of("type", "object")));
        Assert.Equal("object", (await _recorder.Orders.Schema().GetAsync())!.GetString("type"));
        Assert.Equal(["orders"], await _recorder.Shop.ListCollectionsAsync());
        Assert.Equal(3, (await _recorder.Shop.StatsAsync()).GetDouble("documents"));
        Assert.Equal("shop", _recorder.Orders.DatabaseName);
        Assert.Equal("orders", _recorder.Orders.Name);
        Assert.Equal("shop", _recorder.Shop.Name);
        _recorder.Channel.Replies = request => RecordingChannel.Error(request, "NOT_FOUND", "404-2");
        Assert.Null(await _recorder.Orders.FindByIdAsync("missing", dirtyRead: true));
        _recorder.Channel.Replies = request => RecordingChannel.Error(request, "NOT_FOUND", "404-8");
        Assert.Null(await _recorder.Orders.Schema().GetAsync());
        await Assert.ThrowsAsync<ArgumentException>(() => _recorder.Orders.SaveAsync(Document.Of("_id", "bad id")));
        await Assert.ThrowsAsync<ArgumentException>(() => _recorder.Orders.Indexes().CreateAsync([]));
    }

    [Fact]
    public async Task SchedulesAndTriggersCheckTheirDefinitions()
    {
        await Assert.ThrowsAsync<ArgumentException>(() => _recorder.Shop.Schedules().SaveAsync(new Scripting.ScheduleDefinition("s", "p")));
        await Assert.ThrowsAsync<ArgumentException>(() => _recorder.Shop.Schedules().SaveAsync(
            new Scripting.ScheduleDefinition("s", "p") { Cron = "*", Interval = TimeSpan.FromSeconds(1) }));
        await Assert.ThrowsAsync<ArgumentException>(() => _recorder.Orders.Triggers().SaveAsync(new Scripting.TriggerDefinition("t", "p", [])));
    }

    [Fact]
    public void EveryExceptionHasTheStandardConstructors()
    {
        foreach (var type in typeof(LwnrdbException).Assembly.GetExportedTypes().Where(typeof(LwnrdbException).IsAssignableFrom))
        {
            var empty = (LwnrdbException)Activator.CreateInstance(type)!;
            var message = (LwnrdbException)Activator.CreateInstance(type, "m")!;
            var inner = (LwnrdbException)Activator.CreateInstance(type, "m", new InvalidOperationException())!;
            var full = (LwnrdbException)Activator.CreateInstance(type, "m", "400-1", Protocol.Status.Error, "SAVE", null)!;
            Assert.Null(empty.RawCode);
            Assert.Equal("m", message.Message);
            Assert.IsType<InvalidOperationException>(inner.InnerException);
            Assert.Equal(ErrorCode.ValidationError, full.Code);
            Assert.True(full.HasCode(ErrorCode.ValidationError));
            Assert.Equal("SAVE", full.Operation);
        }
        Assert.Equal("VALIDATION_ERROR (400-1)", ErrorCode.ValidationError.ToString());
        Assert.Null(ErrorCode.Of("999-9"));
        Assert.Equal(Protocol.Operation.Save, Protocol.Operation.Of("SAVE"));
        Assert.Null(Protocol.Operation.Of(null));
        Assert.Equal("SAVE", Protocol.Operation.Save.ToString());
    }

    [Fact]
    public async Task UnknownStatusesAndCodesStillRaise()
    {
        _recorder.Channel.Replies = _ => new JsonObject { ["type"] = "SAVE", ["status"] = "WEIRD" };
        var error = await Assert.ThrowsAsync<LwnrdbException>(() => _recorder.Orders.SaveAsync(Document.Empty));
        Assert.Equal("request failed", error.Message);
        _recorder.Channel.Replies = _ => new JsonObject { ["type"] = "SAVE", ["status"] = "FORBIDDEN" };
        await Assert.ThrowsAsync<ForbiddenException>(() => _recorder.Orders.SaveAsync(Document.Empty));
        _recorder.Channel.Replies = _ => new JsonObject { ["type"] = "SAVE", ["status"] = "UNAUTHENTICATED", ["errorCode"] = "999-1" };
        await Assert.ThrowsAsync<AuthenticationException>(() => _recorder.Orders.SaveAsync(Document.Empty));
        _recorder.Channel.Replies = _ => new JsonObject { ["type"] = "SAVE", ["status"] = "NOT_FOUND", ["message"] = new JsonObject() };
        await Assert.ThrowsAsync<NotFoundException>(() => _recorder.Orders.SaveAsync(Document.Empty));
        _recorder.Channel.Replies = _ => new JsonObject { ["type"] = "SAVE", ["status"] = "ERROR", ["errorCode"] = "400-2" };
        Assert.Contains("Entry size", (await Assert.ThrowsAsync<LwnrdbException>(() => _recorder.Orders.SaveAsync(Document.Empty))).Message, StringComparison.Ordinal);
    }
}
