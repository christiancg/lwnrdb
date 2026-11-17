How the client retries a request the server refused with a retryable `503`, or a read whose connection was
lost. Writes that lost their connection, unknown outcomes and requests inside a transaction are never retried.

@param maxAttempts total attempts, including the first
@param initialBackoff the pause before the first retry
@param maxBackoff the longest pause; each retry doubles the previous one up to it

## DEFAULT_MAX_ATTEMPTS

The default number of attempts, 5.

## DEFAULT_INITIAL_BACKOFF

The default first pause, 50 milliseconds.

## DEFAULT_MAX_BACKOFF

The default longest pause, 2 seconds.

## defaults()

@return 5 attempts, starting at 50 ms and doubling up to 2 s

## none()

@return a policy that sends every request once

## backoffBefore(int)

@param attempt the attempt that just failed, starting at 1
@return the pause before the next attempt
