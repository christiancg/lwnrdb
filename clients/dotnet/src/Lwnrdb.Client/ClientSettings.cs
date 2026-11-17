using System;
using System.Collections.Generic;
using System.Text.Json;

namespace Lwnrdb.Client;

public sealed record ClientSettings
{
    public IReadOnlyList<ServerAddress> Hosts { get; init; } = [new("localhost")];

    public Credentials? Credentials { get; init; }

    public TlsSettings? Tls { get; init; }

    public int PoolSize { get; init; } = 8;

    public TimeSpan ConnectTimeout { get; init; } = TimeSpan.FromSeconds(10);

    public TimeSpan RequestTimeout { get; init; } = TimeSpan.FromSeconds(330);

    public TimeSpan AcquireTimeout { get; init; } = TimeSpan.FromSeconds(30);

    public TimeSpan ShutdownTimeout { get; init; } = TimeSpan.FromSeconds(10);

    public long MaxReplyBytes { get; init; } = 256L * 1024 * 1024;

    public RetryPolicy RetryPolicy { get; init; } = RetryPolicy.Default;

    public TimeZoneInfo? TemporalZone { get; init; }

    public JsonSerializerOptions? Json { get; init; }

    internal ClientSettings Validated()
    {
        if (Hosts is null || Hosts.Count == 0)
        {
            throw new ArgumentException("Hosts must name at least one server", nameof(Hosts));
        }
        foreach (var host in Hosts)
        {
            ArgumentNullException.ThrowIfNull(host, nameof(Hosts));
        }
        ArgumentOutOfRangeException.ThrowIfLessThan(PoolSize, 1, nameof(PoolSize));
        Positive(ConnectTimeout, nameof(ConnectTimeout));
        Positive(RequestTimeout, nameof(RequestTimeout));
        Positive(AcquireTimeout, nameof(AcquireTimeout));
        Positive(ShutdownTimeout, nameof(ShutdownTimeout));
        ArgumentOutOfRangeException.ThrowIfLessThan(MaxReplyBytes, 1, nameof(MaxReplyBytes));
        ArgumentNullException.ThrowIfNull(RetryPolicy, nameof(RetryPolicy));
        return this;
    }

    private static void Positive(TimeSpan value, string name)
    {
        if (value <= TimeSpan.Zero || value.TotalMilliseconds > int.MaxValue)
        {
            throw new ArgumentOutOfRangeException(name, value, $"{name} must be positive and at most {int.MaxValue} ms");
        }
    }
}
