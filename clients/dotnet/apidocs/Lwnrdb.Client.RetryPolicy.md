How many times, and how long apart, a request refused with a retryable error is sent again.

Only refusals the server marks as safe to retry are retried (`ErrorCode.IsRetryable`, such as `503-2` and `503-16`). A request whose outcome is unknown is never retried. The wait doubles from `InitialBackoff` up to `MaxBackoff`, with jitter.

## RetryPolicy(int, TimeSpan, TimeSpan)
Creates a policy.

@param maxAttempts the total number of attempts, at least 1; 1 sends once
@param initialBackoff the wait before the first retry, not negative
@param maxBackoff the longest wait, at least `initialBackoff`
@exception System.ArgumentOutOfRangeException when `maxAttempts` is below 1
@exception System.ArgumentException when the backoffs do not satisfy `0 <= initialBackoff <= maxBackoff`

## BackoffBefore(int)
Returns the wait before a given attempt, without jitter: `InitialBackoff` doubled for each earlier retry, capped at `MaxBackoff`.

@param attempt the attempt that is about to start, counted from 1
@returns the wait

## Default
Five attempts, waiting 50 ms at first and at most 2 seconds.

## InitialBackoff
The wait before the first retry.

## MaxAttempts
The total number of attempts, the first included.

## MaxBackoff
The longest wait between attempts.

## None
Sends each request once and never retries.
