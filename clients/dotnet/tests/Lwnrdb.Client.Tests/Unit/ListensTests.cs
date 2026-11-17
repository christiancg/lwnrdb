using System;
using System.Collections.Concurrent;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Tests.Unit.Internal;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit;

public sealed class ListensTests : IAsyncLifetime
{
    private readonly ConcurrentQueue<string> _events = new();
    private FakeServer _server = null!;
    private int _nextListen;
    private Action<JsonObject, FakeSession, string>? _onListen;

    public ValueTask InitializeAsync()
    {
        _server = FakeServer.Start((request, session) =>
        {
            if (request["type"]!.GetValue<string>() == "LISTEN")
            {
                var listenId = $"listen-{Interlocked.Increment(ref _nextListen)}";
                (_onListen ?? ((r, s, id) => s.Send(ListenReply(r, id))))(request, session, listenId);
                return;
            }
            session.Send(RecordingChannel.Ok(request));
        });
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync() => _server.DisposeAsync();

    private static JsonObject ListenReply(JsonObject request, string listenId) => RecordingChannel.Ok(request, new JsonObject
    {
        ["listenId"] = listenId,
        ["results"] = new JsonArray(new JsonObject { ["_id"] = "o-1", ["at"] = "#geo(1,2)" }),
        ["resultHash"] = "h0",
        ["isUpdate"] = false,
    });

    private static JsonObject Push(string listenId, string id) => new()
    {
        ["type"] = "LISTEN",
        ["status"] = "OK",
        ["listenId"] = listenId,
        ["results"] = new JsonArray(new JsonObject { ["_id"] = id }),
        ["resultHash"] = id,
        ["isUpdate"] = true,
    };

    private Task<LwnrdbClient> Connect() => LwnrdbClient.ConnectAsync(_server.Settings(settings => settings with { PoolSize = 1 }));

    private ListenHandler<Document> Recorder(Func<ListenUpdate<Document>, Task>? onUpdate = null) => new(async (update, _) =>
    {
        _events.Enqueue($"update:{update.ResultHash}");
        if (onUpdate is not null)
        {
            await onUpdate(update);
        }
    })
    {
        OnEnded = reason =>
        {
            _events.Enqueue($"ended:{reason.RawCode}");
            return ValueTask.CompletedTask;
        },
        OnError = failure =>
        {
            _events.Enqueue($"error:{failure.Message}");
            return ValueTask.CompletedTask;
        },
    };

    [Fact]
    public async Task APushBeforeTheRegistrationIsDeliveredAfterTheInitialUpdate()
    {
        _onListen = (request, session, listenId) =>
        {
            session.Send(Push(listenId, "early"));
            session.Send(ListenReply(request, listenId));
        };
        await using var client = await Connect();
        await using var subscription = await client.Database("shop").Collection("orders").ListenAsync(Recorder());
        await WireConnectionTests.Eventually(() => _events.Count == 2);
        Assert.Equal(["update:h0", "update:early"], _events);
        Assert.True(subscription.IsActive);
    }

    [Fact]
    public async Task AsyncHandlersRunInOrder()
    {
        FakeSession? session = null;
        string? id = null;
        _onListen = (request, s, listenId) =>
        {
            session = s;
            id = listenId;
            s.Send(ListenReply(request, listenId));
        };
        await using var client = await Connect();
        await using var subscription = await client.Database("shop").Collection("orders").ListenAsync(Recorder(async update =>
        {
            if (update.ResultHash == "slow")
            {
                await Task.Delay(100);
            }
        }));
        await WireConnectionTests.Eventually(() => _events.Count == 1);
        session!.Send(Push(id!, "slow"));
        session.Send(Push(id!, "fast"));
        await WireConnectionTests.Eventually(() => _events.Count == 3);
        Assert.Equal(["update:h0", "update:slow", "update:fast"], _events);
    }

    [Fact]
    public async Task TooManyListensOpensAnotherConnection()
    {
        var refused = false;
        _onListen = (request, session, listenId) =>
        {
            if (listenId == "listen-2" && !refused)
            {
                refused = true;
                session.Send(RecordingChannel.Error(request, "ERROR", "400-25"));
                return;
            }
            session.Send(ListenReply(request, listenId));
        };
        await using var client = await Connect();
        var orders = client.Database("shop").Collection("orders");
        await using var first = await orders.ListenAsync(Recorder());
        await using var second = await orders.ListenAsync(Recorder());
        Assert.Equal(3, _server.Sessions.Count);
    }

    [Fact]
    public async Task AListenEndCallsOnEndedAndDisposingSendsNoStop()
    {
        FakeSession? session = null;
        _onListen = (request, s, listenId) =>
        {
            session = s;
            s.Send(ListenReply(request, listenId));
        };
        await using var client = await Connect();
        var subscription = await client.Database("shop").Collection("orders").ListenAsync(Recorder());
        session!.Send(new JsonObject { ["type"] = "LISTEN", ["status"] = "NOT_FOUND", ["errorCode"] = "410-1", ["listenId"] = subscription.ListenId });
        await WireConnectionTests.Eventually(() => _events.Contains("ended:410-1"));
        Assert.False(subscription.IsActive);
        await subscription.DisposeAsync();
        Assert.Equal(0, _server.Count("STOP_LISTEN"));
    }

    [Fact]
    public async Task AThrowingHandlerCallsOnErrorAndEndsTheSubscription()
    {
        await using var client = await Connect();
        var subscription = await client.Database("shop").Collection("orders")
            .ListenAsync(Recorder(_ => throw new InvalidOperationException("handler bug")));
        await WireConnectionTests.Eventually(() => _events.Contains("error:handler bug"));
        await WireConnectionTests.Eventually(() => !subscription.IsActive);
        await _server.NextRequestAsync("STOP_LISTEN");
    }

    [Fact]
    public async Task DisposingAStillActiveSubscriptionStopsTheListen()
    {
        await using var client = await Connect();
        var subscription = await client.Database("shop").Collection<Order>("orders").ListenAsync(
            client.Database("shop").Collection<Order>("orders").AsQueryable().Where(o => o.Status == "new"),
            ListenHandler.Of<Order>(update => _events.Enqueue(update.Results[0].Id!)));
        Assert.Contains("\"value\":\"new\"", (await _server.NextRequestAsync("LISTEN")).ToJsonString(), StringComparison.Ordinal);
        await WireConnectionTests.Eventually(() => _events.Count == 1);
        await subscription.DisposeAsync();
        await subscription.DisposeAsync();
        Assert.Equal(1, _server.Count("STOP_LISTEN"));
        Assert.Equal(["o-1"], _events);
    }

    [Fact]
    public async Task ALostConnectionFailsItsSubscriptions()
    {
        FakeSession? session = null;
        _onListen = (request, s, listenId) =>
        {
            session = s;
            s.Send(ListenReply(request, listenId));
        };
        await using var client = await Connect();
        var subscription = await client.Database("shop").Collection("orders").ListenAsync(Recorder());
        session!.Close();
        await WireConnectionTests.Eventually(() => _events.Any(item => item.StartsWith("error:", StringComparison.Ordinal)));
        Assert.False(subscription.IsActive);
    }

    [Fact]
    public async Task AListenFromAnIQueryableEndingInSelectIsRefused()
    {
        await using var client = await Connect();
        var orders = client.Database("shop").Collection<Order>("orders");
        await Assert.ThrowsAsync<NotSupportedException>(() => orders.ListenAsync(orders.AsQueryable().Select(o => o), ListenHandler.Of<Order>(_ => { })));
        await Assert.ThrowsAsync<ConflictException>(async () =>
        {
            _onListen = (request, session, _) => session.Send(RecordingChannel.Error(request, "ERROR", "409-1"));
            await orders.ListenAsync(ListenHandler.Of<Order>(_ => { }));
        });
    }
}
