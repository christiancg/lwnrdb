package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class ResourceLockingEvictionRaceTest {
    private static final String DB = "racedb";
    private static final String COLL = "racecoll";
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

    private ReentrantReadWriteLock heldOrphan(String lockName) throws Exception {
        final var orphan = new ReentrantReadWriteLock();
        orphan.writeLock().lock();
        locks().put(lockName, orphan);
        return orphan;
    }

    @SuppressWarnings("BusyWait")
    private static void awaitQueued(ReentrantReadWriteLock lock) throws InterruptedException {
        final var deadline = System.currentTimeMillis() + 5000;
        while (!lock.hasQueuedThreads() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(lock.hasQueuedThreads(), "the contender must be queued on the lock it looked up");
    }

    private void replaceWithHeldLock(String lockName, ReentrantReadWriteLock orphan) throws Exception {
        locks().remove(lockName);
        rl.lockWrite(lockName);
        assertNotSame(orphan, locks().get(lockName));
        orphan.writeLock().unlock();
    }

    @Test
    public void test_write_acquire_of_an_evicted_lock_retries_onto_the_mapped_one() throws Exception {
        final var orphan = heldOrphan(COLL_ID);
        final var contender = CompletableFuture.supplyAsync(() -> {
            try {
                rl.lock(DB, COLL);
                return rl.releaseWrite(DB, COLL);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        awaitQueued(orphan);
        replaceWithHeldLock(COLL_ID, orphan);
        Thread.sleep(200);
        assertFalse(contender.isDone(), "the contender must not proceed on the orphan while the mapped lock is held");
        assertTrue(rl.releaseWrite(DB, COLL));
        assertTrue(contender.get(5, TimeUnit.SECONDS), "the contender must end up holding the mapped lock");
    }

    @Test
    public void test_timed_write_acquire_of_an_evicted_lock_waits_on_the_mapped_one() throws Exception {
        final var orphan = heldOrphan(COLL_ID);
        final var contender = CompletableFuture.supplyAsync(() -> {
            try {
                return rl.tryLockWrite(DB, COLL, 300);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        awaitQueued(orphan);
        replaceWithHeldLock(COLL_ID, orphan);
        assertFalse(contender.get(5, TimeUnit.SECONDS),
                "a timed acquire must not report success while another thread holds the mapped lock");
        assertFalse(orphan.isWriteLocked(), "the orphan acquired during the retry must be released");
    }

    @Test
    public void test_read_acquire_of_an_evicted_lock_waits_for_the_mapped_writer() throws Exception {
        final var orphan = heldOrphan(COLL_ID);
        final var contender = CompletableFuture.supplyAsync(() -> {
            try {
                rl.lockRead(DB, COLL);
                rl.releaseRead(DB, COLL);
                return true;
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        awaitQueued(orphan);
        replaceWithHeldLock(COLL_ID, orphan);
        Thread.sleep(200);
        assertFalse(contender.isDone(), "a reader must not proceed on the orphan while the mapped writer holds it");
        rl.releaseWrite(DB, COLL);
        assertTrue(contender.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_database_barrier_exclusive_acquire_of_an_evicted_lock_waits_on_the_mapped_one() throws Exception {
        final var orphan = heldOrphan(DB);
        final var contender = CompletableFuture.supplyAsync(() -> {
            try {
                return rl.tryLockDatabaseExclusive(DB, 300);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        awaitQueued(orphan);
        replaceWithHeldLock(DB, orphan);
        assertFalse(contender.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_database_barrier_shared_acquire_of_an_evicted_lock_succeeds_once_released() throws Exception {
        final var orphan = heldOrphan(DB);
        final var contender = CompletableFuture.supplyAsync(() -> {
            try {
                final var acquired = rl.tryLockDatabaseShared(DB, 5000);
                rl.releaseDatabaseShared(DB);
                return acquired;
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        awaitQueued(orphan);
        replaceWithHeldLock(DB, orphan);
        rl.releaseDatabaseExclusive(DB);
        assertTrue(contender.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_a_retried_acquire_leaves_the_lock_evictable_after_release() throws Exception {
        final var orphan = heldOrphan(COLL_ID);
        final var contender = CompletableFuture.supplyAsync(() -> {
            try {
                rl.lock(DB, COLL);
                return rl.releaseWrite(DB, COLL);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        awaitQueued(orphan);
        replaceWithHeldLock(COLL_ID, orphan);
        rl.releaseWrite(DB, COLL);
        assertTrue(contender.get(5, TimeUnit.SECONDS));
        rl.removeLock(DB, COLL);
        assertNull(locks().get(COLL_ID));
    }
}
