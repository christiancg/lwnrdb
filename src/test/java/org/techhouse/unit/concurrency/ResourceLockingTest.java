package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class ResourceLockingTest {

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
    }

    private Map<String, ReentrantReadWriteLock> locks(ResourceLocking rl)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, ReentrantReadWriteLock>>() {
        };
        return TestUtils.getPrivateField(rl, "locks", type);
    }

    @Test
    public void test_write_lock_blocks_reader_until_released() throws Exception {
        final var rl = new ResourceLocking();
        rl.lock("db", "coll");
        final var acquired = new AtomicBoolean(false);
        final var reader = new Thread(() -> {
            try {
                rl.lockRead("db", "coll");
                acquired.set(true);
            } catch (InterruptedException ignored) {
            }
        });
        reader.start();
        Thread.sleep(200);
        assertFalse(acquired.get(), "reader must block while a writer holds the collection");
        rl.release("db", "coll");
        reader.join(2000);
        assertTrue(acquired.get(), "reader must proceed once the writer releases");
    }

    @Test
    public void test_multiple_readers_proceed_concurrently() throws Exception {
        final var rl = new ResourceLocking();
        rl.lockRead("db", "coll");
        final var acquired = new AtomicBoolean(false);
        final var reader = new Thread(() -> {
            try {
                rl.lockRead("db", "coll");
                acquired.set(true);
                rl.releaseRead("db", "coll");
            } catch (InterruptedException ignored) {
            }
        });
        reader.start();
        reader.join(2000);
        assertTrue(acquired.get(), "a second reader must not be blocked by an existing reader");
        rl.releaseRead("db", "coll");
    }

    @Test
    public void test_read_lock_excludes_writer() throws Exception {
        final var rl = new ResourceLocking();
        final var holding = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var reader = new Thread(() -> {
            try {
                rl.lockRead("db", "coll");
                holding.countDown();
                release.await();
                rl.releaseRead("db", "coll");
            } catch (InterruptedException ignored) {
            }
        });
        reader.start();
        holding.await();
        assertFalse(rl.tryLockWrite("db", "coll"), "writer must not acquire while a reader holds the lock");
        release.countDown();
        reader.join(2000);
        assertTrue(rl.tryLockWrite("db", "coll"), "writer must acquire once readers are gone");
        rl.releaseWrite("db", "coll");
    }

    @Test
    public void test_tryLockWrite_true_when_free() {
        final var rl = new ResourceLocking();
        assertTrue(rl.tryLockWrite("db", "coll"));
        rl.releaseWrite("db", "coll");
    }

    @Test
    public void test_release_unacquired_is_noop() {
        final var rl = new ResourceLocking();
        assertDoesNotThrow(() -> rl.release("db", "coll"));
        assertDoesNotThrow(() -> rl.releaseWrite("db", "coll"));
        assertDoesNotThrow(() -> rl.releaseRead("db", "coll"));
        assertDoesNotThrow(() -> rl.releaseIndex("db", "coll", "field"));
        assertDoesNotThrow(() -> rl.releaseIndexRead("db", "coll", "field"));
    }

    @Test
    public void test_lock_creates_entry_and_marks_write_held() throws Exception {
        final var rl = new ResourceLocking();
        rl.lock("db", "coll");
        final var identifier = Cache.getCollectionIdentifier("db", "coll");
        final var lock = locks(rl).get(identifier);
        assertNotNull(lock);
        assertTrue(lock.isWriteLockedByCurrentThread());
        rl.release("db", "coll");
        assertFalse(lock.isWriteLockedByCurrentThread());
    }

    @Test
    public void test_index_write_and_read_locks() throws Exception {
        final var rl = new ResourceLocking();
        rl.lockIndex("db", "coll", "field");
        final var identifier = "db" + Globals.COLL_IDENTIFIER_SEPARATOR + "coll" + Globals.COLL_IDENTIFIER_SEPARATOR
                + "field";
        final var lock = locks(rl).get(identifier);
        assertNotNull(lock);
        assertTrue(lock.isWriteLockedByCurrentThread());
        rl.releaseIndex("db", "coll", "field");
        assertFalse(lock.isWriteLockedByCurrentThread());

        rl.lockIndexRead("db", "coll", "field");
        assertEquals(1, lock.getReadHoldCount());
        rl.releaseIndexRead("db", "coll", "field");
        assertEquals(0, lock.getReadHoldCount());
    }

    @Test
    public void test_name_based_read_lock() throws Exception {
        final var rl = new ResourceLocking();
        rl.lockReadByName("some|name");
        final var lock = locks(rl).get("some|name");
        assertNotNull(lock);
        assertEquals(1, lock.getReadHoldCount());
        rl.releaseReadByName("some|name");
        assertEquals(0, lock.getReadHoldCount());
    }

    @Test
    public void test_name_based_write_lock() throws Exception {
        final var rl = new ResourceLocking();
        rl.lockWrite("some|name");
        final var lock = locks(rl).get("some|name");
        assertNotNull(lock);
        assertTrue(lock.isWriteLockedByCurrentThread());
        rl.releaseWrite("some|name");
        assertFalse(lock.isWriteLockedByCurrentThread());
    }

    @Test
    public void test_remove_lock() throws Exception {
        final var rl = new ResourceLocking();
        rl.lock("db", "coll");
        rl.release("db", "coll");
        rl.removeLock("db", "coll");
        final var identifier = Cache.getCollectionIdentifier("db", "coll");
        assertFalse(locks(rl).containsKey(identifier));
    }

    @Test
    public void test_remove_lock_while_held_does_not_strand_a_waiter() throws Exception {
        final var rl = new ResourceLocking();
        rl.lock("db", "held");
        final var acquired = new CountDownLatch(1);
        final var waiter = new Thread(() -> {
            try {
                rl.lock("db", "held");
                acquired.countDown();
                rl.release("db", "held");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(200);
        assertEquals(1, acquired.getCount());
        rl.removeLock("db", "held");
        rl.release("db", "held");
        assertTrue(acquired.await(5, TimeUnit.SECONDS), "the waiter must acquire once the holder releases");
        waiter.join(5000);
    }

    @Test
    public void test_remove_lock_while_held_preserves_mutual_exclusion() throws Exception {
        final var rl = new ResourceLocking();
        rl.lock("db", "excl");
        rl.removeLock("db", "excl");
        final var stolen = new AtomicBoolean(true);
        final var other = new Thread(() -> stolen.set(rl.tryLockWrite("db", "excl")));
        other.start();
        other.join(5000);
        assertFalse(stolen.get(), "another thread must not acquire a collection lock that is still held");
        rl.release("db", "excl");
    }

    @Test
    public void test_remove_lock_is_a_noop_while_a_waiter_is_queued() throws Exception {
        final var rl = new ResourceLocking();
        rl.lock("db", "queued");
        final var waiter = new Thread(() -> {
            try {
                rl.lock("db", "queued");
                rl.release("db", "queued");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(200);
        rl.removeLock("db", "queued");
        assertTrue(locks(rl).containsKey(Cache.getCollectionIdentifier("db", "queued")));
        rl.release("db", "queued");
        waiter.join(5000);
    }

    @Test
    public void test_index_lock_eviction_uses_the_same_atomicity_as_collections() throws Exception {
        final var rl = new ResourceLocking();
        rl.lockIndex("db", "idx", "held");
        rl.lockIndex("db", "idx", "free");
        rl.releaseIndex("db", "idx", "free");

        rl.removeLock("db", "idx");

        final var heldKey = Cache.getCollectionIdentifier("db", "idx") + "|held";
        final var freeKey = Cache.getCollectionIdentifier("db", "idx") + "|free";
        assertTrue(locks(rl).containsKey(heldKey), "an index lock that is still held must not be evicted");
        assertFalse(locks(rl).containsKey(freeKey));
        rl.releaseIndex("db", "idx", "held");
    }

    @Test
    public void test_remove_lock_evicts_when_unheld_and_unqueued() throws Exception {
        final var rl = new ResourceLocking();
        rl.lock("db", "free");
        rl.release("db", "free");
        rl.removeLock("db", "free");
        assertFalse(locks(rl).containsKey(Cache.getCollectionIdentifier("db", "free")));
    }

    // An interrupted read-lock acquisition has to release the locks it already took: the caller only
    // ever releases the returned list, so anything still held would be stranded for good.
    @Test
    public void test_interrupted_read_lock_acquisition_releases_what_it_took() throws Exception {
        final var rl = new ResourceLocking();
        final var first = Cache.getCollectionIdentifier("db", "aColl");
        final var second = Cache.getCollectionIdentifier("db", "bColl");
        final var writerHolds = new CountDownLatch(1);
        final var writerMayRelease = new CountDownLatch(1);
        final var writer = new Thread(() -> {
            try {
                rl.lockWrite(second);
                writerHolds.countDown();
                writerMayRelease.await();
                rl.releaseWrite(second);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        assertTrue(writerHolds.await(5, TimeUnit.SECONDS));
        final var blockedOnSecond = locks(rl).get(second);
        final var reader = Thread.currentThread();
        final var interrupter = new Thread(() -> {
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!blockedOnSecond.hasQueuedThreads() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            reader.interrupt();
        });
        interrupter.start();

        assertThrows(InterruptedException.class, () -> rl.acquireReadLocks(false, List.of(second, first)));
        assertFalse(Thread.interrupted());
        assertEquals(0, locks(rl).get(first).getReadLockCount());

        writerMayRelease.countDown();
        writer.join();
        interrupter.join();
    }

    @Test
    public void test_timed_acquireReadLocks_budgets_total_timeout_across_multiple_locks() throws Exception {
        final var rl = new ResourceLocking();
        final var firstHeld = new CountDownLatch(1);
        final var secondHeld = new CountDownLatch(1);
        final var mayRelease = new CountDownLatch(1);
        final var holder = new Thread(() -> {
            try {
                rl.lockWrite("first");
                firstHeld.countDown();
                rl.lockWrite("second");
                secondHeld.countDown();
                mayRelease.await();
                rl.releaseWrite("second");
                rl.releaseWrite("first");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertTrue(firstHeld.await(5, TimeUnit.SECONDS));
        assertTrue(secondHeld.await(5, TimeUnit.SECONDS));

        final var start = System.nanoTime();
        final var acquired = rl.acquireReadLocks(false, List.of("first", "second"), 200L);
        final var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertNull(acquired, "both locks are held, so the timed acquisition must give up");
        assertTrue(elapsedMillis < 350,
                "the whole acquisition must be bounded by one timeout budget, not one per lock; took " + elapsedMillis
                        + "ms");

        mayRelease.countDown();
        holder.join(5000);
    }

    @Test
    public void test_timed_acquireReadLocks_returns_null_on_timeout_and_releases_partial_acquisitions()
            throws Exception {
        final var rl = new ResourceLocking();
        final var secondHeld = new CountDownLatch(1);
        final var mayRelease = new CountDownLatch(1);
        final var holder = new Thread(() -> {
            try {
                rl.lockWrite("second");
                secondHeld.countDown();
                mayRelease.await();
                rl.releaseWrite("second");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertTrue(secondHeld.await(5, TimeUnit.SECONDS));

        final var acquired = rl.acquireReadLocks(false, List.of("first", "second"), 200L);

        assertNull(acquired, "the second lock is held, so the acquisition must give up");
        assertEquals(0, locks(rl).get("first").getReadLockCount(),
                "the already-acquired first lock must be released when a later one times out");

        mayRelease.countDown();
        holder.join(5000);
    }

    @Test
    public void test_lock_with_empty_database_name() {
        final var rl = new ResourceLocking();
        assertDoesNotThrow(() -> {
            rl.lock("", "coll");
            rl.release("", "coll");
        });
    }

    @Test
    public void test_acquire_write_locks_not_held_skips_a_lock_this_thread_already_holds() throws Exception {
        final var rl = new ResourceLocking();
        final var held = Cache.getCollectionIdentifier("db", "held");
        final var free = Cache.getCollectionIdentifier("db", "free");
        rl.lockWrite(held);

        final var acquired = rl.acquireWriteLocksNotHeld(List.of(held, free), 1000);

        assertEquals(List.of(free), acquired);
        assertEquals(1, locks(rl).get(held).getWriteHoldCount(), "an already-held lock must not be re-entered");
        rl.releaseWriteLocksHeldByCurrentThread(List.of(held, free));
    }

    @Test
    public void test_release_write_locks_held_by_current_thread_unwinds_every_reentrant_hold() throws Exception {
        final var rl = new ResourceLocking();
        final var id = Cache.getCollectionIdentifier("db", "reentrant");
        rl.lockWrite(id);
        rl.lockWrite(id);
        rl.acquireWriteLocksNotHeld(List.of(id));

        rl.releaseWriteLocksHeldByCurrentThread(List.of(id));

        assertFalse(rl.isWriteLockedByCurrentThread(id));
        final var acquiredElsewhere = new AtomicBoolean(false);
        final var other = new Thread(() -> {
            acquiredElsewhere.set(rl.tryLockWrite("db", "reentrant"));
            rl.releaseWrite("db", "reentrant");
        });
        other.start();
        other.join(5000);
        assertTrue(acquiredElsewhere.get(), "another thread must be able to take the lock afterwards");
    }

    @Test
    public void test_try_lock_index_read_fails_while_another_thread_writes() throws Exception {
        final var rl = new ResourceLocking();
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var writer = new Thread(() -> {
            try {
                rl.lockIndex("db", "coll", "field");
                held.countDown();
                release.await();
                rl.releaseIndex("db", "coll", "field");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));

        assertFalse(rl.tryLockIndexRead("db", "coll", "field"), "a reader must not wait behind an index writer");

        release.countDown();
        writer.join(5_000);
        assertTrue(rl.tryLockIndexRead("db", "coll", "field"), "the read lock is free once the writer is done");
        rl.releaseIndexRead("db", "coll", "field");
    }

    @Test
    public void test_try_lock_index_read_is_reentrant_for_the_writer() throws Exception {
        final var rl = new ResourceLocking();
        rl.lockIndex("db", "coll", "field");

        assertTrue(rl.tryLockIndexRead("db", "coll", "field"), "the index writer can still read its own index");

        rl.releaseIndexRead("db", "coll", "field");
        rl.releaseIndex("db", "coll", "field");
    }
}
