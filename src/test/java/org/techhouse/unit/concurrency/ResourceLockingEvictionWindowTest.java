package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class ResourceLockingEvictionWindowTest {
    private static final String DB = "windowdb";
    private static final String COLL = "windowcoll";
    private static final String COLL_ID = Cache.getCollectionIdentifier(DB, COLL);

    private final ResourceLocking rl = new ResourceLocking();

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
    }

    private Map<String, ReentrantReadWriteLock> locks() throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, ReentrantReadWriteLock>>() {
        };
        return TestUtils.getPrivateField(rl, "locks", type);
    }

    private final class AcquiredDuringEviction extends ReentrantReadWriteLock {
        private final AtomicBoolean fired = new AtomicBoolean();
        private final CountDownLatch acquired = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private CompletableFuture<Void> contender = CompletableFuture.completedFuture(null);
        private volatile boolean acquiredInTime;

        @Override
        public boolean isWriteLocked() {
            final var observed = super.isWriteLocked();
            if (fired.compareAndSet(false, true)) {
                contender = CompletableFuture.runAsync(this::holdUntilReleased);
                acquiredInTime = awaitQuietly(acquired);
            }
            return observed;
        }

        private void holdUntilReleased() {
            try {
                rl.lock(DB, COLL);
                acquired.countDown();
                final var released = release.await(5, TimeUnit.SECONDS);
                rl.releaseWrite(DB, COLL);
                if (!released) {
                    throw new IllegalStateException("the test never released the contender");
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        private static boolean awaitQuietly(CountDownLatch latch) {
            try {
                return latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    @Test
    public void test_an_acquire_inside_the_eviction_window_keeps_the_lock_mapped() throws Exception {
        final var hooked = new AcquiredDuringEviction();
        locks().put(COLL_ID, hooked);

        rl.removeLock(DB, COLL);

        try {
            assertTrue(hooked.acquiredInTime, "the contender must have acquired inside the window");
            assertSame(hooked, locks().get(COLL_ID),
                    "a lock acquired while the eviction decided must stay mapped, or its holder is unnamed");
            assertFalse(rl.tryLockWrite(DB, COLL, 100),
                    "a second writer must not get a fresh lock while the contender still holds the evicted one");
        } finally {
            hooked.release.countDown();
            hooked.contender.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void test_eviction_never_removes_a_lock_the_caller_holds() throws Exception {
        rl.lock(DB, COLL);
        final var held = locks().get(COLL_ID);

        rl.removeLock(DB, COLL);

        assertSame(held, locks().get(COLL_ID));
        assertTrue(rl.releaseWrite(DB, COLL));
    }

    @Test
    public void test_eviction_never_removes_a_read_held_lock() throws Exception {
        rl.lockRead(DB, COLL);
        final var held = locks().get(COLL_ID);

        rl.removeLock(DB, COLL);

        assertSame(held, locks().get(COLL_ID));
        rl.releaseRead(DB, COLL);
    }

    @Test
    public void test_an_unused_database_barrier_is_evicted_and_can_be_retaken() throws Exception {
        assertTrue(rl.tryLockDatabaseExclusive(DB, 100));
        rl.releaseDatabaseExclusive(DB);

        rl.removeDatabaseLock(DB);

        assertFalse(locks().containsKey(DB));
        assertTrue(rl.tryLockDatabaseExclusive(DB, 100), "an evicted barrier must leave nothing held behind");
        rl.releaseDatabaseExclusive(DB);
    }
}
