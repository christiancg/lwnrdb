using System;
using System.Collections.Generic;
using System.Linq;
using System.Linq.Expressions;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;
using Xunit;

namespace Lwnrdb.Client.Tests.Linq;

public sealed class RefusalTests
{
    private readonly LinqHarness _harness = new();

    public static TheoryData<string> Queries() => [.. Cases().Keys];

    private static Dictionary<string, Func<LinqHarness, object?>> Cases() => new()
    {
        ["comparer sort"] = h => h.Linq.OrderBy(o => o.Name, StringComparer.Ordinal).ToList(),
        ["comparer distinct"] = h => h.Linq.Distinct(EqualityComparer<Order>.Default).ToList(),
        ["group element selector"] = h => h.Linq.GroupBy(o => o.Status, o => o.Total).ToList(),
        ["group join comparer"] = h => h.Linq.GroupJoin(h.Customers.AsQueryable(), o => o.CustomerId, c => c.Id, (o, c) => o, StringComparer.Ordinal).ToList(),
        ["group join local inner"] = h => h.Linq.GroupJoin(Array.Empty<Customer>().AsQueryable(), o => o.CustomerId, c => c.Id, (o, c) => o).ToList(),
        ["default value"] = h => h.Linq.FirstOrDefault(new Order()),
        ["reverse"] = h => h.Linq.Reverse().ToList(),
        ["filter after group by"] = h => h.Linq.GroupBy(o => o.Status).Select(g => g.Key).Distinct().ToList(),
        ["where on distinct values"] = h => h.Linq.Select(o => o.Name).Distinct().Where(n => n == "a").ToList(),
        ["where after group join"] = h => h.Linq.GroupJoin(h.Customers.AsQueryable(), o => o.CustomerId, c => c.Id, (o, c) => o).Where(o => o.Paid).ToList(),
        ["group by after group by"] = h => h.Linq.GroupBy(o => o.Status).GroupBy(g => g.Key).ToList(),
        ["nearest after select"] = h => h.Linq.Select(o => o).Nearest(o => o.Embedding, Vector.Of(1), 1).ToList(),
        ["computed group key"] = h => h.Linq.GroupBy(o => o.Total * 2).ToList(),
        ["computed sort key"] = h => h.Linq.OrderBy(o => o.Total * 2).ToList(),
        ["sort on group element"] = h => h.Linq.GroupBy(o => o.Status).OrderBy(g => g.Count()).ToList(),
        ["contains on a query"] = h => h.Linq.Where(o => h.Linq.Select(x => x.Name).Contains(o.Name)).ToList(),
        ["distance not equal"] = h => h.Linq.Where(o => LwnrdbFunctions.Distance(o.Location, o.Location) < 5).ToList(),
        ["within a member"] = h => h.Linq.Where(o => LwnrdbFunctions.Within(o.Location, new[] { o.Location })).ToList(),
        ["script from a member"] = h => h.Linq.Where(o => LwnrdbFunctions.Script(o.Name!)).ToList(),
        ["distance alone"] = h => h.Linq.Where(o => LwnrdbFunctions.Distance(o.Location, new GeoPoint(0, 0)) == LwnrdbFunctions.Distance(o.Location, new GeoPoint(1, 1))).ToList(),
        ["equals with ordinal"] = h => h.Linq.Where(o => o.Name!.Equals("a", StringComparison.Ordinal)).ToList(),
        ["not a lwnrdb source"] = h => h.Linq.Where(o => true).Provider.Execute<int>(Expression.Constant(1)),
        ["select with index"] = h => h.Linq.Select((o, i) => o).ToList(),
    };

    [Theory]
    [MemberData(nameof(Queries))]
    public void EachUntranslatableQueryIsRefused(string name)
    {
        var error = Assert.Throws<NotSupportedException>(() => Cases()[name](_harness));
        Assert.StartsWith("LWNRDB cannot translate", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ANonGenericCreateQueryFindsTheElementType()
    {
        var provider = _harness.Linq.Provider;
        Assert.Equal(typeof(Order), provider.CreateQuery(_harness.Linq.Where(o => o.Paid).Expression).ElementType);
        var grouped = provider.CreateQuery(_harness.Linq.GroupBy(o => o.Status).Expression);
        Assert.Equal(typeof(IGrouping<string, Order>), grouped.ElementType);
        Assert.Throws<ArgumentNullException>(() => provider.CreateQuery(null!));
        Assert.Throws<ArgumentNullException>(() => provider.Execute(null!));
    }
}
