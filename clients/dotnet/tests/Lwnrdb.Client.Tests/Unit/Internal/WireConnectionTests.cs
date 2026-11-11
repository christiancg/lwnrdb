using System;
using System.Net;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Tests.Support;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit.Internal;

public sealed class WireConnectionTests : IAsyncLifetime
{
    private static readonly TimeSpan Long = TimeSpan.FromSeconds(5);
    private FakeServer _server = null!;

    public ValueTask InitializeAsync()
    {
        _server = FakeServer.Start((_, _) => { });
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync() => _server.DisposeAsync();

    private async Task<WireConnection> Connect(long maxReplyBytes = 1024 * 1024)
    {
        var client = new TcpClient();
        await client.ConnectAsync(IPAddress.Loopback, _server.Port);
        return new WireConnection(client.GetStream(), maxReplyBytes, "test");
    }

    private static JsonObject Request(string type, int id) => new() { ["type"] = type, ["n"] = id };

    [Fact]
    public async Task RepliesCompleteRequestsInOrder()
    {
        _server.Responder = (request, session) => session.Send(new JsonObject { ["type"] = "SAVE", ["n"] = request["n"]!.DeepClone() });
        using var connection = await Connect();
        var replies = await Task.WhenAll(
            connection.RequestAsync(Request("SAVE", 1), Long, default),
            connection.RequestAsync(Request("SAVE", 2), Long, default),
            connection.RequestAsync(Request("SAVE", 3), Long, default));
        Assert.Equal([1, 2, 3], Array.ConvertAll(replies, reply => reply["n"]!.GetValue<int>()));
    }

    [Fact]
    public async Task ANonJsonLineFailsOnlyTheOldestRequest()
    {
        var first = true;
        _server.Responder = (request, session) =>
        {
            if (first)
            {
                first = false;
                session.SendRaw("not json\n");
                return;
            }
            session.Send(RecordingChannel.Ok(request));
        };
        using var connection = await Connect();
        var refused = await Assert.ThrowsAsync<LwnrdbException>(() => connection.RequestAsync(Request("SAVE", 1), Long, default));
        Assert.Contains("not json", refused.Message, StringComparison.Ordinal);
        Assert.Equal("OK", (await connection.RequestAsync(Request("SAVE", 2), Long, default))["status"]!.GetValue<string>());
        Assert.True(connection.IsOpen);
    }

    [Fact]
    public async Task ATimeoutClosesTheConnection()
    {
        using var connection = await Connect();
        var other = connection.RequestAsync(Request("SAVE", 1), Long, default);
        await Assert.ThrowsAsync<RequestTimeoutException>(() => connection.RequestAsync(Request("SAVE", 2), TimeSpan.FromMilliseconds(100), default));
        await Assert.ThrowsAsync<RequestTimeoutException>(() => other);
        Assert.False(connection.IsOpen);
        await Assert.ThrowsAsync<RequestTimeoutException>(() => connection.RequestAsync(Request("SAVE", 3), Long, default));
    }

    [Fact]
    public async Task CancellingAnInFlightRequestClosesTheConnection()
    {
        using var connection = await Connect();
        using var cancel = new CancellationTokenSource(TimeSpan.FromMilliseconds(100));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => connection.RequestAsync(Request("SAVE", 1), Long, cancel.Token));
        Assert.False(connection.IsOpen);
    }

