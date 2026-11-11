using System;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Mapping;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client;

public sealed class Transaction : IAsyncDisposable
{
    private readonly Lease _lease;
    private readonly ClientContext _context;
    private int _finished;

    private Transaction(string id, Lease lease, ClientContext context)
    {
        Id = id;
        _lease = lease;
        _context = context;
    }

    public string Id { get; }

    public bool IsOpen => Volatile.Read(ref _finished) == 0;

    public TransactionCollection<T> Collection<T>(string database, string collection, IDocumentCodec<T>? codec = null)
    {
        RequireOpen();
        return new(new CollectionOps<T>(_context, database, collection, codec ?? new JsonCodec<T>(_context.Json)));
    }

    public TransactionCollection<Document> Collection(string database, string collection)
    {
        RequireOpen();
        return new(new CollectionOps<Document>(_context, database, collection, IdentityCodec.Instance));
    }

    public Task CommitAsync(CancellationToken ct = default) =>
        EndAsync(Operation.CommitTransaction, "COMMIT_TRANSACTION", ct);

    public Task RollbackAsync(CancellationToken ct = default) =>
        EndAsync(Operation.RollbackTransaction, "ROLLBACK_TRANSACTION", ct);

    public async ValueTask DisposeAsync()
    {
        if (IsOpen)
        {
            await RollbackAsync().ConfigureAwait(false);
        }
    }

    internal static async Task<Transaction> StartAsync(Lease lease, ClientContext parent, CancellationToken ct)
    {
        var context = parent.WithChannel(new PinnedChannel(lease.Connection, parent.RequestTimeout));
        try
        {
            var reply = await context.Channel
                .SendAsync(Operation.StartTransaction, RequestEncoder.Request("START_TRANSACTION"), ct)
                .ConfigureAwait(false);
            return new Transaction(ResponseDecoder.TextOf(reply["transactionId"]) ?? string.Empty, lease, context);
        }
        catch (Exception error)
        {
            if (error is ConnectionLostException or OperationCanceledException)
            {
                lease.Discard();
            }
            lease.Release();
            throw;
        }
    }

    private async Task EndAsync(Operation operation, string type, CancellationToken ct)
    {
        if (Interlocked.Exchange(ref _finished, 1) == 1)
        {
            throw new InvalidOperationException("the transaction is already committed or rolled back");
        }
        try
        {
            await _context.Channel.SendAsync(operation, RequestEncoder.Request(type), ct).ConfigureAwait(false);
        }
        catch (Exception error) when (error is UnknownOutcomeException or ConnectionLostException or OperationCanceledException)
        {
            _lease.Discard();
            throw;
        }
        finally
        {
            _lease.Release();
        }
    }

    private void RequireOpen()
    {
        if (!IsOpen)
        {
            throw new InvalidOperationException("the transaction is already committed or rolled back");
        }
    }
}
