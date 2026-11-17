export interface RetryPolicy {
    readonly maxAttempts: number;
    readonly initialBackoffMs: number;
    readonly maxBackoffMs: number;
}

export const DEFAULT_RETRY_POLICY: RetryPolicy = Object.freeze({
    maxAttempts: 5,
    initialBackoffMs: 50,
    maxBackoffMs: 2000,
});

export const NO_RETRIES: RetryPolicy = Object.freeze({ maxAttempts: 1, initialBackoffMs: 0, maxBackoffMs: 0 });

const MAX_DOUBLINGS = 30;

export function checkRetryPolicy(policy: RetryPolicy): RetryPolicy {
    if (!Number.isInteger(policy.maxAttempts) || policy.maxAttempts < 1) {
        throw new RangeError(`maxAttempts must be an integer of at least 1: ${policy.maxAttempts}`);
    }
    if (
        !Number.isFinite(policy.initialBackoffMs) ||
        !Number.isFinite(policy.maxBackoffMs) ||
        policy.initialBackoffMs < 0 ||
        policy.maxBackoffMs < policy.initialBackoffMs
    ) {
        throw new RangeError("backoffs must satisfy 0 <= initialBackoffMs <= maxBackoffMs");
    }
    return Object.freeze({ ...policy });
}

export function backoffBefore(policy: RetryPolicy, attempt: number): number {
    const doublings = Math.min(Math.max(attempt - 1, 0), MAX_DOUBLINGS);
    return Math.min(policy.initialBackoffMs * 2 ** doublings, policy.maxBackoffMs);
}