    [Fact]
    public async Task CancellingBeforeTheWriteKeepsTheConnection()
    {
        _server.Responder = FakeServer.OkToEverything;
        using var connection = await Connect();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => connection.RequestAsync(Request("SAVE", 1), Long, new CancellationToken(true)));
        Assert.True(connection.IsOpen);
        Assert.Equal("OK", (await connection.RequestAsync(Request("SAVE", 2), Long, default))["status"]!.GetValue<string>());
    }

    [Fact]
    public async Task AServerCloseFailsEveryPendingRequest()
    {
        _server.Responder = (request, session) =>
        {
            if (request["n"]!.GetValue<int>() == 2)
            {
                session.Send(new JsonObject { ["type"] = "CLOSE_CONNECTION", ["status"] = "ERROR", ["errorCode"] = "408-4", ["message"] = "auth timeout" });
            }
        };
        using var connection = await Connect();
        var first = connection.RequestAsync(Request("SAVE", 1), Long, default);
        var second = connection.RequestAsync(Request("SAVE", 2), Long, default);
        var closed = await Assert.ThrowsAsync<ServerClosedConnectionException>(() => first);
        Assert.Equal("408-4", closed.RawCode);
        await Assert.ThrowsAsync<ServerClosedConnectionException>(() => second);
    }

    [Fact]
    public async Task AFailingPushHandlerClosesTheConnectionInsteadOfHangingRequests()
    {
        _server.Responder = (_, session) => session.Send(new JsonObject { ["type"] = "LISTEN", ["isUpdate"] = true });
        using var connection = await Connect();
        connection.OnPush = _ => throw new InvalidOperationException("handler bug");
        await Assert.ThrowsAsync<ConnectionLostException>(() => connection.RequestAsync(Request("SAVE", 1), Long, default));
        Assert.False(connection.IsOpen);
    }

    [Fact]
    public async Task AnUnaskedReplyClosesTheConnection()
    {
        using var connection = await Connect();
        LwnrdbException? cause = null;
        connection.OnClose = error => cause = error;
        await connection.RequestAsync(Request("SAVE", 0), TimeSpan.FromMilliseconds(1), default).ContinueWith(_ => Task.CompletedTask);
        var session = await WaitForSession();
        session.Send(RecordingChannel.Ok(Request("SAVE", 9)));
        await Eventually(() => cause is not null);
        Assert.IsAssignableFrom<ConnectionLostException>(cause);
    }

    [Fact]
    public async Task AnOversizedReplyFailsItsRequestOnly()
    {
        _server.Responder = (request, session) => session.Send(request["n"]!.GetValue<int>() == 1
            ? new JsonObject { ["big"] = new string('x', 200) }
            : RecordingChannel.Ok(request));
        using var connection = await Connect(maxReplyBytes: 100);
        var tooLarge = await Assert.ThrowsAsync<LwnrdbException>(() => connection.RequestAsync(Request("SAVE", 1), Long, default));
        Assert.Contains("MaxReplyBytes", tooLarge.Message, StringComparison.Ordinal);
        await connection.RequestAsync(Request("SAVE", 2), Long, default);
    }

    [Fact]
    public async Task TheServerHangingUpFailsPendingRequests()
    {
        _server.Responder = (_, session) => session.Close();
        using var connection = await Connect();
        await Assert.ThrowsAsync<ConnectionLostException>(() => connection.RequestAsync(Request("SAVE", 1), Long, default));
    }

    [Fact]
    public async Task AGracefulCloseSendsCloseConnection()
    {
        _server.Responder = FakeServer.OkToEverything;
        using var connection = await Connect();
        await connection.CloseGracefullyAsync(Long);
        Assert.False(connection.IsOpen);
        Assert.Equal(1, _server.Count("CLOSE_CONNECTION"));
        await connection.CloseGracefullyAsync(Long);
        Assert.Equal(1, _server.Count("CLOSE_CONNECTION"));
    }

    [Fact]
    public async Task AGracefulCloseOfASilentServerGivesUp()
    {
        using var connection = await Connect();
        await connection.CloseGracefullyAsync(TimeSpan.FromMilliseconds(100));
        Assert.False(connection.IsOpen);
    }

    private async Task<FakeSession> WaitForSession()
    {
        await Eventually(() => !_server.Sessions.IsEmpty);
        _server.Sessions.TryPeek(out var session);
        return session!;
    }

    internal static async Task Eventually(Func<bool> condition)
    {
        var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(5);
        while (!condition())
        {
            Assert.True(DateTime.UtcNow < deadline, "the condition never held");
            await Task.Delay(10);
        }
    }
}
