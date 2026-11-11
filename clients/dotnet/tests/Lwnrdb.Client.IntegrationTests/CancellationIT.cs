using System;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.IntegrationTests.Support;
using Xunit;
using static Lwnrdb.Client.Query.Filters;

namespace Lwnrdb.Client.IntegrationTests;

public sealed class CancellationIT(CancellationIT.Server fixture) : IClassFixture<CancellationIT.Server>
{
    [Fact]
    public async Task CancellingALongAggregateClosesOnlyItsConnection()
    {
        var orders = fixture.Client.Database("cancel_it").Collection("orders");
        using var cancel = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));
        var slow = orders.Query().Filter(Script("export default (doc) => { const end = Date.now() + 5000; while (Date.now() < end) {} return true; };"));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => slow.ToListAsync(cancel.Token));
        Assert.Equal(1, await orders.Query().CountAsync());
        await orders.SaveAsync(Document.Of("_id", "after"));
        Assert.Equal(2, await orders.Query().CountAsync());
    }

    [Fact]
    public async Task ACancelledTokenSendsNothing()
    {
        var orders = fixture.Client.Database("cancel_it").Collection("orders");
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => orders.SaveAsync(Document.Of("_id", "never"), new CancellationToken(true)));
        Assert.Null(await orders.FindByIdAsync("never"));
    }

    public sealed class Server : ServerFixture
    {
        protected override System.Collections.Generic.IReadOnlyDictionary<string, string> Config =>
            new System.Collections.Generic.Dictionary<string, string>
            {
                ["aggregationScriptInstructionBudget"] = "2000000000",
                ["scriptTimeoutMs"] = "20000",
            };

        protected override ClientSettings AdjustClient(ClientSettings settings) => settings with { PoolSize = 2 };

        protected override async Task SeedAsync()
        {
            await Client.Database("cancel_it").CreateAsync();
            var orders = Client.Database("cancel_it").Collection("orders");
            await orders.CreateAsync();
            await orders.SaveAsync(Document.Of("_id", "one"));
        }
    }
}
