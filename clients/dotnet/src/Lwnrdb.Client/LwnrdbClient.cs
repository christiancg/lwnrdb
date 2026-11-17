using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Mapping;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client;

public sealed class LwnrdbClient : IAsyncDisposable
{
    private readonly ConnectionPool _pool;
    private readonly Listens _listens;
    private readonly ClientContext _context;

    private LwnrdbClient(ClientSettings settings, Backoff backoff, StreamOpener? opener)
    {
        Settings = settings;
        var encoder = new RequestEncoder(settings.TemporalZone);
        var factory = new ConnectionFactory(settings, encoder, opener);
        _pool = new ConnectionPool(factory);
        _listens = new Listens(factory, encoder, settings.RequestTimeout);
        _context = new ClientContext(
            new PooledChannel(_pool, settings.RetryPolicy, settings.RequestTimeout, backoff),
            encoder,
            new ScriptRequestEncoder(encoder),
            _listens,
            settings.Credentials?.Username,
            settings.RequestTimeout,
            LwnrdbJson.CreateOptions(settings.Json, settings.TemporalZone));
    }

    public ClientSettings Settings { get; }

    public static Task<LwnrdbClient> ConnectAsync(ClientSettings? settings = null, CancellationToken ct = default) =>
        ConnectAsync(settings ?? new ClientSettings(), Backoffs.Jittered, null, ct);

    public Database Database(string name) => new(_context, name);

    public async Task<IReadOnlyList<string>> ListDatabasesAsync(CancellationToken ct = default)
    {
        var reply = await _context.Channel.SendAsync(Operation.ListDatabases, RequestEncoder.Request("LIST_DATABASES"), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.Strings(reply["databases"]);
    }

    public async Task<Admin.ServerInfo> ServerInfoAsync(CancellationToken ct = default)
    {
        var reply = await _context.Channel.SendAsync(Operation.ServerInfo, RequestEncoder.Request("SERVER_INFO"), ct)
            .ConfigureAwait(false);
        return Descriptors.Server(ResponseDecoder.DocumentOf(reply, "server") ?? Document.Empty);
    }

    public async Task<Document> DatabaseStatsAsync(CancellationToken ct = default)
    {
        var reply = await _context.Channel.SendAsync(Operation.GetDatabaseStats, RequestEncoder.Request("GET_DATABASE_STATS"), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.DocumentOf(reply, "stats") ?? Document.Empty;
    }

    public async Task ChangePasswordAsync(string currentPassword, string newPassword, CancellationToken ct = default)
    {
        var username = _context.Username ?? throw new InvalidOperationException("the client has no credentials to change");
        await _context.Channel
            .SendAsync(Operation.SetPassword, ScriptRequestEncoder.SetPassword(username, currentPassword, newPassword), ct)
            .ConfigureAwait(false);
    }

    public Admin.Admin Admin() => new(_context);

    public async Task<Transaction> BeginTransactionAsync(CancellationToken ct = default) =>
        await Transaction.StartAsync(await _pool.AcquireAsync(ct).ConfigureAwait(false), _context, ct).ConfigureAwait(false);

    public async Task<TResult> TransactionAsync<TResult>(
        Func<Transaction, CancellationToken, Task<TResult>> body,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(body);
        var transaction = await BeginTransactionAsync(ct).ConfigureAwait(false);
        TResult result;
        try
        {
            result = await body(transaction, ct).ConfigureAwait(false);
        }
        catch
        {
            await RollbackQuietlyAsync(transaction).ConfigureAwait(false);
            throw;
        }
        if (transaction.IsOpen)
        {
            await transaction.CommitAsync(ct).ConfigureAwait(false);
        }
        return result;
    }

    public Task TransactionAsync(Func<Transaction, CancellationToken, Task> body, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(body);
        return TransactionAsync(
            async (transaction, token) =>
            {
                await body(transaction, token).ConfigureAwait(false);
                return true;
            },
            ct);
    }

    public async ValueTask DisposeAsync()
    {
        await _listens.DisposeAsync().ConfigureAwait(false);
        await _pool.DisposeAsync().ConfigureAwait(false);
    }

    internal static async Task<LwnrdbClient> ConnectAsync(
        ClientSettings settings,
        Backoff backoff,
        StreamOpener? opener,
        CancellationToken ct)
    {
        var client = new LwnrdbClient(settings.Validated(), backoff, opener);
        try
        {
            var lease = await client._pool.AcquireAsync(ct).ConfigureAwait(false);
            lease.Release();
            return client;
        }
        catch
        {
            await client.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    private static async Task RollbackQuietlyAsync(Transaction transaction)
    {
        if (!transaction.IsOpen)
        {
            return;
        }
        try
        {
            await transaction.RollbackAsync().ConfigureAwait(false);
        }
        catch (LwnrdbException)
        {
            return;
        }
    }
}
