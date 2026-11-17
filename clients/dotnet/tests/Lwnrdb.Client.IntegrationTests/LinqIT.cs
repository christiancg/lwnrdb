using System;
using System.Collections.Generic;
using System.Linq;
using System.Linq.Expressions;
using System.Threading.Tasks;
using Lwnrdb.Client.IntegrationTests.Support;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Values;
using Xunit;
using static Lwnrdb.Client.Query.Filters;

namespace Lwnrdb.Client.IntegrationTests;

public sealed class LinqIT(LinqIT.Server fixture) : IClassFixture<LinqIT.Server>
{
    private static readonly string[] NewOrPaid = ["new", "paid"];
    private static readonly string[] OnlyNew = ["new"];

    private Collection<Item> Items => fixture.Client.Database("linq_it").Collection<Item>("orders");

    private IQueryable<Item> Linq => Items.AsQueryable();

    public static TheoryData<string> Cases() => [.. Matrix().Keys];

    private static Dictionary<string, (Expression<Func<Item, bool>> Linq, Filter Fluent)> Matrix() => new()
    {
        ["greater_than"] = (o => o.Total > 100, Field("total").Gt(100)),
        ["smaller_than_flipped"] = (o => 50 > o.Total, Field("total").Lt(50)),
        ["and"] = (o => o.Total >= 100 && o.Status == "new", And(Field("total").Gte(100), Field("status").Eq("new"))),
        ["or"] = (o => o.Status == "new" || o.Total < 20, Or(Field("status").Eq("new"), Field("total").Lt(20))),
        ["xor"] = (o => o.Status == "new" ^ o.Total >= 100, Xor(Field("status").Eq("new"), Field("total").Gte(100))),
        ["not_comparison"] = (o => !(o.Total < 50), Nor(Field("total").Lt(50))),
        ["not_and"] = (o => !(o.Paid && o.Total > 100), Nand(Field("paid").Eq(true), Field("total").Gt(100))),
        ["not_member"] = (o => !o.Paid, Field("paid").Eq(false)),
        ["in"] = (o => NewOrPaid.Contains(o.Status), Field("status").In("new", "paid")),
        ["not_in"] = (o => !OnlyNew.Contains(o.Status), Field("status").NotIn("new")),
        ["contains_string"] = (o => o.Name!.Contains("1"), Field("name").Contains("1")),
        ["contains_array"] = (o => o.Tags.Contains("t1"), Field("tags").Contains("t1")),
        ["null"] = (o => o.Discount == null, Field("discount").Eq(null)),
        ["not_null"] = (o => o.Discount != null, Field("discount").Ne(null)),
        ["has_value"] = (o => o.Discount.HasValue, Field("discount").Ne(null)),
        ["distance"] = (o => LwnrdbFunctions.Distance(o.Location, new GeoPoint(40, -74)) < 5000, Distance("location", new GeoPoint(40, -74), GeoDistanceComparator.SmallerThan, 5000)),
        ["within"] = (o => LwnrdbFunctions.Within(o.Location, new GeoPoint(39.9, -74.1), new GeoPoint(40.1, -74.1), new GeoPoint(40.1, -73.9), new GeoPoint(39.9, -73.9)),
            Within("location", new GeoPoint(39.9, -74.1), new GeoPoint(40.1, -74.1), new GeoPoint(40.1, -73.9), new GeoPoint(39.9, -73.9))),
    };

    private static List<string> Multiset(IEnumerable<Item> rows) => [.. rows.Select(row => row.Id!).Order(StringComparer.Ordinal)];

    [Theory]
    [MemberData(nameof(Cases))]
    public async Task EachLinqPredicateAnswersLikeItsFluentFilter(string name)
    {
        var (linq, fluent) = Matrix()[name];
        var expected = Multiset(await Items.Query().Filter(fluent).ToListAsync());
        Assert.NotEmpty(expected);
        Assert.Equal(expected, Multiset(await Linq.Where(linq).ToListAsync()));
        Assert.Equal(expected.Count, await Linq.CountAsync(linq));
    }

