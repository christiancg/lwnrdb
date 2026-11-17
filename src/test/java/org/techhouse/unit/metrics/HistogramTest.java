package org.techhouse.unit.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.techhouse.metrics.Histogram;

public class HistogramTest {
    private static final long ONE_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    public void test_one_observation_lands_in_one_bucket_and_counts_are_cumulative() {
        final var histogram = new Histogram();

        histogram.observeNanos(3 * ONE_MILLISECOND);

        final var snapshot = histogram.snapshot();
        final var bounds = Histogram.boundLabels();
        for (var i = 0; i < bounds.size(); i++) {
            final var expected = Double.parseDouble(bounds.get(i)) >= 0.003 ? 1L : 0L;
            assertEquals(expected, snapshot.countAtMost(i), "le=" + bounds.get(i));
        }
        assertEquals(1L, snapshot.count());
        assertEquals(1L, histogram.count());
    }

    @Test
    public void test_a_value_on_a_bound_belongs_to_that_bound() {
        final var histogram = new Histogram();

        histogram.observeNanos(ONE_MILLISECOND);

        final var snapshot = histogram.snapshot();
        final var oneMillisecond = Histogram.boundLabels().indexOf("0.001");
        assertEquals(0L, snapshot.countAtMost(oneMillisecond - 1));
        assertEquals(1L, snapshot.countAtMost(oneMillisecond));
    }

    @Test
    public void test_a_value_past_the_last_bound_counts_only_in_the_total() {
        final var histogram = new Histogram();

        histogram.observeNanos(TimeUnit.SECONDS.toNanos(30));

        final var snapshot = histogram.snapshot();
        assertEquals(0L, snapshot.countAtMost(Histogram.boundLabels().size() - 1));
        assertEquals(1L, snapshot.count());
    }

    @Test
    public void test_the_sum_is_in_seconds_and_a_negative_duration_counts_as_zero() {
        final var histogram = new Histogram();

        histogram.observeNanos(TimeUnit.MILLISECONDS.toNanos(250));
        histogram.observeNanos(TimeUnit.MILLISECONDS.toNanos(750));
        histogram.observeNanos(-5L);

        final var snapshot = histogram.snapshot();
        assertEquals(1.0, snapshot.sumSeconds(), 1e-9);
        assertEquals(3L, snapshot.count());
        assertEquals(1L, snapshot.countAtMost(0));
    }

    @Test
    public void test_concurrent_observations_keep_an_exact_count() throws InterruptedException {
        final var histogram = new Histogram();
        final var threads = new ArrayList<Thread>();
        for (var t = 0; t < 8; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                for (var i = 0; i < 10_000; i++) {
                    histogram.observeNanos(i * 1000L);
                }
            }));
        }
        for (final var thread : threads) {
            thread.join();
        }

        assertEquals(80_000L, histogram.snapshot().count());
    }
}
