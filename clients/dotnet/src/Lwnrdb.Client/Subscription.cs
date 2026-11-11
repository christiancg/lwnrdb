using System;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;

namespace Lwnrdb.Client;

public sealed class Subscription : IAsyncDisposable
{
    private readonly ListenChannel _channel;
    private readonly CancellationTokenSource _lifetime = new();
    private int _closed;

    internal Subscription(string listenId, ListenChannel channel)
    {
        ListenId = listenId;
        _channel = channel;
    }

    public string ListenId { get; }

    public bool IsActive => Volatile.Read(ref _closed) == 0 && _channel.IsOpen;

    internal CancellationToken Lifetime => _lifetime.Token;

    public async ValueTask DisposeAsync()
    {
        if (!MarkEnded())
        {
            return;
        }
        if (_channel.IsOpen)
        {
            await _channel.StopAsync(ListenId).ConfigureAwait(false);
        }
    }

    internal bool MarkEnded()
    {
        if (Interlocked.Exchange(ref _closed, 1) == 1)
        {
            return false;
        }
        _lifetime.Cancel();
        return true;
    }
}
