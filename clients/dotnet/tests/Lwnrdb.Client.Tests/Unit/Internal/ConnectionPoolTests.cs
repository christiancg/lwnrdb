using System;
using System.Diagnostics;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Tests.Support;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit.Internal;

public sealed class ConnectionPoolTests : IAsyncLifetime
{
    private FakeServer _server = null!;

    public ValueTask InitializeAsync()
    {
        _server = FakeServer.Start();
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync() => _server.DisposeAsync();

    private ConnectionPool Pool(int size = 2, TimeSpan? acquire = null) =>
        new(new ConnectionFactory(
            _server.Settings(settings => settings with { PoolSize = size, AcquireTimeout = acquire ?? TimeSpan.FromSeconds(5) }),
            new RequestEncoder(null)));

    [Fact]
    public async Task ThePoolGrowsLazilyAndReusesConnections()
    {
        await using var pool = Pool();
        Assert.Empty(_server.Sessions);
        var first = await pool.AcquireAsync(default);
        var second = await pool.AcquireAsync(default);
        Assert.Equal(2, pool.ConnectionsInUse);
        first.Release();
        first.Release();
        second.Release();
        Assert.Equal(2, pool.IdleConnections);
        var again = await pool.AcquireAsync(default);
        again.Release();
        await WireConnectionTests.Eventually(() => _server.Sessions.Count == 2);
        await Task.Delay(50);
        Assert.Equal(2, _server.Sessions.Count);
    }

    [Fact]
    public async Task AWaiterGetsTheReleasedConnection()
    {
        await using var pool = Pool(size: 1);
        var held = await pool.AcquireAsync(default);
        var waiting = pool.AcquireAsync(default);
        Assert.False(waiting.IsCompleted);
        held.Release();
        var granted = await waiting;
        Assert.Same(held.Connection, granted.Connection);
        granted.Release();
    }

    [Fact]
    public async Task AcquiringFailsAfterTheAcquireTimeout()
    {
        await using var pool = Pool(size: 1, acquire: TimeSpan.FromMilliseconds(100));
        var held = await pool.AcquireAsync(default);
        await Assert.ThrowsAsync<PoolExhaustedException>(() => pool.AcquireAsync(default));
        held.Release();
    }

    [Fact]
    public async Task ADeadConnectionIsNotReused()
    {
        await using var pool = Pool(size: 1);
        var lease = await pool.AcquireAsync(default);
        lease.Connection.Close();
        lease.Release();
        var next = await pool.AcquireAsync(default);
        Assert.NotSame(lease.Connection, next.Connection);
        next.Discard();
        next.Release();
        Assert.Equal(0, pool.IdleConnections);
    }

    [Fact]
    public async Task DisposingWaitsAtMostTheShutdownTimeoutForASilentServer()
    {
        _server.Responder = (request, session) =>
        {
            if (request["type"]!.GetValue<string>() != "CLOSE_CONNECTION")
            {
                session.Send(RecordingChannel.Ok(request));
            }
        };
        var pool = Pool();
        var lease = await pool.AcquireAsync(default);
        lease.Release();
        var held = await pool.AcquireAsync(default);
        var clock = Stopwatch.StartNew();
        await pool.DisposeAsync();
        Assert.True(clock.Elapsed < TimeSpan.FromSeconds(6), $"took {clock.Elapsed}");
        held.Release();
        await Assert.ThrowsAsync<ObjectDisposedException>(() => pool.AcquireAsync(default));
        await pool.DisposeAsync();
    }

    [Fact]
    public async Task DisposingWaitsForBorrowedConnections()
    {
        var pool = Pool(size: 1);
        var held = await pool.AcquireAsync(default);
        var waiter = pool.AcquireAsync(default);
        var disposing = pool.DisposeAsync().AsTask();
        await Assert.ThrowsAsync<ObjectDisposedException>(() => waiter);
        Assert.False(disposing.IsCompleted);
        held.Release();
        await disposing;
        Assert.False(held.Connection.IsOpen);
    }

    [Fact]
    public async Task AnAuthenticationFailureFailsTheConnect()
    {
        _server.Responder = (request, session) => session.Send(request["type"]!.GetValue<string>() == "AUTHENTICATE"
            ? RecordingChannel.Error(request, "UNAUTHENTICATED", "401-1")
            : RecordingChannel.Ok(request));
        var settings = _server.Settings(settings => settings with { Credentials = new Credentials("alice", "wrong") });
        await Assert.ThrowsAsync<AuthenticationException>(() => LwnrdbClient.ConnectAsync(settings));
    }

    [Fact]
    public async Task AnUnreachableHostIsSkippedForTheNextOne()
    {
        var settings = _server.Settings(settings => settings with
        {
            Hosts = [new ServerAddress("127.0.0.1", 1), .. _server.Hosts],
            ConnectTimeout = TimeSpan.FromSeconds(2),
        });
        await using var client = await LwnrdbClient.ConnectAsync(settings);
        var unreachable = settings with { Hosts = [new ServerAddress("127.0.0.1", 1)] };
        await Assert.ThrowsAsync<ConnectionLostException>(() => LwnrdbClient.ConnectAsync(unreachable));
    }
}
