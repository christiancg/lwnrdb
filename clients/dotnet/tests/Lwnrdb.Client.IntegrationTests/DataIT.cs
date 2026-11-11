using System.Linq;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.IntegrationTests.Support;
using Xunit;

namespace Lwnrdb.Client.IntegrationTests;

public sealed class DataIT(DefaultServer fixture) : IClassFixture<DefaultServer>
{
    private async Task<Collection<Document>> Orders(string name)
    {
        var database = fixture.Client.Database("data_it");
        if (!(await fixture.Client.ListDatabasesAsync()).Contains("data_it"))
        {
            await database.CreateAsync();
        }
        var orders = database.Collection(name);
        await orders.CreateAsync();
        return orders;
    }

    [Fact]
    public async Task SaveFindUpsertAndDelete()
    {
        var orders = await Orders("orders");
        var created = await orders.SaveAsync(Document.Of("_id", "o-1", "status", "new", "total", 10));
        Assert.Equal(new SaveResult("o-1", true), created);
        var generated = await orders.SaveAsync(Document.Of("status", "draft"));
        Assert.True(generated.Inserted);
        Assert.False(string.IsNullOrEmpty(generated.Id));
        var updated = await orders.SaveAsync(Document.Of("_id", "o-1", "status", "paid", "total", 12));
        Assert.False(updated.Inserted);
        var found = await orders.FindByIdAsync("o-1");
        Assert.Equal("paid", found!.GetString("status"));
        Assert.Equal(12, found.GetDouble("total"));
        Assert.Equal("paid", (await orders.FindByIdAsync("o-1", dirtyRead: true))!.GetString("status"));
        await orders.DeleteAsync("o-1");
        Assert.Null(await orders.FindByIdAsync("o-1"));
    }

    [Fact]
    public async Task BulkSaveReportsInsertedAndUpdatedIds()
    {
        var orders = await Orders("bulk");
        await orders.SaveAsync(Document.Of("_id", "b-1"));
        var result = await orders.SaveAllAsync([Document.Of("_id", "b-1", "v", 2), Document.Of("_id", "b-2"), Document.Of("v", 3)]);
        Assert.Equal(["b-1"], result.Updated);
        Assert.Equal(2, result.Inserted.Count);
        Assert.Contains("b-2", result.Inserted);
        await Assert.ThrowsAsync<LwnrdbException>(() => orders.SaveAllAsync([Document.Of("_id", "d"), Document.Of("_id", "d")]));
    }

    [Fact]
    public async Task ATypedCollectionMapsIdAndMembers()
    {
        await Orders("typed");
        var items = fixture.Client.Database("data_it").Collection<Item>("typed");
        await items.SaveAsync(new Item { Id = "i-1", Status = "new", Total = 3, Tags = ["a"] });
        var item = await items.FindByIdAsync("i-1");
        Assert.Equal(("i-1", "new", 3.0), (item!.Id, item.Status, item.Total));
        var raw = await fixture.Client.Database("data_it").Collection("typed").FindByIdAsync("i-1");
        Assert.Equal("new", raw!.GetString("status"));
        Assert.False(raw.Has("id"));
    }

    [Fact]
    public async Task AMissingCollectionAnswersNotFound()
    {
        await Orders("present");
        var missing = fixture.Client.Database("data_it").Collection("absent");
        var error = await Assert.ThrowsAsync<NotFoundException>(() => missing.SaveAsync(Document.Of("a", 1)));
        Assert.Equal("404-11", error.RawCode);
    }
}
