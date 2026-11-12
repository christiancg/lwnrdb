package org.techhouse.metrics;

import java.util.List;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

public final class Histogram {
    private static final double NANOS_PER_SECOND = 1_000_000_000d;
    private static final List<String> BOUND_LABELS = List.of("0.0005", "0.001", "0.0025", "0.005", "0.01", "0.025",
            "0.05", "0.1", "0.25", "0.5", "1", "2.5", "5", "10");
    private static final double[] BOUNDS = BOUND_LABELS.stream().mapToDouble(Double::parseDouble).toArray();
    private final LongAdder[] cells = new LongAdder[BOUNDS.length + 1];
    private final DoubleAdder sumSeconds = new DoubleAdder();

    public Histogram() {
        for (var i = 0; i < cells.length; i++) {
            cells[i] = new LongAdder();
        }
    }

    public static List<String> boundLabels() {
        return BOUND_LABELS;
    }

    public void observeNanos(long nanos) {
        final var seconds = Math.max(0L, nanos) / NANOS_PER_SECOND;
        cells[cellOf(seconds)].increment();
        sumSeconds.add(seconds);
    }

    private static int cellOf(double seconds) {
        for (var i = 0; i < BOUNDS.length; i++) {
            if (seconds <= BOUNDS[i]) {
                return i;
            }
        }
        return BOUNDS.length;
    }

    public Snapshot snapshot() {
        final var cumulative = new long[cells.length];
        long running = 0L;
        for (var i = 0; i < cells.length; i++) {
            running += cells[i].sum();
            cumulative[i] = running;
        }
        return new Snapshot(cumulative, sumSeconds.sum());
    }

    public long count() {
        long total = 0L;
        for (final var cell : cells) {
            total += cell.sum();
        }
        return total;
    }

    public record Snapshot(long[] cumulativeCounts, double sumSeconds) {
        public long count() {
            return cumulativeCounts[cumulativeCounts.length - 1];
        }

        public long countAtMost(int boundIndex) {
            return cumulativeCounts[boundIndex];
        }
    }
}
