package io.github.christiancg.lwnrdb.client;

import java.time.Duration;
import java.util.Objects;

public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
    public static final int DEFAULT_MAX_ATTEMPTS = 5;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(50);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(2);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (initialBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("backoffs must satisfy 0 <= initialBackoff <= maxBackoff");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF);
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO);
    }

    public Duration backoffBefore(int attempt) {
        final var doublings = Math.min(Math.max(attempt - 1, 0), Long.SIZE - 2);
        final var nanos = initialBackoff.toNanos() * (1L << Math.min(doublings, 30));
        return nanos < 0 || nanos > maxBackoff.toNanos() ? maxBackoff : Duration.ofNanos(nanos);
    }
}
