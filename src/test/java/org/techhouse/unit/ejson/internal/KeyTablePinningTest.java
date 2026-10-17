package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.KeyTable;

public class KeyTablePinningTest {
    private static final int SLOTS = 64;
    private static final int PIN_HITS = 3;

    private static String hit(KeyTable table, String name, int times) {
        var key = table.lookup(name, 0, name.length());
        for (var hit = 1; hit < times; hit++) {
            key = table.lookup(name, 0, name.length());
        }
        return key;
    }

    @Test
    public void aNamePinsOnceItReachesTheHitThreshold() {
        final var table = new KeyTable(SLOTS, 8, PIN_HITS);
        final var key = hit(table, "alpha", PIN_HITS - 1);
        assertTrue(table.holds(key));
        assertFalse(table.holdsPinned(key), "two hits are not enough to pin");

        assertSame(key, hit(table, "alpha", 1));
        assertTrue(table.holdsPinned(key));
        assertEquals(1, table.pinnedSlots());
    }

    @Test
    public void keysSeenOnceAreNeverPinned() {
        final var table = new KeyTable(SLOTS, 8, PIN_HITS);
        for (var index = 0; index < 10_000; index++) {
            hit(table, "data-key-" + index, 1);
        }
        assertEquals(0, table.pinnedSlots(), "data-like keys churn through without pinning");
    }

    @Test
    public void aPinnedNameSurvivesAnyChurn() {
        final var table = new KeyTable(16, 4, PIN_HITS);
        final var key = hit(table, "hot", PIN_HITS);
        assertTrue(table.holdsPinned(key));
        for (var churn = 0; churn < 100_000; churn++) {
            hit(table, "churn-" + churn, 2);
        }

        assertTrue(table.holdsPinned(key), "a pinned instance is never displaced");
        assertSame(key, hit(table, "hot", 1));
    }

    @Test
    public void thePinCapHoldsAndUnpinnedNamesStayShared() {
        final var table = new KeyTable(SLOTS, 2, PIN_HITS);
        final var keys = new ArrayList<String>();
        for (var index = 0; index < 5; index++) {
            keys.add(hit(table, "name-" + index, PIN_HITS + 2));
        }

        assertEquals(2, table.pinnedSlots());
        assertEquals(2, keys.stream().filter(table::holdsPinned).count());
        assertTrue(keys.stream().allMatch(table::holds), "names past the cap are still shared, just never pinned");
    }

    @Test
    public void pinningRacingWithChurnKeepsTheCapAndEveryKeyItsText() throws InterruptedException {
        final var table = new KeyTable(16, 3, PIN_HITS);
        final var failure = new AtomicReference<Throwable>();
        final var start = new CountDownLatch(1);
        final var threads = new ArrayList<Thread>();
        for (var thread = 0; thread < 8; thread++) {
            final var id = thread;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (var round = 0; round < 50_000; round++) {
                        final var name = round % 3 == 0 ? "hot-" + (round % 5) : "cold-" + id + "-" + round;
                        final var key = table.lookup(name, 0, name.length());
                        if (!key.equals(name)) {
                            throw new AssertionError(name + " came back as " + key);
                        }
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }));
        }
        start.countDown();
        for (final var thread : threads) {
            assertTrue(thread.join(Duration.ofSeconds(60)), "a racer finished in time");
        }

        assertNull(failure.get());
        assertTrue(table.pinnedSlots() <= 3, "pinned " + table.pinnedSlots() + " over a cap of 3");
    }
}
