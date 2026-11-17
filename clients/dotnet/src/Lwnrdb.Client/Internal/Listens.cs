using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Query;

namespace Lwnrdb.Client.Internal;

internal sealed class Listens(ConnectionFactory factory, RequestEncoder encoder, TimeSpan requestTimeout) : IAsyncDisposable
{
    private readonly List<ListenChannel> _channels = [];
    private readonly SemaphoreSlim _queue = new(1, 1);
    private readonly Lock _sync = new();

    public int OpenChannels
    {
        get
        {
            lock (_sync)
            {
                return _channels.Count(channel => channel.IsOpen);
            }
        }
    }

    public async Task<Subscription> ListenAsync<T>(
        string database,
        string collection,
        ImmutableArray<PipelineStep> steps,
        Func<Document, T> decode,
        ListenHandler<T> handler,
        CancellationToken ct)
    {
        var request = encoder.Listen(database, collection, steps);
        await _queue.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            return await RegisterAsync(request, decode, handler, ct).ConfigureAwait(false);
        }
        finally
        {
            _queue.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        ListenChannel[] channels;
        lock (_sync)
        {
            channels = [.. _channels];
            _channels.Clear();
        }
        await Task.WhenAll(channels.Select(channel => channel.CloseAsync())).ConfigureAwait(false);
    }

    private async Task<Subscription> RegisterAsync<T>(JsonObject request, Func<Document, T> decode, ListenHandler<T> handler, CancellationToken ct)
    {
        ListenChannel[] existing;
        lock (_sync)
        {
            existing = [.. _channels];
        }
        foreach (var channel in existing.Where(channel => channel.IsOpen))
        {
            try
            {
                var subscription = await channel.RegisterAsync(request, decode, handler, ct).ConfigureAwait(false);
                if (subscription is not null)
                {
                    return subscription;
                }
            }
            catch (LwnrdbException error) when (error.HasCode(ErrorCode.TooManyListens))
            {
            }
        }
        var opened = new ListenChannel(await factory.OpenAsync(ct).ConfigureAwait(false), requestTimeout, encoder, Remove);
        lock (_sync)
        {
            _channels.Add(opened);
        }
        Subscription? registered;
        try
        {
            registered = await opened.RegisterAsync(request, decode, handler, ct).ConfigureAwait(false);
        }
        catch
        {
            await DiscardAsync(opened).ConfigureAwait(false);
            throw;
        }
        if (registered is null)
        {
            await DiscardAsync(opened).ConfigureAwait(false);
            throw new LwnrdbException("a new listen connection closed before the listen registered");
        }
        return registered;
    }

    private async Task DiscardAsync(ListenChannel channel)
    {
        Remove(channel);
        await channel.CloseAsync().ConfigureAwait(false);
    }

    private void Remove(ListenChannel channel)
    {
        lock (_sync)
        {
            _channels.Remove(channel);
        }
    }
}
