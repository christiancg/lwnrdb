using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Lwnrdb.Client.IntegrationTests.Support;
using Lwnrdb.Client.Query;
using Xunit;
using static Lwnrdb.Client.Query.Filters;

namespace Lwnrdb.Client.IntegrationTests;

public sealed class QueryIT(QueryIT.Server fixture) : IClassFixture<QueryIT.Server>
{
    private Collection<Document> Orders => fixture.Client.Database("query_it").Collection("orders");

    private static List<string?> Ids(IEnumerable<Document> rows) => [.. rows.Select(row => row.Id)];

    private async Task<T> SameWithAndWithoutIndex<T>(string field, Func<Task<T>> read)
    {
        var scanned = await read();
        await Orders.Indexes().CreateAsync(field);
        try
        {
            var indexed = await read();
            Assert.Equal(scanned, indexed);
            return indexed;
        }
        finally
        {
            await Orders.Indexes().DropAsync(field);
        }
    }

    [Fact]
    public async Task FieldOperatorsAnswerTheSameWithAndWithoutAnIndex()
    {
        Assert.Equal(4, (await SameWithAndWithoutIndex("total", async () => Ids(await Orders.Query().Filter(Field("total").Gte(160)).ToListAsync()).Count)));
        Assert.Equal(2, await SameWithAndWithoutIndex("total", () => Orders.Query().Filter(Field("total").Lt(20)).CountAsync()));
        Assert.Equal(3, await SameWithAndWithoutIndex("total", () => Orders.Query().Filter(Field("total").Lte(20)).CountAsync()));
        Assert.Equal(7, await SameWithAndWithoutIndex("status", () => Orders.Query().Filter(Field("status").Eq("NEW")).CountAsync()));
        Assert.Equal(13, await SameWithAndWithoutIndex("status", () => Orders.Query().Filter(Field("status").Ne("new")).CountAsync()));
        Assert.Equal(20, await SameWithAndWithoutIndex("status", () => Orders.Query().Filter(Field("status").In("new", "paid")).CountAsync()));
        Assert.Equal(13, await SameWithAndWithoutIndex("status", () => Orders.Query().Filter(Field("status").NotIn("new")).CountAsync()));
        Assert.Equal(11, await Orders.Query().Filter(Field("name").Contains("1")).CountAsync());
        Assert.Equal(10, await Orders.Query().Filter(Field("tags").Contains("t1")).CountAsync());
    }

    [Fact]
    public async Task ConjunctionsAndScripts()
    {
        var isNew = Field("status").Eq("new");
        var big = Field("total").Gte(100);
        Assert.Equal(3, await Orders.Query().Filter(And(isNew, big)).CountAsync());
        Assert.Equal(14, await Orders.Query().Filter(Or(isNew, big)).CountAsync());
        Assert.Equal(6, await Orders.Query().Filter(Nor(isNew, big)).CountAsync());
        Assert.Equal(11, await Orders.Query().Filter(Xor(isNew, big)).CountAsync());
        Assert.Equal(17, await Orders.Query().Filter(Nand(isNew, big)).CountAsync());
        Assert.Equal(4, await Orders.Query().Filter(Script("export default (doc) => doc.total > 150;")).CountAsync());
    }

    [Fact]
    public async Task SortSkipLimitAndFirst()
    {
        Assert.Equal(["o-17", "o-16", "o-15"], Ids(await Orders.Query().Sort("total", Client.Query.Order.Desc).Skip(2).Limit(3).ToListAsync()));
        Assert.Equal(["o-00", "o-01"], await SameWithAndWithoutIndex("total", async () => Ids(await Orders.Query().Sort("total").Limit(2).ToListAsync())));
        Assert.Null(await Orders.Query().Filter(Field("total").Gt(1000)).FirstOrDefaultAsync());
        Assert.Empty(await Orders.Query().Filter(Field("total").Gt(1000)).ToListAsync());
    }

    [Fact]
    public async Task GroupByDistinctJoinAndMap()
    {
        var groups = await Orders.Query().GroupBy("status").Sort("status").ToDocumentsAsync();
        Assert.Equal(2, groups.Count);
        Assert.Equal("new", groups[0].GetString("status"));
        Assert.Equal(7, groups[0].GetList("group")!.Count);
        Assert.Equal(2, (await Orders.Query().Distinct("status").ToDocumentsAsync()).Count);
        var joined = (await Orders.Query().Filter(Field("_id").Eq("o-03")).Join("customers", "customerId", "_id", "customer").ToListAsync()).Single();
        Assert.Equal("Quito", ((Document)joined.GetList("customer")![0]!).GetString("city"));
        var mapped = (await Orders.Query()
            .Filter(Field("_id").Eq("o-04"))
            .Map(
                MapOps.Set("line", MidOps.Multiply("qty", "price")),
                MapOps.Set("label", MidOps.Concat("name", MidOps.Literal(" / "), "status")),
                MapOps.Set("text", MidOps.Cast("total", CastType.String)),
                MapOps.Set("flag", MidOps.Sum(1)).When(Field("status").Eq("paid")),
                MapOps.Remove("tags"))
            .ToListAsync()).Single();
        Assert.Equal(2.5, mapped.GetDouble("line"));
        Assert.Equal("Item 4 / paid", mapped.GetString("label"));
        Assert.Equal("40", mapped.GetString("text"));
        Assert.Equal(1, mapped.GetDouble("flag"));
        Assert.False(mapped.Has("tags"));
    }

    [Fact]
    public async Task ReduceAnalyzeAndDirtyReads()
    {
        var sum = (await Orders.Query().Reduce("export default (acc, doc) => acc + doc.total;", 0, "sum").ToDocumentsAsync()).Single();
        Assert.Equal(1900, sum.GetDouble("sum"));
        var analyzed = await Orders.Query().Filter(Field("status").Eq("new")).AnalyzeAsync();
        Assert.Equal(7, analyzed.Results.Count);
        Assert.NotEmpty(analyzed.Analysis.FieldNames);
        Assert.Equal(7, await Orders.Query().Filter(Field("status").Eq("new")).DirtyRead().CountAsync());
    }

    public sealed class Server : ServerFixture
    {
        protected override Task SeedAsync() => Seed.QueryDataAsync(Client, "query_it");
    }
}
