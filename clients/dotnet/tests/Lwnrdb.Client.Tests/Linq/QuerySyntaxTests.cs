using System.Linq;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Tests.Support;
using Xunit;

namespace Lwnrdb.Client.Tests.Linq;

public sealed class QuerySyntaxTests
{
    private readonly LinqHarness _harness = new();

    [Fact]
    public void WhereOrderBySelectTranslateLikeMethodSyntax()
    {
        var querySyntax = from o in _harness.Linq where o.Total > 10 orderby o.Name descending select o;
        var methodSyntax = _harness.Linq.Where(o => o.Total > 10).OrderByDescending(o => o.Name);
        Assert.Equal(methodSyntax.ToQuery(), querySyntax.ToQuery());
    }

    [Fact]
    public void GroupIntoSelectTranslatesLikeMethodSyntax()
    {
        _ = (from o in _harness.Linq group o by o.Status into g select new { g.Key, Count = g.Count() }).ToList();
        var querySyntax = _harness.Channel.Last.ToJsonString();
        _ = _harness.Linq.GroupBy(o => o.Status).Select(g => new { g.Key, Count = g.Count() }).ToList();
        Assert.Equal(_harness.Channel.Last.ToJsonString(), querySyntax);
    }

    [Fact]
    public void JoinIntoTranslatesToAGroupJoin()
    {
        _ = (from o in _harness.Linq
             join c in _harness.Customers.AsQueryable() on o.CustomerId equals c.Id into customer
             select new { o, customer }).ToList();
        Assert.Contains("\"asField\":\"customer\"", _harness.Channel.Last.ToJsonString(), System.StringComparison.Ordinal);
    }
}
