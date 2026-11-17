using System.Linq;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Tests.Support;
using Xunit;

namespace Lwnrdb.Client.Tests.Linq;

public sealed class ShapingEdgeTests
{
    private readonly LinqHarness _harness = new();

    [Fact]
    public async Task SingleTerminalsReturnTheOnlyRow()
    {
        _harness.Reply(new JsonObject { ["_id"] = "a", ["count"] = 3 });
        Assert.Equal("a", (await _harness.Linq.SingleAsync()).Id);
        Assert.Equal("a", (await _harness.Linq.SingleOrDefaultAsync())!.Id);
        Assert.Equal("a", (await _harness.Linq.ElementAtAsync(0)).Id);
        Assert.Equal("a", _harness.Linq.ElementAtOrDefault(4)!.Id);
        Assert.Equal(3, await _harness.Linq.CountAsync());
        _harness.Reply(new JsonObject { ["_id"] = "a" });
        Assert.Equal(0, await _harness.Linq.CountAsync());
        _harness.Reply();
        Assert.Null(_harness.Linq.ElementAtOrDefault(4));
    }

    [Fact]
    public async Task GroupKeysConvertAndMissingGroupsAreEmpty()
    {
        _harness.Reply(new JsonObject { ["count"] = 2 }, new JsonObject { ["group"] = new JsonArray(1) });
        var groups = await _harness.Linq.GroupBy(o => (double?)o.Count).ToListAsync();
        Assert.Equal(2.0, groups[0].Key);
        Assert.Empty(groups[0]);
        Assert.Null(groups[1].Key);
        Assert.Empty(groups[1]);
        _harness.Reply(new JsonObject { ["address"] = "flat" });
        Assert.Null((await _harness.Linq.GroupBy(o => o.Address!.City).ToListAsync()).Single().Key);
    }

    [Fact]
    public async Task AGroupJoinWithoutItsFieldReceivesAnEmptyList()
    {
        _harness.Reply(new JsonObject { ["_id"] = "o-1" });
        var joined = await _harness.Linq
            .GroupJoin(_harness.Customers.AsQueryable(), o => o.CustomerId, c => c.Id, (o, customers) => customers.Count())
            .ToListAsync();
        Assert.Equal([0], joined);
    }

    [Fact]
    public void CapturedValuesOfTheWrongKindAreRefused()
    {
        Assert.Throws<System.NotSupportedException>(() => _harness.Filter(o => LwnrdbFunctions.Within(o.Location, null!)));
        Assert.Throws<System.NotSupportedException>(() => _harness.Filter(o => LwnrdbFunctions.Script(null!)));
        var notASequence = 5;
        Assert.Throws<System.NotSupportedException>(() => _harness.Filter(o => Enumerable.Contains(Enumerable.Repeat(notASequence, 1).AsQueryable(), o.Count)));
    }
}
