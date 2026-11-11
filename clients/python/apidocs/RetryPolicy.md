# RetryPolicy

How often and how long the client retries a request that is safe to retry. Backoffs are seconds.

## backoff_before

The delay before retrying after this attempt: the initial backoff doubled per attempt, capped.
