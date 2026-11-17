package org.techhouse.fs;

import java.util.concurrent.atomic.LongAdder;
import org.techhouse.metrics.Histogram;

public final class ForceMetrics {
    private static final Histogram durations = new Histogram();
    private static final LongAdder failures = new LongAdder();

    private ForceMetrics() {
    }

    static void observe(long nanos) {
        durations.observeNanos(nanos);
    }

    static void failed() {
        failures.increment();
    }

    public static long count() {
        return durations.count();
    }

    public static long failures() {
        return failures.sum();
    }

    public static Histogram durations() {
        return durations;
    }
}
