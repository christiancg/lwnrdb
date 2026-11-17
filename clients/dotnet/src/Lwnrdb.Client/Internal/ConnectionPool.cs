using System;
using System.Collections.Concurrent;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;

namespace Lwnrdb.Client.Internal;

internal sealed class ConnectionPool : IAsyncDisposable
{
    private readonly ConnectionFactory _factory;
    private readonly SemaphoreSlim _permits;
    private readonly ConcurrentStack<WireConnection> _idle = new();
    private readonly CancellationTokenSource _closing = new();
    private readonly TaskCompletionSource _drained = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private int _inUse;
    private int _closed;

    public ConnectionPool(ConnectionFactory factory)
    {
        _factory = factory;
        _permits = new SemaphoreSlim(factory.Settings.PoolSize, factory.Settings.PoolSize);
    }

    public int IdleConnections => _idle.Count;

    public int ConnectionsInUse => Volatile.Read(ref _inUse);

    private bool IsClosed => Volatile.Read(ref _closed) == 1;

    public async Task<Lease> AcquireAsync(CancellationToken ct)
    {
        ObjectDisposedException.ThrowIf(IsClosed, this);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, _closing.Token);
        bool granted;
        try
        {
            granted = await _permits.WaitAsync(_factory.Settings.AcquireTimeout, linked.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            throw new ObjectDisposedException(nameof(LwnrdbClient), "the client is closed");
        }
        if (!granted)
        {
            throw new PoolExhaustedException(
                $"no connection became free within {_factory.Settings.AcquireTimeout.TotalMilliseconds} ms; all {_factory.Settings.PoolSize} are in use");
        }
        Interlocked.Increment(ref _inUse);
        try
        {
            ObjectDisposedException.ThrowIf(IsClosed, this);
            return new Lease(await ConnectionAsync(ct).ConfigureAwait(false), this);
        }
        catch
        {
            ReleasePermit();
            throw;
        }
    }

    public void GiveBack(WireConnection connection, bool discard)
    {
        if (discard || IsClosed || !connection.IsOpen)
        {
            connection.Close();
        }
        else
        {
            _idle.Push(connection);
        }
        ReleasePermit();
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _closed, 1) == 1)
        {
            return;
        }
        await _closing.CancelAsync().ConfigureAwait(false);
        await DrainIdleAsync().ConfigureAwait(false);
        if (Volatile.Read(ref _inUse) > 0)
        {
            try
            {
                await _drained.Task.WaitAsync(_factory.Settings.ShutdownTimeout).ConfigureAwait(false);
            }
            catch (TimeoutException)
            {
                await DrainIdleAsync().ConfigureAwait(false);
                return;
            }
        }
        await DrainIdleAsync().ConfigureAwait(false);
    }

    private async Task<WireConnection> ConnectionAsync(CancellationToken ct)
    {
        while (_idle.TryPop(out var connection))
        {
            if (connection.IsOpen)
            {
                return connection;
            }
        }
        return await _factory.OpenAsync(ct).ConfigureAwait(false);
    }

    private void ReleasePermit()
    {
        if (Interlocked.Decrement(ref _inUse) == 0 && IsClosed)
        {
            _drained.TrySetResult();
        }
        _permits.Release();
    }

    private async Task DrainIdleAsync()
    {
        var idle = new System.Collections.Generic.List<WireConnection>();
        while (_idle.TryPop(out var connection))
        {
            idle.Add(connection);
        }
        await Task.WhenAll(idle.Select(connection => connection.CloseGracefullyAsync(_factory.Settings.ShutdownTimeout)))
            .ConfigureAwait(false);
    }
}

internal sealed class Lease(WireConnection connection, ConnectionPool pool)
{
    private bool _discard;
    private int _released;

    public WireConnection Connection { get; } = connection;

    public void Discard() => _discard = true;

    public void Release()
    {
        if (Interlocked.Exchange(ref _released, 1) == 0)
        {
            pool.GiveBack(Connection, _discard);
        }
    }
}
