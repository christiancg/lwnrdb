package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class ResourceLockingReadSetTest {
    private static final long TIMEOUT_MS = 60L;

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
    }

    private static Map<String, ReentrantReadWriteLock> locks(ResourceLocking rl)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, ReentrantReadWriteLock>>() {
        };
        return TestUtils.getPrivateField(rl, "locks", type);
    }

    @Test
    public void a_single_identifier_is_locked_and_released_like_any_other_set() throws Exception {
        final var rl = new ResourceLocking();

        final var acquired = rl.acquireReadLocks(false, List.of("only"), TIMEOUT_MS);

        assertEquals(List.of("only"), acquired);
        assertEquals(1, locks(rl).get("only").getReadLockCount());
        rl.releaseReadLocks(acquired);
        assertEquals(0, locks(rl).get("only").getReadLockCount());
    }

    @Test
    public void duplicates_collapse_and_the_set_is_taken_in_sorted_order() throws Exception {
        final var rl = new ResourceLocking();

        final var acquired = rl.acquireReadLocks(false, List.of("b", "a", "b", "a"), TIMEOUT_MS);

        assertEquals(List.of("a", "b"), acquired);
        assertEquals(1, locks(rl).get("a").getReadLockCount());
        assertEquals(1, locks(rl).get("b").getReadLockCount());
        rl.releaseReadLocks(acquired);
    }

    @Test
    public void a_dirty_read_and_an_empty_set_take_nothing() throws Exception {
        final var rl = new ResourceLocking();

        assertEquals(List.of(), rl.acquireReadLocks(true, List.of("a"), TIMEOUT_MS));
        assertEquals(List.of(), rl.acquireReadLocks(false, List.of(), TIMEOUT_MS));
        assertTrue(locks(rl).isEmpty());
    }

    @Test
    public void a_single_identifier_held_for_writing_times_out_and_leaves_nothing_behind() throws Exception {
        final var rl = new ResourceLocking();
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var writer = new Thread(() -> {
            try {
                rl.lock("lockDb", "busy");
                held.countDown();
                release.await();
                rl.release("lockDb", "busy");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.setDaemon(true);
        writer.start();
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            final var identifier = Cache.getCollectionIdentifier("lockDb", "busy");

            final var acquired = rl.acquireReadLocks(false, List.of(identifier), TIMEOUT_MS);

            assertNull(acquired);
            assertEquals(0, locks(rl).get(identifier).getReadLockCount());
        } finally {
            release.countDown();
            writer.join();
        }
    }
}
