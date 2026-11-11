using System;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Internal;

internal interface IRequestChannel
{
    Task<JsonObject> SendAsync(Operation operation, JsonObject request, CancellationToken ct);
}

internal delegate Task Backoff(TimeSpan delay, CancellationToken ct);

internal static class Backoffs
{
    public static Backoff Jittered { get; } = (delay, ct) =>
        Task.Delay(delay / 2 + delay / 2 * Random.Shared.NextDouble(), ct);

    public static bool IsRetryable(Operation operation, LwnrdbException error) => error switch
    {
        ConnectionLostException => operation.IsRead,
        UnavailableException unavailable => unavailable.Code?.IsRetryable == true,
        _ => false,
    };
}

internal sealed class PooledChannel(ConnectionPool pool, RetryPolicy retryPolicy, TimeSpan requestTimeout, Backoff backoff)
    : IRequestChannel
{
    public async Task<JsonObject> SendAsync(Operation operation, JsonObject request, CancellationToken ct)
    {
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                return await OnceAsync(request, ct).ConfigureAwait(false);
            }
            catch (LwnrdbException error) when (attempt < retryPolicy.MaxAttempts && Backoffs.IsRetryable(operation, error))
            {
                await backoff(retryPolicy.BackoffBefore(attempt), ct).ConfigureAwait(false);
            }
        }
    }

    private async Task<JsonObject> OnceAsync(JsonObject request, CancellationToken ct)
    {
        var lease = await pool.AcquireAsync(ct).ConfigureAwait(false);
        try
        {
            return ResponseDecoder.Check(await lease.Connection.RequestAsync(request, requestTimeout, ct).ConfigureAwait(false));
        }
        catch (ConnectionLostException)
        {
            lease.Discard();
            throw;
        }
        finally
        {
            lease.Release();
        }
    }
}

internal sealed class PinnedChannel(WireConnection connection, TimeSpan requestTimeout) : IRequestChannel
{
    public async Task<JsonObject> SendAsync(Operation operation, JsonObject request, CancellationToken ct)
    {
        if (!operation.AllowedInTransaction)
        {
            throw new LwnrdbException($"{operation} is not allowed inside a transaction", null, null, operation.Name);
        }
        return ResponseDecoder.Check(await connection.RequestAsync(request, requestTimeout, ct).ConfigureAwait(false));
    }
}
