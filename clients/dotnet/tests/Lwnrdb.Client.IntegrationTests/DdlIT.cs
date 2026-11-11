using System.Linq;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.IntegrationTests.Support;
using Xunit;

namespace Lwnrdb.Client.IntegrationTests;

public sealed class DdlIT(DefaultServer fixture) : IClassFixture<DefaultServer>
{
    [Fact]
    public async Task DatabasesAndCollectionsAreCreatedListedAndDropped()
    {
        var database = fixture.Client.Database("ddl_it");
        await database.CreateAsync();
        Assert.Contains("ddl_it", await fixture.Client.ListDatabasesAsync());
        await Assert.ThrowsAsync<ConflictException>(() => database.CreateAsync());
        await database.Collection("orders").CreateAsync();
        await database.Collection("lines").CreateAsync();
        Assert.Equal(["lines", "orders"], [.. (await database.ListCollectionsAsync()).Order()]);
        await database.Collection("lines").DropAsync();
        Assert.Equal(["orders"], await database.ListCollectionsAsync());
        Assert.NotNull((await database.StatsAsync()).Get("collections") ?? (object)true);
        Assert.NotEqual(Document.Empty, await fixture.Client.DatabaseStatsAsync());
        var info = await fixture.Client.ServerInfoAsync();
        Assert.NotNull(info.State ?? info.Version);
        await database.DropAsync();
        Assert.DoesNotContain("ddl_it", await fixture.Client.ListDatabasesAsync());
    }

    [Fact]
    public async Task OwnersIndexesAndSchemas()
    {
        var database = fixture.Client.Database("ddl_owned");
        await database.CreateAsync();
        await fixture.Client.Admin().Users().CreateAsync("owner_it", "Owner-Secret-1234");
        await database.SetOwnersAsync(["owner_it"]);
        var orders = database.Collection("orders");
        await orders.CreateAsync();
        await orders.Indexes().CreateAsync("status", "total");
        await orders.SaveAsync(Document.Of("_id", "o", "status", "new", "total", 1));
        Assert.Contains("status", (await orders.Indexes().ReindexAsync("status")).RebuiltFields);
        Assert.Equal(1, (await orders.Indexes().ReindexPrimaryKeyAsync()).PrimaryKeyEntries);
        await orders.Indexes().DropAsync("total");
        Assert.Null(await orders.Schema().GetAsync());
        await orders.Schema().SaveAsync(Document.Parse("{\"type\":\"object\",\"required\":[\"status\"]}"));
        Assert.Equal("object", (await orders.Schema().GetAsync())!.GetString("type"));
        var refused = await Assert.ThrowsAsync<LwnrdbException>(() => orders.SaveAsync(Document.Of("_id", "x")));
        Assert.Equal("400-7", refused.RawCode);
        await orders.Schema().DeleteAsync();
        await orders.SaveAsync(Document.Of("_id", "x"));
    }
}
