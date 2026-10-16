package org.techhouse.analyze;

import org.techhouse.ops.AggregateLimitException;
import org.techhouse.ops.ErrorCode;

public final class AggregateResultBudget implements AutoCloseable {
    private static final ThreadLocal<AggregateResultBudget> CURRENT = new ThreadLocal<>();
    private final long maxBytes;
    private final AggregateResultBudget previous;
    private long bytes;

    private AggregateResultBudget(long maxBytes, AggregateResultBudget previous) {
        this.maxBytes = maxBytes;
        this.previous = previous;
    }

    public static AggregateResultBudget open(long maxBytes) {
        final var budget = new AggregateResultBudget(maxBytes, CURRENT.get());
        CURRENT.set(budget);
        return budget;
    }

    public static void charge(long bytes) {
        final var budget = CURRENT.get();
        if (budget != null) {
            budget.add(bytes);
        }
    }

    private void add(long charged) {
        bytes += charged;
        if (bytes > maxBytes) {
            throw new AggregateLimitException(ErrorCode.AGGREGATE_RESULT_TOO_LARGE);
        }
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
