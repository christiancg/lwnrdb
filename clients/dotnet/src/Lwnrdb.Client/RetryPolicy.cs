using System;

namespace Lwnrdb.Client;

public sealed record RetryPolicy
{
    private const int MaxDoublings = 30;

    public RetryPolicy(int maxAttempts, TimeSpan initialBackoff, TimeSpan maxBackoff)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(maxAttempts, 1);
        if (initialBackoff < TimeSpan.Zero || maxBackoff < initialBackoff)
        {
            throw new ArgumentException("backoffs must satisfy 0 <= initialBackoff <= maxBackoff");
        }
        MaxAttempts = maxAttempts;
        InitialBackoff = initialBackoff;
        MaxBackoff = maxBackoff;
    }

    public static RetryPolicy Default { get; } = new(5, TimeSpan.FromMilliseconds(50), TimeSpan.FromSeconds(2));

    public static RetryPolicy None { get; } = new(1, TimeSpan.Zero, TimeSpan.Zero);

    public int MaxAttempts { get; }

    public TimeSpan InitialBackoff { get; }

    public TimeSpan MaxBackoff { get; }

    public TimeSpan BackoffBefore(int attempt)
    {
        var doublings = Math.Clamp(attempt - 1, 0, MaxDoublings);
        var ticks = Math.Min(InitialBackoff.Ticks * Math.Pow(2, doublings), MaxBackoff.Ticks);
        return TimeSpan.FromTicks((long)ticks);
    }
}
