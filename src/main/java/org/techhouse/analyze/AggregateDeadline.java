package org.techhouse.analyze;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class AggregateDeadline implements AutoCloseable {
    private static final ThreadLocal<AggregateDeadline> CURRENT = new ThreadLocal<>();
    private static final int CHECK_MASK = 0xFF;
    private final long deadlineNanos;
    private final AggregateDeadline previous;
    private int checks;

    private AggregateDeadline(long timeoutMillis, AggregateDeadline previous) {
        this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        this.previous = previous;
    }

    public static AggregateDeadline open(long timeoutMillis) {
        final var previous = CURRENT.get();
        if (timeoutMillis <= 0) {
            return new AggregateDeadline(Long.MAX_VALUE / 2, previous);
        }
        final var deadline = new AggregateDeadline(timeoutMillis, previous);
        CURRENT.set(deadline);
        return deadline;
    }

    public static <T> Stream<T> watch(Stream<T> stream) {
        final var deadline = CURRENT.get();
        if (deadline == null || stream == null) {
            return stream;
        }
        return stream.peek(_ -> deadline.check());
    }

    private void check() {
        if ((++checks & CHECK_MASK) == 0 && System.nanoTime() > deadlineNanos) {
            throw new Expired();
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

    public static final class Expired extends RuntimeException {
        private Expired() {
            super("The aggregation exceeded its time budget", null, false, false);
        }
    }
}
