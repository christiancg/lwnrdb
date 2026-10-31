package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ReadTrackingLock;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class ResourceLockingConvoyTest {
    private static final String DB = "convoy_db";
    private static final String COLL = "convoy_coll";
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

    private static final class Holder implements AutoCloseable {
        private final CountDownLatch holds = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CompletableFuture<Boolean> done;

        private Holder(Locked acquire, Runnable unlock) throws InterruptedException {
            done = inOtherThread(() -> {
                final var acquired = acquire.acquire();
                holds.countDown();
                release.await();
                return acquired;
            }, unlock);
            assertTrue(holds.await(WAIT_MS, TimeUnit.MILLISECONDS), "the holder never acquired");
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            assertTrue(done.get(WAIT_MS, TimeUnit.MILLISECONDS));
        }
    }

    private Holder longReader() throws InterruptedException {
        return new Holder(() -> rl.acquireReadLocks(false, List.of(ID), 0L) != null,
                () -> rl.releaseReadLocks(List.of(ID)));
    }

    private Holder pointReader() throws InterruptedException {
        return new Holder(() -> rl.acquirePointReadLock(false, ID, 0L) != null,
                () -> rl.releasePointReadLocks(List.of(ID)));
    }

    private CompletableFuture<Boolean> pointRead() {
        return inOtherThread(() -> rl.acquirePointReadLock(false, ID, 0L) != null,
                () -> rl.releasePointReadLocks(List.of(ID)));
    }

    private CompletableFuture<Boolean> queuedWriter(CountDownLatch release) throws Exception {
        final var writer = inOtherThread(() -> {
            rl.lock(DB, COLL);
            release.await();
            return true;
        }, () -> rl.release(DB, COLL));
        awaitQueuedWriter();
        return writer;
    }

    private ReadTrackingLock collectionLock() throws Exception {
        final var type = new ReflectionUtils.TypeToken<Map<String, ReadTrackingLock>>() {
        };
        return TestUtils.getPrivateField(rl, "locks", type).get(ID);
    }

    private void awaitQueuedWriter() throws Exception {
        final var deadline = System.currentTimeMillis() + WAIT_MS;
        while (collectionLock() == null || !collectionLock().hasQueuedThreads()) {
            assertTrue(System.currentTimeMillis() < deadline, "the writer never queued");
            LockSupport.parkNanos(1_000_000L);
        }
    }

    private static void assertStillBlocked(CompletableFuture<Boolean> pending) {
        try {
            pending.get(BLOCKED_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expected) {
            return;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("the acquisition did not wait");
    }

    @Test
    public void test_a_point_read_passes_a_writer_queued_behind_a_long_reader() throws Exception {
        final var writerRelease = new CountDownLatch(1);
        final CompletableFuture<Boolean> writer;
        try (var ignored = longReader()) {
            assertEquals(1, rl.longReaders(ID));
            writer = queuedWriter(writerRelease);

            assertTrue(pointRead().get(WAIT_MS, TimeUnit.MILLISECONDS), "the point read waited for the long scan");
            assertFalse(writer.isDone());
        }
        writerRelease.countDown();
        assertTrue(writer.get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals(0, rl.longReaders(ID));
    }

    @Test
    public void test_without_a_long_reader_a_point_read_queues_behind_the_writer() throws Exception {
        final var writerRelease = new CountDownLatch(1);
        final CompletableFuture<Boolean> writer;
        final CompletableFuture<Boolean> late;
        try (var ignored = pointReader()) {
            assertEquals(0, rl.longReaders(ID));
            writer = queuedWriter(writerRelease);
            late = pointRead();
            assertStillBlocked(late);
        }
        assertStillBlocked(late);
        assertFalse(writer.isDone());
        writerRelease.countDown();
        assertTrue(writer.get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue(late.get(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void test_a_writer_is_not_starved_by_point_reads_once_the_long_reader_leaves() throws Exception {
        final var stop = new AtomicBoolean();
        final var served = new AtomicInteger();
        final var readers = new ArrayList<Thread>();
        final var writerRelease = new CountDownLatch(1);
        final CompletableFuture<Boolean> writer;
        try (var ignored = longReader()) {
            writer = queuedWriter(writerRelease);
            for (var reader = 0; reader < 4; reader++) {
                readers.add(Thread.ofPlatform().start(() -> pointReadsUntil(stop, served)));
            }
            final var deadline = System.currentTimeMillis() + WAIT_MS;
            while (served.get() < 20) {
                assertTrue(System.currentTimeMillis() < deadline, "point reads never passed the queued writer");
                LockSupport.parkNanos(1_000_000L);
            }
            assertFalse(writer.isDone());
        }
        try {
            writerRelease.countDown();
            assertTrue(writer.get(WAIT_MS, TimeUnit.MILLISECONDS), "overlapping point reads starved the writer");
        } finally {
            stop.set(true);
            for (final var reader : readers) {
                reader.join(WAIT_MS);
            }
        }
    }

    private void pointReadsUntil(AtomicBoolean stop, AtomicInteger served) {
        while (!stop.get()) {
            try {
                final var held = rl.acquirePointReadLock(false, ID, 0L);
                LockSupport.parkNanos(3_000_000L);
                rl.releasePointReadLocks(held);
                served.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    public void test_long_readers_return_to_zero_on_every_release_path() throws Exception {
        assertNotNull(rl.acquireReadLocks(false, List.of(ID), 0L));
        rl.lockRead(DB, COLL);
        assertTrue(rl.tryLockRead(DB, COLL, 0L));
        rl.lockReadByName(ID);
        assertEquals(4, rl.longReaders(ID));
        rl.releaseReadLocks(List.of(ID));
        rl.releaseRead(DB, COLL);
        rl.releaseReadByName(ID);
        rl.releaseRead(DB, COLL);
        assertEquals(0, rl.longReaders(ID));
        rl.releaseRead(DB, COLL);
        assertEquals(0, rl.longReaders(ID), "a release without a hold never goes below zero");

        final var held = rl.acquirePointReadLock(false, ID, 0L);
        assertEquals(0, rl.longReaders(ID), "a point read is not a long reader");
        rl.releasePointReadLocks(held);

        assertTrue(rl.tryLockIndexRead(DB, COLL, "field"));
        final var indexId = ID + "|field";
        assertEquals(1, rl.longReaders(indexId));
        rl.releaseIndexRead(DB, COLL, "field");
        assertEquals(0, rl.longReaders(indexId));

        rl.removeLock(DB, COLL);
        assertEquals(0, rl.longReaders(ID));
    }

    @Test
    public void test_a_read_that_retries_on_the_gate_counts_once() throws Exception {
        rl.lock(DB, COLL);
        final CompletableFuture<Boolean> scan;
        final var scanHolds = new CountDownLatch(1);
        final var scanRelease = new CountDownLatch(1);
        try (var ignored = rl.openSettleWindow(DB, COLL)) {
            assertFalse(inOtherThread(() -> rl.tryLockRead(DB, COLL, 100L), () -> rl.releaseRead(DB, COLL)).get(WAIT_MS,
                    TimeUnit.MILLISECONDS), "a bounded read times out on the gate");
            assertEquals(0, rl.longReaders(ID));
            scan = inOtherThread(() -> {
                final var acquired = rl.acquireReadLocks(false, List.of(ID), 0L) != null;
                scanHolds.countDown();
                scanRelease.await();
                return acquired;
            }, () -> rl.releaseReadLocks(List.of(ID)));
            assertStillBlocked(scan);
            assertEquals(0, rl.longReaders(ID));
        }
        assertTrue(scanHolds.await(WAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals(1, rl.longReaders(ID));
        scanRelease.countDown();
        assertTrue(scan.get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals(0, rl.longReaders(ID));
    }

    @Test
    public void test_a_point_read_never_barges_while_the_gate_is_failing() throws Exception {
        final var opened = new CountDownLatch(1);
        final var failNow = new CountDownLatch(1);
        final var failed = new CountDownLatch(1);
        final var closeNow = new CountDownLatch(1);
        final var owner = Thread.ofPlatform().start(() -> {
            try {
                rl.lock(DB, COLL);
                try (var window = rl.openSettleWindow(DB, COLL)) {
                    opened.countDown();
                    failNow.await();
                    window.fail();
                    failed.countDown();
                    closeNow.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                rl.release(DB, COLL);
            }
        });
        assertTrue(opened.await(WAIT_MS, TimeUnit.MILLISECONDS));
        try (var ignored = pointReader()) {
            TestUtils.getPrivateField(collectionLock(), "longReaders", AtomicInteger.class).incrementAndGet();
            failNow.countDown();
            assertFalse(failed.await(BLOCKED_MS, TimeUnit.MILLISECONDS), "fail waits for the point reader inside");
            assertFalse(
                    inOtherThread(() -> rl.acquirePointReadLock(false, ID, 150L) != null,
                            () -> rl.releasePointReadLocks(List.of(ID))).get(WAIT_MS, TimeUnit.MILLISECONDS),
                    "a failing gate holds a point read even when a long reader is counted");
        }
        assertTrue(failed.await(WAIT_MS, TimeUnit.MILLISECONDS));
        closeNow.countDown();
        owner.join(WAIT_MS);
        assertFalse(owner.isAlive());
    }

    @Test
    public void test_removing_a_collection_lock_leaves_its_index_locks() throws Exception {
        rl.lockRead(DB, COLL);
        rl.releaseRead(DB, COLL);
        assertTrue(rl.tryLockIndexRead(DB, COLL, "field"));
        rl.releaseIndexRead(DB, COLL, "field");
        final var type = new ReflectionUtils.TypeToken<Map<String, ReadTrackingLock>>() {
        };
        final var registry = TestUtils.getPrivateField(rl, "locks", type);

        rl.removeCollectionLock(DB, COLL);

        assertFalse(registry.containsKey(ID));
        assertTrue(registry.containsKey(ID + "|field"));
    }
}
