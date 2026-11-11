using System;
using System.Linq;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.IntegrationTests.Support;
using Lwnrdb.Client.Linq;
using Xunit;
using static Lwnrdb.Client.Query.Filters;

namespace Lwnrdb.Client.IntegrationTests;

public sealed class TransactionIT(TransactionIT.Server fixture) : IClassFixture<TransactionIT.Server>
{
    private Collection<Document> Accounts => fixture.Client.Database("tx_it").Collection("accounts");

    [Fact]
    public async Task ACommitIsVisibleAndReadsInsideSeeTheirOwnWrites()
    {
        var seen = await fixture.Client.TransactionAsync(async (transaction, ct) =>
        {
            var inside = transaction.Collection("tx_it", "accounts");
            await inside.SaveAsync(Document.Of("_id", "a1", "balance", 100), ct);
            await inside.SaveAllAsync([Document.Of("_id", "a2", "balance", 50)], ct);
            Assert.Null(await Accounts.FindByIdAsync("a1", dirtyRead: true, ct));
            Assert.Equal(1, await inside.Query().Filter(Field("_id").Eq("a2")).CountAsync(ct));
            var typed = transaction.Collection<Account>("tx_it", "accounts");
            Assert.Equal(2, await typed.AsQueryable().CountAsync(a => a.Balance >= 50, ct));
            return (await inside.FindByIdAsync("a1", ct))!.GetDouble("balance");
        });
        Assert.Equal(100, seen);
        Assert.NotNull(await Accounts.FindByIdAsync("a1"));
        Assert.NotNull(await Accounts.FindByIdAsync("a2"));
    }

    [Fact]
    public async Task ARollbackAThrowingBodyAndADisposalLeaveNothing()
    {
        await using (var transaction = await fixture.Client.BeginTransactionAsync())
        {
            await transaction.Collection("tx_it", "accounts").SaveAsync(Document.Of("_id", "r1"));
            await transaction.RollbackAsync();
        }
        Assert.Null(await Accounts.FindByIdAsync("r1"));
        await Assert.ThrowsAsync<InvalidOperationException>(() => fixture.Client.TransactionAsync(async (transaction, ct) =>
        {
            await transaction.Collection("tx_it", "accounts").SaveAsync(Document.Of("_id", "r2"), ct);
            throw new InvalidOperationException("abort");
        }));
        Assert.Null(await Accounts.FindByIdAsync("r2"));
        await using (var disposed = await fixture.Client.BeginTransactionAsync())
        {
            await disposed.Collection("tx_it", "accounts").SaveAsync(Document.Of("_id", "r3"));
        }
        Assert.Null(await Accounts.FindByIdAsync("r3"));
    }

    [Fact]
    public async Task AnIdleTransactionIsRolledBackByTheServer()
    {
        var transaction = await fixture.Client.BeginTransactionAsync();
        var inside = transaction.Collection("tx_it", "accounts");
        await inside.SaveAsync(Document.Of("_id", "idle"));
        await Task.Delay(TimeSpan.FromSeconds(3));
        var error = await Assert.ThrowsAnyAsync<LwnrdbException>(() => inside.SaveAsync(Document.Of("_id", "idle2")));
        Assert.True(error.RawCode == "409-16" || error is ConnectionLostException or ServerClosedConnectionException, error.ToString());
        try
        {
            await transaction.DisposeAsync();
        }
        catch (LwnrdbException)
        {
        }
        Assert.Null(await Accounts.FindByIdAsync("idle"));
    }

    [Fact]
    public async Task TwoTransactionsUseTheirOwnConnections()
    {
        var first = await fixture.Client.BeginTransactionAsync();
        var second = await fixture.Client.BeginTransactionAsync();
        await first.Collection("tx_it", "accounts").SaveAsync(Document.Of("_id", "s1"));
        await second.RollbackAsync();
        await first.CommitAsync();
        Assert.NotNull(await Accounts.FindByIdAsync("s1"));
    }

    public sealed class Account
    {
        public string? Id { get; set; }

        public double Balance { get; set; }
    }

    public sealed class Server : ServerFixture
    {
        protected override System.Collections.Generic.IReadOnlyDictionary<string, string> Config =>
            new System.Collections.Generic.Dictionary<string, string> { ["transactionIdleTimeoutMs"] = "1500" };

        protected override async Task SeedAsync()
        {
            var database = Client.Database("tx_it");
            await database.CreateAsync();
            await database.Collection("accounts").CreateAsync();
        }
    }
}
