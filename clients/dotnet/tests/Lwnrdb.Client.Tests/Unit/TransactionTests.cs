using System;
using System.Linq;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Tests.Support;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit;

public sealed class TransactionTests : IAsyncLifetime
{
    private FakeServer _server = null!;
    private Func<System.Text.Json.Nodes.JsonObject, System.Text.Json.Nodes.JsonObject?> _special = _ => null;

    public ValueTask InitializeAsync()
    {
        _server = FakeServer.Start((request, session) =>
            session.Send(_special(request) ?? RecordingChannel.Ok(request, new System.Text.Json.Nodes.JsonObject { ["transactionId"] = "tx-1" })));
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync() => _server.DisposeAsync();

    private Task<LwnrdbClient> Connect() => LwnrdbClient.ConnectAsync(_server.Settings(settings => settings with { PoolSize = 1 }));

    [Fact]
    public async Task ACompletedBodyCommits()
    {
        await using var client = await Connect();
        var result = await client.TransactionAsync(async (transaction, ct) =>
        {
            Assert.Equal("tx-1", transaction.Id);
            await transaction.Collection("shop", "orders").SaveAsync(Document.Of("a", 1), ct);
            return 42;
        });
        Assert.Equal(42, result);
        Assert.Equal(1, _server.Count("COMMIT_TRANSACTION"));
        Assert.Equal(0, _server.Count("ROLLBACK_TRANSACTION"));
    }

    [Fact]
    public async Task AThrowingBodyRollsBackAndRethrowsItsException()
    {
        _special = request => request["type"]!.GetValue<string>() == "ROLLBACK_TRANSACTION" ? RecordingChannel.Error(request, "ERROR", "409-4") : null;
        await using var client = await Connect();
        var thrown = new InvalidOperationException("body failed");
        var caught = await Assert.ThrowsAsync<InvalidOperationException>(() => client.TransactionAsync((_, _) => throw thrown));
        Assert.Same(thrown, caught);
        Assert.Equal(1, _server.Count("ROLLBACK_TRANSACTION"));
        await client.Database("shop").CreateAsync();
    }

    [Fact]
    public async Task ABodyThatEndsTheTransactionItselfIsNotCommittedAgain()
    {
        await using var client = await Connect();
        await client.TransactionAsync((transaction, ct) => transaction.RollbackAsync(ct));
        Assert.Equal(0, _server.Count("COMMIT_TRANSACTION"));
        await Assert.ThrowsAsync<InvalidOperationException>(async () =>
        {
            await using var transaction = await client.BeginTransactionAsync();
            await transaction.CommitAsync();
            transaction.Collection("shop", "orders");
        });
    }

    [Fact]
    public async Task AnUnknownCommitOutcomeDiscardsTheConnection()
    {
        _special = request => request["type"]!.GetValue<string>() == "COMMIT_TRANSACTION" ? RecordingChannel.Error(request, "ERROR", "500-34") : null;
        await using var client = await Connect();
        var transaction = await client.BeginTransactionAsync();
        await Assert.ThrowsAsync<UnknownOutcomeException>(() => transaction.CommitAsync());
        Assert.Equal(0, _server.Count("ROLLBACK_TRANSACTION"));
        await client.ListDatabasesAsync();
        Assert.Equal(2, _server.Sessions.Count);
    }

    [Fact]
    public async Task DisposingAnOpenTransactionRollsItBack()
    {
        await using var client = await Connect();
        await using (await client.BeginTransactionAsync())
        {
        }
        Assert.Equal(1, _server.Count("ROLLBACK_TRANSACTION"));
    }

    [Fact]
    public async Task DdlInsideATransactionIsRefusedLocally()
    {
        using var tcp = new System.Net.Sockets.TcpClient();
        await tcp.ConnectAsync(System.Net.IPAddress.Loopback, _server.Port);
        using var connection = new Client.Internal.WireConnection(tcp.GetStream(), 1024, "test");
        var pinned = new Client.Internal.PinnedChannel(connection, TimeSpan.FromSeconds(5));
        var refused = await Assert.ThrowsAsync<LwnrdbException>(() =>
            pinned.SendAsync(Protocol.Operation.CreateCollection, Client.Internal.RequestEncoder.Request("CREATE_COLLECTION", "shop", "orders"), default));
        Assert.Contains("not allowed inside a transaction", refused.Message, StringComparison.Ordinal);
        Assert.Equal(0, _server.Count("CREATE_COLLECTION"));
    }

    [Fact]
    public async Task LinqInsideATransactionGoesToThePinnedConnection()
    {
        await using var client = await Connect();
        await using var transaction = await client.BeginTransactionAsync();
        var orders = transaction.Collection<Order>("shop", "orders");
        await orders.AsQueryable().Where(o => o.Paid).ToListAsync();
        await orders.SaveAsync(new Order { Id = "o-1" });
        await orders.SaveAllAsync([new Order()]);
        await orders.FindByIdAsync("o-1");
        await orders.DeleteAsync("o-1");
        await orders.Query().CountAsync();
        await orders.DeleteWhereAsync(o => o.Paid);
        await orders.DeleteWhereAsync(Lwnrdb.Client.Query.Filters.Field("status").Eq("void"));
        var session = Assert.Single(_server.Sessions);
        Assert.Equal(
            ["START_TRANSACTION", "AGGREGATE", "SAVE", "BULK_SAVE", "FIND_BY_ID", "DELETE", "AGGREGATE", "DELETE", "DELETE"],
            session.Requests.Select(request => request["type"]!.GetValue<string>()));
        Assert.Equal("orders", orders.Name);
    }

    [Fact]
    public async Task AFailedStartReturnsTheConnection()
    {
        _special = request => request["type"]!.GetValue<string>() == "START_TRANSACTION" ? RecordingChannel.Error(request, "ERROR", "409-3") : null;
        await using var client = await Connect();
        await Assert.ThrowsAsync<ConflictException>(() => client.BeginTransactionAsync());
        await client.ListDatabasesAsync();
        Assert.Single(_server.Sessions);
    }
}
