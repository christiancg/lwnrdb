package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.SharedByteBudget;

public class SharedByteBudgetTest {
    private static final int THREADS = 8;
    private static final int RESERVATIONS = 10_000;

    @Test
    public void reservesUpToTheLimitAndRefusesPastIt() {
        final var budget = new SharedByteBudget(() -> 100L);

        assertTrue(budget.tryReserve(60L));
        assertTrue(budget.tryReserve(40L));
        assertFalse(budget.tryReserve(1L));
        assertEquals(100L, budget.reservedBytes());
    }

    @Test
    public void releaseMakesRoomAgain() {
        final var budget = new SharedByteBudget(() -> 100L);
        assertTrue(budget.tryReserve(100L));

        budget.release(30L);
        budget.release(0L);

        assertTrue(budget.tryReserve(30L));
        assertFalse(budget.tryReserve(1L));
    }

    @Test
    public void concurrentReservationsNeverPassTheLimit() throws InterruptedException {
        final var limit = THREADS * RESERVATIONS / 2;
        final var budget = new SharedByteBudget(() -> limit);
        final var granted = new AtomicLong();
        final var start = new CountDownLatch(1);
        final var threads = new ArrayList<Thread>();
        for (var t = 0; t < THREADS; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (var i = 0; i < RESERVATIONS; i++) {
                    if (budget.tryReserve(1L)) {
                        granted.incrementAndGet();
                    }
                }
            }));
        }
        start.countDown();
        for (final var thread : threads) {
            thread.join();
        }

        assertEquals(limit, granted.get());
        assertEquals(limit, budget.reservedBytes());
    }
}
