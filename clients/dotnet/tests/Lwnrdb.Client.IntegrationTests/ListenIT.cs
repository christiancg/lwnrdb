using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Channels;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.IntegrationTests.Support;
using Xunit;

namespace Lwnrdb.Client.IntegrationTests;

public sealed class ListenIT(ListenIT.Server fixture) : IClassFixture<ListenIT.Server>
{
    private sealed class Events
    {
        private readonly Channel<ListenUpdate<Item>> _updates = Channel.CreateUnbounded<ListenUpdate<Item>>();

        public ConcurrentQueue<Exception> Ended { get; } = new();

        public ListenHandler<Item> Handler => new((update, ct) => _updates.Writer.WriteAsync(update, ct))
        {
            OnEnded = reason =>
            {
                Ended.Enqueue(reason);
                return ValueTask.CompletedTask;
            },
            OnError = error =>
            {
                Ended.Enqueue(error);
                return ValueTask.CompletedTask;
            },
        };

        public async Task<ListenUpdate<Item>> NextAsync(TimeSpan? within = null) =>
            await _updates.Reader.ReadAsync().AsTask().WaitAsync(within ?? TimeSpan.FromSeconds(15));

        public async Task<bool> QuietAsync(TimeSpan within)
        {
            try
            {
                await NextAsync(within);
                return false;
            }
            catch (TimeoutException)
            {
                return true;
            }
        }
    }

    private async Task<Collection<Item>> Created(string name)
    {
        var collection = fixture.Client.Database("listen_it").Collection<Item>(name);
        await collection.CreateAsync();
        return collection;
    }

    [Fact]
    public async Task AMatchingWritePushesAndADisposedSubscriptionStops()
    {
        var items = await Created("pending");
        var events = new Events();
        var subscription = await items.ListenAsync(items.AsQueryable().Where(o => o.Status == "pending"), events.Handler);
        Assert.True((await events.NextAsync()).Initial);
        await items.SaveAsync(new Item { Id = "p1", Status = "pending" });
        var pushed = await events.NextAsync();
        Assert.False(pushed.Initial);
        Assert.Equal("p1", pushed.Results[0].Id);
        await items.SaveAsync(new Item { Id = "x1", Status = "other" });
        Assert.True(await events.QuietAsync(TimeSpan.FromSeconds(1)));
        await subscription.DisposeAsync();
        await items.SaveAsync(new Item { Id = "p2", Status = "pending" });
        Assert.True(await events.QuietAsync(TimeSpan.FromSeconds(1)));
    }

    [Fact]
    public async Task UpdatesArriveInWriteOrder()
    {
        var items = await Created("burst");
        var events = new Events();
        await using var subscription = await items.ListenAsync(events.Handler);
        await events.NextAsync();
        for (var index = 0; index < 20; index++)
        {
            await items.SaveAsync(new Item { Id = $"b{index}" });
        }
        var last = 0;
        while (last < 20)
        {
            var update = await events.NextAsync();
            Assert.True(update.Results.Count >= last);
            last = update.Results.Count;
        }
    }

    [Fact]
    public async Task DroppingTheCollectionEndsTheListen()
    {
        var items = await Created("doomed");
        var events = new Events();
        var subscription = await items.ListenAsync(events.Handler);
        await events.NextAsync();
        await items.DropAsync();
        var ended = await Eventually.Until(() => Task.FromResult(events.Ended.ToList()), list => list.Count > 0);
        var listenEnded = Assert.IsType<ListenEndedException>(ended[0]);
        Assert.Equal("410-1", listenEnded.RawCode);
        Assert.False(subscription.IsActive);
    }

    [Fact]
    public async Task ListensPastThePerConnectionLimitSpreadOverConnections()
    {
        var items = await Created("spread");
        await items.SaveAsync(new Item { Id = "f1" });
        var subscriptions = new List<Subscription>();
        for (var index = 0; index < 3; index++)
        {
            var events = new Events();
            subscriptions.Add(await items.ListenAsync(events.Handler));
            Assert.Equal(["f1"], (await events.NextAsync()).Results.Select(row => row.Id));
        }
        foreach (var subscription in subscriptions)
        {
            await subscription.DisposeAsync();
        }
    }

    public sealed class Server : ServerFixture
    {
        protected override IReadOnlyDictionary<string, string> Config => new Dictionary<string, string> { ["maxListensPerConnection"] = "2" };

        protected override Task SeedAsync() => Client.Database("listen_it").CreateAsync();
    }
}
