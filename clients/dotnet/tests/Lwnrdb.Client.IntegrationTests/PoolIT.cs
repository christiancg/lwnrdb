using System;
using System.Linq;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.IntegrationTests.Support;
using Xunit;

namespace Lwnrdb.Client.IntegrationTests;

public sealed class PoolIT(DefaultServer fixture) : IClassFixture<DefaultServer>
{
    [Fact]
    public async Task ManyConcurrentWritersShareASmallPool()
    {
        await fixture.Client.Database("pool_it").CreateAsync();
        await fixture.Client.Database("pool_it").Collection("events").CreateAsync();
        await using var client = await LwnrdbClient.ConnectAsync(fixture.Settings(settings => settings with { PoolSize = 4 }));
        var events = client.Database("pool_it").Collection("events");
        await Task.WhenAll(Enumerable.Range(0, 32).Select(async writer =>
        {
            for (var index = 0; index < 25; index++)
            {
                await events.SaveAsync(Document.Of("_id", $"e-{writer}-{index}"));
            }
        }));
        Assert.Equal(800, await events.Query().CountAsync());
    }

    [Fact]
    public async Task DisposingTheClientWhileRequestsRunFailsThemCleanly()
    {
        var client = await LwnrdbClient.ConnectAsync(fixture.Settings(settings => settings with { PoolSize = 2 }));
        var running = Enumerable.Range(0, 8).Select(async _ =>
        {
            try
            {
                for (var index = 0; index < 1000; index++)
                {
                    await client.ListDatabasesAsync();
                }
                return true;
            }
            catch (Exception error) when (error is LwnrdbException or ObjectDisposedException)
            {
                return false;
            }
        }).ToList();
        await Task.Delay(100);
        await client.DisposeAsync();
        var finished = await Task.WhenAll(running).WaitAsync(TimeSpan.FromSeconds(30));
        Assert.Contains(false, finished);
    }
}