    [Fact]
    public async Task SortSkipTakeAndTerminalsMatchTheFluentBuilder()
    {
        var ordered = await Linq.OrderByDescending(o => o.Total).Skip(2).Take(3).Select(o => o.Id).ToListAsync();
        var fluent = await Items.Query().Sort("total", Client.Query.Order.Desc).Skip(2).Limit(3).ToListAsync();
        Assert.Equal(fluent.Select(o => o.Id), ordered);
        Assert.True(await Linq.AnyAsync(o => o.Total > 150));
        Assert.False(await Linq.AnyAsync(o => o.Total > 1000));
        Assert.True(await Linq.AllAsync(o => o.Total >= 0));
        Assert.False(await Linq.AllAsync(o => o.Paid));
        Assert.Equal("o-19", (await Linq.OrderByDescending(o => o.Total).FirstAsync()).Id);
        Assert.Equal("o-04", (await Linq.SingleAsync(o => o.Id == "o-04")).Id);
        await Assert.ThrowsAsync<InvalidOperationException>(() => Linq.SingleAsync(o => o.Status == "new"));
        Assert.Null(await Linq.FirstOrDefaultAsync(o => o.Total > 1000));
        Assert.Equal("o-03", (await Linq.OrderBy(o => o.Total).ElementAtAsync(3)).Id);
        Assert.Equal(20L, await Linq.LongCountAsync());
    }

    [Fact]
    public async Task GroupByDistinctAndGroupJoinShapeTheirRows()
    {
        var counts = await Linq.GroupBy(o => o.Status).OrderBy(g => g.Key).Select(g => new { g.Key, Count = g.Count() }).ToListAsync();
        Assert.Equal([("new", 7), ("paid", 13)], counts.Select(c => (c.Key!, c.Count)));
        Assert.Equal(["new", "paid"], (await Linq.Select(o => o.Status).Distinct().ToListAsync()).Order());
        var customers = fixture.Client.Database("linq_it").Collection<Customer>("customers").AsQueryable();
        var joined = await Linq.Where(o => o.Id == "o-03")
            .GroupJoin(customers, o => o.CustomerId, c => c.Id, (o, customer) => new { o.Id, City = customer.Single().City })
            .ToListAsync();
        Assert.Equal("Quito", joined.Single().City);
    }

    [Fact]
    public async Task NearestRanksTheClosestVectors()
    {
        var vectors = fixture.Client.Database("linq_it").Collection<Item>("vectors");
        var nearest = await vectors.AsQueryable().Nearest(o => o.Embedding, Vector.Of(1, 0), 2, exact: true).ToListAsync();
        Assert.Equal(["v-1", "v-2"], nearest.Select(o => o.Id).Order());
        var fluent = await vectors.Query().Filter(Nearest("embedding", Vector.Of(1, 0), 2).Exact()).ToListAsync();
        Assert.Equal(Multiset(fluent), Multiset(nearest));
    }

    [Fact]
    public async Task StringEqualityIgnoresCaseExceptOnTheId()
    {
        Assert.Equal(7, await Linq.CountAsync(o => o.Status == "NEW"));
        Assert.Equal(0, await Linq.CountAsync(o => o.Id == "O-01"));
        Assert.Equal(1, await Linq.CountAsync(o => o.Id == "o-01"));
    }

    [Fact]
    public async Task ACapturedVariableIsReadOnEachExecution()
    {
        var threshold = 150.0;
        var query = Linq.Where(o => o.Total > threshold);
        Assert.Equal(4, await query.CountAsync());
        threshold = 100;
        Assert.Equal(9, await query.CountAsync());
        Assert.Equal(9, query.Count());
    }

    public sealed class Server : ServerFixture
    {
        protected override async Task SeedAsync()
        {
            await Seed.QueryDataAsync(Client, "linq_it");
            var vectors = Client.Database("linq_it").Collection<Item>("vectors");
            await vectors.CreateAsync();
            await vectors.SaveAllAsync([
                new Item { Id = "v-1", Embedding = Vector.Of(1, 0) },
                new Item { Id = "v-2", Embedding = Vector.Of(0.9, 0.1) },
                new Item { Id = "v-3", Embedding = Vector.Of(0, 1) },
            ]);
        }
    }
}
