using System;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;
using Xunit;
using static Lwnrdb.Client.Query.Filters;
using SortOrder = Lwnrdb.Client.Query.Order;

namespace Lwnrdb.Client.Tests.Linq;

public sealed class QueryModelBuilderTests
{
    private readonly LinqHarness _harness = new();

    [Fact]
    public void EachOperatorMakesTheStepsTheFluentBuilderMakes()
    {
        var vector = Vector.Of(1, 2);
        var filter = Field("total").Gt(1);
        var linq = _harness.Linq
            .Where(o => o.Status == "a")
            .Where(o => o.Total > 1)
            .OrderBy(o => o.Name)
            .OrderByDescending(o => o.Total)
            .Skip(5)
            .Take(3)
            .Filter(filter)
            .Nearest(o => o.Embedding, vector, 4)
            .DirtyRead()
            .ToQuery();
        var fluent = _harness.Fluent
            .Filter(Field("status").Eq("a"))
            .Filter(Field("total").Gt(1))
            .Sort("name")
            .Sort("total", SortOrder.Desc)
            .Skip(5)
            .Limit(3)
            .Filter(filter)
            .Filter(Nearest("embedding", vector, 4))
            .DirtyRead();
        Assert.Equal(fluent, linq);
        Assert.Equal(_harness.Fluent.Distinct(), _harness.Linq.Distinct().ToQuery());
        Assert.Equal(_harness.Fluent, _harness.Linq.Skip(0).ToQuery());
    }

    [Fact]
    public async Task TerminalsAddTheirSteps()
    {
        await _harness.Linq.CountAsync(o => o.Paid);
        Assert.Equal("[{\"type\":\"FILTER\",\"operator\":{\"fieldOperatorType\":\"EQUALS\",\"field\":\"paid\",\"value\":true}},{\"type\":\"COUNT\"}]", Steps());
        await _harness.Linq.LongCountAsync();
        Assert.Equal("[{\"type\":\"COUNT\"}]", Steps());
        await _harness.Linq.AnyAsync();
        Assert.Equal("[{\"type\":\"LIMIT\",\"limit\":1}]", Steps());
        await _harness.Linq.AllAsync(o => o.Paid);
        Assert.Equal("[{\"type\":\"FILTER\",\"operator\":{\"conjunctionType\":\"NOR\",\"operators\":[{\"fieldOperatorType\":\"EQUALS\",\"field\":\"paid\",\"value\":true}]}},{\"type\":\"LIMIT\",\"limit\":1}]", Steps());
        await _harness.Linq.SingleOrDefaultAsync(o => o.Paid);
        Assert.EndsWith("{\"type\":\"LIMIT\",\"limit\":2}]", Steps(), StringComparison.Ordinal);
        await _harness.Linq.ElementAtAsync(3).ContinueWith(_ => Task.CompletedTask);
        Assert.Equal("[{\"type\":\"SKIP\",\"skip\":3},{\"type\":\"LIMIT\",\"limit\":1}]", Steps());
        await _harness.Linq.FirstOrDefaultAsync(o => o.Paid);
        Assert.EndsWith("{\"type\":\"LIMIT\",\"limit\":1}]", Steps(), StringComparison.Ordinal);
    }

    [Fact]
    public async Task TakeZeroSendsNothing()
    {
        Assert.Empty(await _harness.Linq.Take(0).ToListAsync());
        Assert.Equal(0, await _harness.Linq.Where(o => o.Paid).Take(0).CountAsync());
        Assert.Empty(_harness.Channel.Requests);
    }

    [Fact]
    public async Task FirstAndSingleKeepLinqSemantics()
    {
        await Assert.ThrowsAsync<InvalidOperationException>(() => _harness.Linq.FirstAsync());
        await Assert.ThrowsAsync<InvalidOperationException>(() => _harness.Linq.SingleAsync());
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => _harness.Linq.ElementAtAsync(2));
        Assert.Null(await _harness.Linq.FirstOrDefaultAsync());
        _harness.Reply(new JsonObject { ["_id"] = "a" }, new JsonObject { ["_id"] = "b" });
        await Assert.ThrowsAsync<InvalidOperationException>(() => _harness.Linq.SingleAsync());
        await Assert.ThrowsAsync<InvalidOperationException>(() => _harness.Linq.SingleOrDefaultAsync());
        Assert.Equal("a", (await _harness.Linq.FirstAsync()).Id);
    }

    [Fact]
    public void RefusedOperatorsNameTheExpression()
    {
        Refused(() => _harness.Linq.OrderBy(o => o.Name).ThenBy(o => o.Total).ToQuery(), "one field");
        Refused(() => _harness.Linq.Join(_harness.Customers.AsQueryable(), o => o.CustomerId, c => c.Id, (o, c) => o).ToList(), "GroupJoin");
        Refused(() => _harness.Linq.Sum(o => o.Total), "Reduce");
        Refused(() => _harness.Linq.Last(), "Last");
        Refused(() => _harness.Linq.SelectMany(o => o.Tags).ToList(), "SelectMany");
        Refused(() => _harness.Linq.Select(o => o.Name).Where(n => n == "a").ToList(), "Select");
        Refused(() => _harness.Linq.Where(o => o.Name!.StartsWith('a')).ToList(), "StartsWith");
        Refused(() => _harness.Linq.GroupJoin(_harness.Customers.AsQueryable().Where(c => c.Name == "x"), o => o.CustomerId, c => c.Id, (o, c) => o).ToList(), "operators");
        var other = new Database(_harness.Context, "other").Collection<Customer>("customers");
        Refused(() => _harness.Linq.GroupJoin(other.AsQueryable(), o => o.CustomerId, c => c.Id, (o, c) => o).ToList(), "another database");
        Refused(() => _harness.Linq.GroupBy(o => o.Status).Where(g => g.Count() > 1).ToList(), "Count");
        Refused(() => _harness.Linq.Select(o => o.Name).ToQuery(), "ToQuery");
        Refused(() => _harness.Linq.Where((o, index) => index > 1).ToList(), "index");
    }

    [Fact]
    public void ASelectAfterADistinctOrGroupJoinKeepsTheServerSteps()
    {
        Assert.Throws<NotSupportedException>(() => _harness.Linq.Select(o => o.Name).Distinct().Where(n => n == "a").ToList());
        Assert.Throws<NotSupportedException>(() => _harness.Linq.Select(o => new { o.Name }).Distinct().ToList());
    }

    [Fact]
    public void OperatorsOutsideAnLwnrdbQueryAreRefused()
    {
        var local = Array.Empty<Support.Order>().AsQueryable();
        Assert.Throws<InvalidOperationException>(() => local.ToQuery());
        Assert.Throws<InvalidOperationException>(() => local.DirtyRead());
        Assert.Throws<InvalidOperationException>(() => local.AsAsyncEnumerable());
    }

    private string Steps() => _harness.Channel.Last["aggregationSteps"]!.ToJsonString();

    private static void Refused(Action action, string reason)
    {
        var error = Assert.Throws<NotSupportedException>(action);
        Assert.StartsWith("LWNRDB cannot translate", error.Message, StringComparison.Ordinal);
        Assert.Contains(reason, error.Message, StringComparison.Ordinal);
    }
}
