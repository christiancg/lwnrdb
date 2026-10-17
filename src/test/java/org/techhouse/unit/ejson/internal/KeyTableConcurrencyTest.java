package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.internal.KeyTable;

public class KeyTableConcurrencyTest {
    private static final int RACERS = 8;
    private static final int CONCURRENT_READERS = 16;
    private static final int LOOKUPS_PER_THREAD = 200_000;

    private static void race(int threads, Runnable work) throws InterruptedException {
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(threads);
        final var failure = new AtomicReference<Throwable>();
        for (var thread = 0; thread < threads; thread++) {
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    work.run();
                } catch (InterruptedException | RuntimeException | AssertionError e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(2, TimeUnit.MINUTES), "racers finished");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    public void returnedKeyAlwaysEqualsItsText() throws InterruptedException {
        final var table = new KeyTable(16);
        final var source = new StringBuilder();
        final var seed = new SplittableRandom(5L);
        for (var index = 0; index < 4096; index++) {
            source.append((char) ('a' + seed.nextInt(3)));
        }
        final var text = source.toString();
        final var seeds = new SplittableRandom(9L);
        final var threadSeeds = new ArrayList<Long>();
        for (var thread = 0; thread < RACERS; thread++) {
            threadSeeds.add(seeds.nextLong());
        }
        final var next = new AtomicInteger();
        race(RACERS, () -> {
            final var rng = new SplittableRandom(threadSeeds.get(next.getAndIncrement()));
            for (var lookup = 0; lookup < LOOKUPS_PER_THREAD; lookup++) {
                final var start = rng.nextInt(text.length() - 80);
                final var end = start + rng.nextInt(Globals.KEY_TABLE_MAX_KEY_LENGTH + 4);
                final var key = table.lookup(text, start, end);
                if (!key.equals(text.substring(start, end))) {
                    throw new AssertionError("lookup of [" + start + "," + end + ") returned " + key);
                }
            }
        });
        assertTrue(table.occupiedSlots() <= table.capacity());
    }

    @Test
    public void concurrentLookupsOfEqualKeysWithoutChurnReturnOneInstance() throws InterruptedException {
        final var table = new KeyTable(Globals.KEY_TABLE_SLOTS);
        final var names = List.of("name", "email", "age", "address", "phone");
        final List<Set<String>> seen = new ArrayList<>();
        for (final var ignored : names) {
            seen.add(Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>())));
        }
        race(CONCURRENT_READERS, () -> {
            for (var round = 0; round < 1000; round++) {
                for (var index = 0; index < names.size(); index++) {
                    final var source = "[" + names.get(index) + "]";
                    seen.get(index).add(table.lookup(source, 1, source.length() - 1));
                }
            }
        });
        for (var index = 0; index < names.size(); index++) {
            assertEquals(1, seen.get(index).size(), names.get(index) + " had more than one instance");
        }
    }
}
