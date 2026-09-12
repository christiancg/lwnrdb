package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.log.Logger;

// Lives in org.techhouse.cluster rather than unit/cluster because CoalescingSweep is package-private.
public class CoalescingSweepTest {
    private static final Logger logger = Logger.logFor(CoalescingSweepTest.class);

    @Test
    public void test_concurrent_schedules_coalesce_to_one_queued_run() throws Exception {
        final var started = new CountDownLatch(1);
        final var gate = new CountDownLatch(1);
        final var completed = new CountDownLatch(2);
        final var runs = new AtomicInteger();
        final var sweep = new CoalescingSweep(logger, "test-coalescing", "Test", () -> {
            runs.incrementAndGet();
            started.countDown();
            gate.await();
            completed.countDown();
        });

        sweep.schedule();
        assertTrue(started.await(5, TimeUnit.SECONDS), "the first pass never started");
        for (var i = 0; i < 50; i++) {
            sweep.schedule();
        }
        gate.countDown();

        assertTrue(completed.await(5, TimeUnit.SECONDS), "the coalesced pass never ran");
        assertEquals(2, runs.get(), "51 requests must collapse to the running pass plus one queued");
        sweep.stopPeriodic();
    }

    @Test
    public void test_a_failing_pass_does_not_stop_the_next_one() throws Exception {
        final var runs = new AtomicInteger();
        final var secondRun = new CountDownLatch(2);
        final var sweep = new CoalescingSweep(logger, "test-failing", "Test", () -> {
            runs.incrementAndGet();
            secondRun.countDown();
            throw new IllegalStateException("boom");
        });

        sweep.schedule();
        assertTrue(waitFor(() -> runs.get() == 1), "the first pass never ran");
        sweep.schedule();

        assertTrue(secondRun.await(5, TimeUnit.SECONDS), "the sweep stopped after a failing pass");
        sweep.stopPeriodic();
    }

    @Test
    public void test_a_non_positive_interval_starts_no_periodic_scheduler() {
        final var sweep = new CoalescingSweep(logger, "test-interval", "Test", () -> {
        });

        sweep.startPeriodic(0L);

        sweep.stopPeriodic();
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }
}
