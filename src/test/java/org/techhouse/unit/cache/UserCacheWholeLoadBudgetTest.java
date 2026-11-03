package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.cache.WholeLoads;
import org.techhouse.fs.FileSystem;

public class UserCacheWholeLoadBudgetTest {
    private static final long FILE_BYTES = 4096L;
    private static final long ESTIMATE = CacheSizeEstimator.estimatePkIndexSizeFromFileBytes(FILE_BYTES);

    private static FileSystem blockingFs(AtomicInteger entered, CountDownLatch release) throws Exception {
        final var fs = mock(FileSystem.class);
        when(fs.pkIndexFileBytes(anyString(), anyString())).thenReturn(FILE_BYTES);
        when(fs.readWholePkIndexFile(anyString(), anyString())).thenAnswer(_ -> {
            entered.incrementAndGet();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return List.of();
        });
        return fs;
    }

    @SuppressWarnings("BusyWait")
    private static boolean waitFor(AtomicInteger value, int expected) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (value.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return value.get() >= expected;
    }

    @Test
    public void transientLoadsWaitForByteBudget() throws Exception {
        final var entered = new AtomicInteger();
        final var release = new CountDownLatch(1);
        final var fs = blockingFs(entered, release);
        final var loads = new WholeLoads(() -> fs, 4, ESTIMATE + ESTIMATE / 2, TimeUnit.SECONDS.toNanos(30));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            final var first = pool.submit(() -> loads.transientPkIndex("db", "first"));
            assertTrue(waitFor(entered, 1));
            final var second = pool.submit(() -> loads.transientPkIndex("db", "second"));
            Thread.sleep(200);

            assertEquals(1, entered.get(), "a second load that would pass the byte budget waits");
            assertEquals(ESTIMATE, loads.reservedBytes());
            assertEquals(2, loads.availablePermits(), "the waiting load holds a permit, not bytes");

            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(2, entered.get());
        assertEquals(0L, loads.reservedBytes());
        assertEquals(4, loads.availablePermits());
    }

    @Test
    public void aLoadLargerThanTheBudgetRunsAloneAndAWaitEndsAtItsBudget() throws Exception {
        final var entered = new AtomicInteger();
        final var release = new CountDownLatch(1);
        final var fs = blockingFs(entered, release);
        final var loads = new WholeLoads(() -> fs, 4, ESTIMATE / 2, TimeUnit.MILLISECONDS.toNanos(100));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            final var first = pool.submit(() -> loads.transientPkIndex("db", "first"));
            assertTrue(waitFor(entered, 1), "nothing else is reserved, so an oversized load still runs");
            final var second = pool.submit(() -> loads.transientPkIndex("db", "second"));
            assertTrue(waitFor(entered, 2), "the wait is bounded and the load proceeds after it");
            assertEquals(2 * ESTIMATE, loads.reservedBytes());

            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0L, loads.reservedBytes());
    }
}
