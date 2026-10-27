package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

public class SizeCellTest {
    @Test
    public void retiringDrainsTheCellFromItsTier() {
        final var tier = new LongAdder();
        final var cell = new SizeCell(tier);
        cell.add(100L);
        cell.set(250L);
        assertEquals(250L, tier.sum());

        cell.retire();

        assertEquals(0L, tier.sum());
        assertEquals(0L, cell.bytes());
    }

    @Test
    public void aChangeAfterRetiringDrainsAgain() {
        final var tier = new LongAdder();
        final var live = new SizeCell(tier);
        final var retired = new SizeCell(tier);
        live.add(40L);
        retired.add(60L);
        retired.retire();

        retired.add(500L);
        retired.set(900L);

        assertEquals(40L, tier.sum(), "a late write into an evicted resource never reaches the total");
    }

    @Test
    public void adjustersRacingARetireLeaveTheTotalEqualToTheLiveCells() throws Exception {
        for (var round = 0; round < 50; round++) {
            final var tier = new LongAdder();
            final var survivor = new SizeCell(tier);
            final var doomed = new SizeCell(tier);
            final var start = new CountDownLatch(1);
            final var threads = new ArrayList<Thread>();
            for (var adder = 0; adder < 8; adder++) {
                final var target = adder % 2 == 0 ? survivor : doomed;
                threads.add(Thread.ofPlatform().start(() -> {
                    awaitQuietly(start);
                    for (var i = 0; i < 2_000; i++) {
                        target.add(3L);
                        target.add(-1L);
                    }
                }));
            }
            threads.add(Thread.ofPlatform().start(() -> {
                awaitQuietly(start);
                doomed.retire();
            }));
            start.countDown();
            for (final var thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(10));
            }
            doomed.retire();
            assertEquals(survivor.bytes(), tier.sum(), "the total is the sum of the live cells");
            assertTrue(survivor.bytes() > 0L);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
