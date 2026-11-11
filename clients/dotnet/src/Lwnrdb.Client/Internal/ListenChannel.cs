using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;

namespace Lwnrdb.Client.Internal;

internal sealed class ListenChannel
{
    private const string ListenIdField = "listenId";

    private readonly WireConnection _connection;
    private readonly TimeSpan _requestTimeout;
    private readonly RequestEncoder _encoder;
    private readonly Channel<Func<Task>> _tasks = Channel.CreateUnbounded<Func<Task>>(new UnboundedChannelOptions { SingleReader = true });
    private readonly Dictionary<string, IRegistration> _registrations = new(StringComparer.Ordinal);
    private readonly Dictionary<string, List<JsonObject>> _early = new(StringComparer.Ordinal);
    private int _active;
    private volatile bool _closing;

    public ListenChannel(WireConnection connection, TimeSpan requestTimeout, RequestEncoder encoder, Action<ListenChannel> onLost)
    {
        _connection = connection;
        _requestTimeout = requestTimeout;
        _encoder = encoder;
        connection.OnPush = frame => Dispatch(() => DeliverAsync(frame));
        connection.OnClose = cause =>
        {
            Dispatch(() => LostAsync(cause));
            _tasks.Writer.TryComplete();
            onLost(this);
        };
        _ = Task.Run(DrainAsync);
    }

    private interface IRegistration
    {
        Subscription Subscription { get; }

        Task UpdateAsync(JsonObject frame, bool initial);

        Task EndedAsync(ListenEndedException reason);

        Task FailedAsync(Exception error);
    }

    public bool IsOpen => !_closing && _connection.IsOpen;

    public async Task<Subscription?> RegisterAsync<T>(
        JsonObject request,
        Func<Document, T> decode,
        ListenHandler<T> handler,
        CancellationToken ct)
    {
        if (!Reserve())
        {
            return null;
        }
        JsonObject reply;
        try
        {
            reply = ResponseDecoder.Check(await _connection.RequestAsync(request, _requestTimeout, ct).ConfigureAwait(false));
        }
        catch
        {
            await ReleaseAsync().ConfigureAwait(false);
            throw;
        }
        var listenId = ResponseDecoder.TextOf(reply[ListenIdField]) ?? string.Empty;
        var registration = new Registration<T>(new Subscription(listenId, this), decode, handler);
        Dispatch(() => ActivateAsync(listenId, registration, reply));
        return registration.Subscription;
    }

    public async Task StopAsync(string listenId)
    {
        try
        {
            ResponseDecoder.Check(await _connection
                .RequestAsync(RequestEncoder.With(RequestEncoder.Request("STOP_LISTEN"), ListenIdField, listenId), _requestTimeout, CancellationToken.None)
                .ConfigureAwait(false));
        }
        finally
        {
            Dispatch(() =>
            {
                _registrations.Remove(listenId);
                return Task.CompletedTask;
            });
            await ReleaseAsync().ConfigureAwait(false);
        }
    }

    public async Task CloseAsync()
    {
        _closing = true;
        await _connection.CloseGracefullyAsync(_requestTimeout).ConfigureAwait(false);
    }

    private void Dispatch(Func<Task> task) => _tasks.Writer.TryWrite(task);

    private async Task DrainAsync()
    {
        await foreach (var task in _tasks.Reader.ReadAllAsync().ConfigureAwait(false))
        {
            try
            {
                await task().ConfigureAwait(false);
            }
            catch (Exception error) when (error is not OutOfMemoryException)
            {
                continue;
            }
        }
    }

    private bool Reserve()
    {
        if (!IsOpen)
        {
            return false;
        }
        Interlocked.Increment(ref _active);
        return true;
    }

    private async Task ReleaseAsync()
    {
        if (Interlocked.Decrement(ref _active) == 0 && !_closing)
        {
            await CloseAsync().ConfigureAwait(false);
        }
    }

    private async Task ActivateAsync(string listenId, IRegistration registration, JsonObject initial)
    {
        _registrations[listenId] = registration;
        await registration.UpdateAsync(initial, true).ConfigureAwait(false);
        if (_early.Remove(listenId, out var buffered))
        {
            foreach (var frame in buffered)
            {
                await DeliverAsync(frame).ConfigureAwait(false);
            }
        }
    }

    private async Task DeliverAsync(JsonObject frame)
    {
        var listenId = ResponseDecoder.TextOf(frame[ListenIdField]) ?? string.Empty;
        if (!_registrations.TryGetValue(listenId, out var registration))
        {
            if (!_early.TryGetValue(listenId, out var buffered))
            {
                _early[listenId] = buffered = [];
            }
            buffered.Add(frame);
            return;
        }
        if (FrameRouter.IsListenEnd(frame))
        {
            _registrations.Remove(listenId);
            registration.Subscription.MarkEnded();
            await registration.EndedAsync((ListenEndedException)ResponseDecoder.Raise(frame)).ConfigureAwait(false);
            await ReleaseAsync().ConfigureAwait(false);
            return;
        }
        if (registration.Subscription.IsActive)
        {
            await registration.UpdateAsync(frame, false).ConfigureAwait(false);
        }
    }

    private async Task LostAsync(LwnrdbException cause)
    {
        _closing = true;
        var lost = _registrations.Values.ToList();
        _registrations.Clear();
        _early.Clear();
        foreach (var registration in lost)
        {
            if (registration.Subscription.MarkEnded())
            {
                await registration.FailedAsync(cause).ConfigureAwait(false);
            }
        }
    }

    private sealed class Registration<T>(Subscription subscription, Func<Document, T> decode, ListenHandler<T> handler)
        : IRegistration
    {
        public Subscription Subscription { get; } = subscription;

        public async Task UpdateAsync(JsonObject frame, bool initial)
        {
            try
            {
                var results = ResponseDecoder.Documents(frame, "results").Select(decode).ToList();
                var update = new ListenUpdate<T>(Subscription.ListenId, results, ResponseDecoder.TextOf(frame["resultHash"]) ?? string.Empty, initial);
                await handler.OnUpdate(update, Subscription.Lifetime).ConfigureAwait(false);
            }
            catch (Exception error) when (error is not OutOfMemoryException)
            {
                await FailedAsync(error).ConfigureAwait(false);
                await Subscription.DisposeAsync().ConfigureAwait(false);
            }
        }

        public async Task EndedAsync(ListenEndedException reason)
        {
            try
            {
                if (handler.OnEnded is not null)
                {
                    await handler.OnEnded(reason).ConfigureAwait(false);
                }
            }
            catch (Exception error) when (error is not OutOfMemoryException)
            {
                await FailedAsync(error).ConfigureAwait(false);
            }
        }

        public async Task FailedAsync(Exception error)
        {
            try
            {
                if (handler.OnError is not null)
                {
                    await handler.OnError(error).ConfigureAwait(false);
                }
            }
            catch (Exception ignored) when (ignored is not OutOfMemoryException)
            {
                return;
            }
        }
    }
}
