# RetryPolicy

How the client retries refusals that are safe to retry, and reads whose connection was lost.

## maxAttempts

Attempts in all, at least 1.

## initialBackoffMs

The pause before the first retry, doubled for each one after.

## maxBackoffMs

The longest pause.
