using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Tests.Support;
using Xunit;

namespace Lwnrdb.Client.Tests.Linq;

public sealed class ResultShaperTests
{
    private readonly LinqHarness _harness = new();

    [Fact]
    public async Task RowsDecodeIntoEntitiesAndProjections()
    {
        _harness.Reply(new JsonObject { ["_id"] = "a", ["total"] = 3 }, new JsonObject { ["_id"] = "b", ["total"] = 4 });
        var orders = await _harness.Linq.ToListAsync();
        Assert.Equal(["a", "b"], orders.Select(o => o.Id));
        var projected = await _harness.Linq.Select(o => new { o.Id, Double = o.Total * 2 }).Select(x => x.Double).ToArrayAsync();
        Assert.Equal([6.0, 8.0], projected);
        Assert.Equal(["a", "b"], _harness.Linq.Select(o => o.Id).ToList());
        var streamed = new List<string?>();
        await foreach (var order in _harness.Linq.AsAsyncEnumerable())
        {
            streamed.Add(order.Id);
        }
        Assert.Equal(["a", "b"], streamed);
    }

    [Fact]
    public async Task GroupRowsBecomeGroupings()
    {
        _harness.Reply(
            new JsonObject { ["status"] = "new", ["group"] = new JsonArray(new JsonObject { ["_id"] = "a" }, new JsonObject { ["_id"] = "b" }) },
            new JsonObject { ["status"] = "old", ["group"] = new JsonArray(new JsonObject { ["_id"] = "c" }) });
        var groups = await _harness.Linq.GroupBy(o => o.Status).ToListAsync();
        Assert.Equal(["new", "old"], groups.Select(group => group.Key));
        Assert.Equal(["a", "b"], groups[0].Select(o => o.Id));
        var counts = await _harness.Linq.GroupBy(o => o.Status).OrderBy(g => g.Key).Select(g => new { g.Key, Count = g.Count() }).ToListAsync();
        Assert.Equal(2, counts[0].Count);
        Assert.Contains("\"fieldName\":\"status\",\"ascending\":true", _harness.Channel.Last.ToJsonString(), System.StringComparison.Ordinal);
    }

    [Fact]
    public async Task ADottedGroupKeyIsReadFromItsNestedRow()
    {
        _harness.Reply(new JsonObject { ["address"] = new JsonObject { ["city"] = "Lima" }, ["group"] = new JsonArray() });
        var groups = await _harness.Linq.GroupBy(o => o.Address!.City).ToListAsync();
        Assert.Equal("Lima", groups.Single().Key);
        Assert.Empty(groups.Single());
    }

    [Fact]
    public async Task DistinctMemberValuesAreUnwrapped()
    {
        _harness.Reply(new JsonObject { ["status"] = "new" }, new JsonObject { ["status"] = "old" });
        Assert.Equal(["new", "old"], await _harness.Linq.Select(o => o.Status).Distinct().ToListAsync());
    }

    [Fact]
    public async Task AGroupJoinResultSelectorReceivesTheDecodedInnerList()
    {
        _harness.Reply(new JsonObject
        {
            ["_id"] = "o-1",
            ["customerId"] = "c-1",
            ["customer"] = new JsonArray(new JsonObject { ["_id"] = "c-1", ["name"] = "Ana" }),
        });
        var joined = await _harness.Linq
            .GroupJoin(_harness.Customers.AsQueryable(), o => o.CustomerId, c => c.Id, (o, customer) => new { o.Id, Names = customer.Select(c => c.Name).ToList() })
            .Take(5)
            .ToListAsync();
        Assert.Equal("o-1", joined.Single().Id);
        Assert.Equal(["Ana"], joined.Single().Names);
    }

    [Fact]
    public async Task ScalarTerminalsReadTheirRows()
    {
        _harness.Reply(new JsonObject { ["count"] = 7 });
        Assert.Equal(7, await _harness.Linq.CountAsync());
        Assert.Equal(7L, _harness.Linq.LongCount());
        Assert.True(await _harness.Linq.AnyAsync(o => o.Paid));
        Assert.False(await _harness.Linq.AllAsync(o => o.Paid));
        _harness.Reply();
        Assert.Equal(0, await _harness.Linq.CountAsync());
        Assert.True(_harness.Linq.All(o => o.Paid));
        Assert.Null(await _harness.Linq.SingleOrDefaultAsync());
        Assert.Equal(0, await _harness.Linq.Select(o => o.Count).FirstOrDefaultAsync());
    }
}
