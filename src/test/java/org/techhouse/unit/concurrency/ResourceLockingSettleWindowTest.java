package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.concurrency.SettleWindow;
import org.techhouse.test.TestUtils;

public class ResourceLockingSettleWindowTest {
    private static final String DB = "settle_db";
    private static final String COLL = "settle_coll";
    private static final String ID = Cache.getCollectionIdentifier(DB, COLL);
    private static final long WAIT_MS = 5_000L;
    private static final long BLOCKED_MS = 200L;
    private final ResourceLocking rl = new ResourceLocking();

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
    }

    private interface Locked {
        boolean acquire() throws Exception;
    }

    private static CompletableFuture<Boolean> inOtherThread(Locked locked, Runnable release) {
        final var result = new CompletableFuture<Boolean>();
        Thread.ofPlatform().start(() -> {
            try {
                final var acquired = locked.acquire();
                if (acquired) {
                    release.run();
                }
                result.complete(acquired);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private CompletableFuture<Boolean> pointRead(long timeoutMillis) {
        return inOtherThread(() -> rl.acquirePointReadLock(false, ID, timeoutMillis) != null,
                () -> rl.releaseRead(DB, COLL));
    }

    private CompletableFuture<Boolean> scanRead(long timeoutMillis) {
        return inOtherThread(() -> rl.acquireReadLocks(false, List.of(ID), timeoutMillis) != null,
                () -> rl.releaseRead(DB, COLL));
    }

    private SettleWindow openWindow() throws InterruptedException {
        rl.lock(DB, COLL);
        return rl.openSettleWindow(DB, COLL);
    }

    private static void assertStillBlocked(CompletableFuture<Boolean> pending) {
        try {
            pending.get(BLOCKED_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expected) {
            return;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("the acquisition went through an open window");
    }

    @Test
    public void test_a_point_read_proceeds_while_a_window_is_open() throws Exception {
        try (var window = openWindow()) {
            assertTrue(window.shared());
            assertTrue(pointRead(0L).get(WAIT_MS, TimeUnit.MILLISECONDS));
            assertEquals(List.of(), rl.acquirePointReadLock(true, ID, 0L), "a dirty read takes no lock");
        }
    }

    @Test
    public void test_a_scan_read_waits_until_the_window_closes() throws Exception {
        final CompletableFuture<Boolean> scan;
        try (var ignored = openWindow()) {
            scan = scanRead(0L);
            assertStillBlocked(scan);
        }
        assertTrue(scan.get(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void test_a_scan_queued_before_the_window_backs_off_onto_the_gate() throws Exception {
        rl.lock(DB, COLL);
        final var acquired = new AtomicBoolean();
        final var scan = inOtherThread(() -> {
            rl.lockRead(DB, COLL);
            acquired.set(true);
            return true;
        }, () -> rl.releaseRead(DB, COLL));
        Thread.sleep(BLOCKED_MS);
        try (var ignored = rl.openSettleWindow(DB, COLL)) {
            assertStillBlocked(scan);
            assertFalse(acquired.get());
        }
        assertTrue(scan.get(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void test_a_bounded_scan_read_times_out_on_an_open_window() throws Exception {
        try (var ignored = openWindow()) {
            assertFalse(scanRead(150L).get(WAIT_MS, TimeUnit.MILLISECONDS));
            assertFalse(inOtherThread(() -> rl.tryLockRead(DB, COLL, 150L), () -> rl.releaseRead(DB, COLL)).get(WAIT_MS,
                    TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void test_try_lock_write_fails_inside_a_window() throws Exception {
        try (var ignored = openWindow()) {
            assertFalse(inOtherThread(() -> rl.tryLockWrite(DB, COLL), () -> rl.release(DB, COLL)).get(WAIT_MS,
                    TimeUnit.MILLISECONDS));
            assertFalse(inOtherThread(() -> rl.tryLockWrite(DB, COLL, 150L), () -> rl.release(DB, COLL)).get(WAIT_MS,
                    TimeUnit.MILLISECONDS));
        }
        assertTrue(inOtherThread(() -> rl.tryLockWrite(DB, COLL), () -> rl.release(DB, COLL)).get(WAIT_MS,
                TimeUnit.MILLISECONDS));
    }

    @Test
    public void test_a_failing_window_holds_point_reads_and_hands_the_write_lock_to_its_owner() throws Exception {
        final var opened = new CountDownLatch(1);
        final var failNow = new CountDownLatch(1);
        final var failed = new CountDownLatch(1);
        final var closeNow = new CountDownLatch(1);
        final var ownerHeldWrite = new AtomicBoolean();
        final var owner = Thread.ofPlatform().start(() -> {
            try (var window = openWindow()) {
                opened.countDown();
                failNow.await();
                window.fail();
                window.fail();
                ownerHeldWrite.set(rl.isWriteLockedByCurrentThread(ID) && !window.shared());
                failed.countDown();
                closeNow.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(opened.await(WAIT_MS, TimeUnit.MILLISECONDS));
        final var readerHolds = new CountDownLatch(1);
        final var readerRelease = new CountDownLatch(1);
        final var reader = inOtherThread(() -> {
            final var acquired = rl.acquirePointReadLock(false, ID, 0L) != null;
            readerHolds.countDown();
            readerRelease.await();
            return acquired;
        }, () -> rl.releaseRead(DB, COLL));
        assertTrue(readerHolds.await(WAIT_MS, TimeUnit.MILLISECONDS));

        failNow.countDown();
        assertFalse(failed.await(BLOCKED_MS, TimeUnit.MILLISECONDS), "fail waits for the point reader inside");
        assertFalse(pointRead(150L).get(WAIT_MS, TimeUnit.MILLISECONDS), "a failing window holds new point reads");
        readerRelease.countDown();
        assertTrue(reader.get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue(failed.await(WAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue(ownerHeldWrite.get());

        closeNow.countDown();
        owner.join(WAIT_MS);
        assertTrue(pointRead(0L).get(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void test_a_reentrant_write_hold_stays_exclusive() throws Exception {
        rl.lock(DB, COLL);
        rl.lock(DB, COLL);
        try (var window = rl.openSettleWindow(DB, COLL)) {
            assertFalse(window.shared());
            window.fail();
            assertFalse(pointRead(150L).get(WAIT_MS, TimeUnit.MILLISECONDS));
        }
        try (var exclusive = SettleWindow.exclusive()) {
            assertFalse(exclusive.shared());
        }
        assertTrue(rl.isWriteLockedByCurrentThread(ID));
        rl.release(DB, COLL);
        rl.release(DB, COLL);
        assertFalse(rl.isWriteLockedByCurrentThread(ID));
        try (var unlocked = rl.openSettleWindow(DB, COLL)) {
            assertFalse(unlocked.shared(), "no window without the write lock");
        }
    }

    @Test
    public void test_window_calls_from_another_thread_release_nothing() throws Exception {
        try (var window = openWindow()) {
            inOtherThread(() -> {
                window.fail();
                window.close();
                return true;
            }, () -> {
            }).get(WAIT_MS, TimeUnit.MILLISECONDS);
            assertTrue(window.shared());
            assertStillBlocked(scanRead(0L));
            assertTrue(pointRead(0L).get(WAIT_MS, TimeUnit.MILLISECONDS));
        }
        assertTrue(scanRead(0L).get(WAIT_MS, TimeUnit.MILLISECONDS));
    }
}
