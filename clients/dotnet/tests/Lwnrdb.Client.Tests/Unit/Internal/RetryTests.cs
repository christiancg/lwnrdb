using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Tests.Support;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit.Internal;

public sealed class RetryTests : IAsyncLifetime
{
    private readonly List<TimeSpan> _delays = [];
    private FakeServer _server = null!;

    public ValueTask InitializeAsync()
    {
        _server = FakeServer.Start();
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync() => _server.DisposeAsync();

    public static TheoryData<string> RetryableCodes() => [.. ErrorCode.All.Where(code => code.IsRetryable).Select(code => code.Code)];

    private Task<LwnrdbClient> Connect(RetryPolicy? policy = null) => LwnrdbClient.ConnectAsync(
        _server.Settings(settings => settings with { RetryPolicy = policy ?? new RetryPolicy(4, TimeSpan.FromMilliseconds(10), TimeSpan.FromMilliseconds(25)) }),
        (delay, _) =>
        {
            _delays.Add(delay);
            return Task.CompletedTask;
        },
        null,
        CancellationToken.None);

    private void FailFirst(string type, int times, Action<FakeSession, System.Text.Json.Nodes.JsonObject> failure)
    {
        var failures = 0;
        _server.Responder = (request, session) =>
        {
            if (request["type"]!.GetValue<string>() == type && failures++ < times)
            {
                failure(session, request);
                return;
            }
            session.Send(RecordingChannel.Ok(request));
        };
    }

    [Theory]
    [MemberData(nameof(RetryableCodes))]
    public async Task EachRetryableCodeIsRetriedWithCappedDoubling(string code)
    {
        FailFirst("SAVE", 10, (session, request) => session.Send(RecordingChannel.Error(request, "ERROR", code)));
        await using var client = await Connect();
        await Assert.ThrowsAsync<UnavailableException>(() => client.Database("shop").Collection("orders").SaveAsync(Document.Of("a", 1)));
        Assert.Equal(4, _server.Count("SAVE"));
        Assert.Equal([10, 20, 25], _delays.Select(delay => delay.TotalMilliseconds));
    }

    [Theory]
    [InlineData("503-3")]
    [InlineData("500-33")]
    [InlineData("500-34")]
    public async Task OutcomeUnknownCodesAreNotRetried(string code)
    {
        FailFirst("SAVE", 10, (session, request) => session.Send(RecordingChannel.Error(request, "ERROR", code)));
        await using var client = await Connect();
        await Assert.ThrowsAnyAsync<LwnrdbException>(() => client.Database("shop").Collection("orders").SaveAsync(Document.Of("a", 1)));
        Assert.Equal(1, _server.Count("SAVE"));
    }

    [Fact]
    public async Task AReadIsRetriedAfterALostConnection()
    {
        FailFirst("AGGREGATE", 1, (session, _) => session.Close());
        await using var client = await Connect();
        Assert.Empty(await client.Database("shop").Collection("orders").Query().ToListAsync());
        Assert.Equal(2, _server.Count("AGGREGATE"));
    }

    [Fact]
    public async Task AWriteIsNotRetriedAfterALostConnection()
    {
        FailFirst("SAVE", 1, (session, _) => session.Close());
        await using var client = await Connect();
        await Assert.ThrowsAsync<ConnectionLostException>(() => client.Database("shop").Collection("orders").SaveAsync(Document.Of("a", 1)));
        Assert.Equal(1, _server.Count("SAVE"));
    }

    [Fact]
    public async Task NothingIsRetriedInsideATransaction()
    {
        FailFirst("SAVE", 10, (session, request) => session.Send(RecordingChannel.Error(request, "ERROR", "503-1")));
        await using var client = await Connect();
        await using var transaction = await client.BeginTransactionAsync();
        await Assert.ThrowsAsync<UnavailableException>(() => transaction.Collection("shop", "orders").SaveAsync(Document.Of("a", 1)));
        Assert.Equal(1, _server.Count("SAVE"));
    }

    [Fact]
    public void BackoffDoublesUpToItsCap()
    {
        var policy = RetryPolicy.Default;
        Assert.Equal(TimeSpan.FromMilliseconds(50), policy.BackoffBefore(1));
        Assert.Equal(TimeSpan.FromMilliseconds(100), policy.BackoffBefore(2));
        Assert.Equal(TimeSpan.FromSeconds(2), policy.BackoffBefore(40));
        Assert.Throws<ArgumentOutOfRangeException>(() => new RetryPolicy(0, TimeSpan.Zero, TimeSpan.Zero));
        Assert.Throws<ArgumentException>(() => new RetryPolicy(1, TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(1)));
    }

    [Fact]
    public async Task TheJitteredBackoffWaitsAtLeastHalfTheDelay()
    {
        var clock = System.Diagnostics.Stopwatch.StartNew();
        await Backoffs.Jittered(TimeSpan.FromMilliseconds(40), default);
        Assert.True(clock.ElapsedMilliseconds >= 15);
    }
}
