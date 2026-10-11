package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ex.CollectionBusyException;

public class BoundedWriteLocksTest {

    private static final long SHORT_BUDGET_MS = 120;

    private final ResourceLocking locks = new ResourceLocking();

    @AfterEach
    void releaseEverything() {
        for (final var name : List.of("db|one", "db|two", "db|three")) {
            locks.releaseWrite(name);
        }
    }

    @Test
    public void test_bounded_write_locks_run_the_action_when_every_lock_is_free() throws Exception {
        final var result = locks.withWriteLocks(List.of("db|one", "db|two"), SHORT_BUDGET_MS, () -> "ran");

        assertEquals("ran", result, "with every lock free the action must run normally");
    }

    @Test
    public void test_bounded_write_locks_release_everything_they_took() throws Exception {
        locks.withWriteLocks(List.of("db|one", "db|two"), SHORT_BUDGET_MS, () -> "ran");

        assertTrue(locks.tryLockWrite("db", "one"), "db|one must be free again after the action returns");
        locks.releaseWrite("db|one");
        assertTrue(locks.tryLockWrite("db", "two"), "db|two must be free again after the action returns");
        locks.releaseWrite("db|two");
    }

    @Test
    public void test_bounded_write_locks_give_up_instead_of_parking_forever() throws Exception {
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = new Thread(() -> {
            try {
                locks.lockWrite("db|two");
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.releaseWrite("db|two");
            }
        });
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS), "the holder thread must take the lock before the test proceeds");

        try {
            final var startedAt = System.currentTimeMillis();
            assertThrows(CollectionBusyException.class,
                    () -> locks.withWriteLocks(List.of("db|one", "db|two"), SHORT_BUDGET_MS, () -> "ran"),
                    "a lock held by another thread must be skipped, not waited on forever");
            assertTrue(System.currentTimeMillis() - startedAt < 5000, "it must give up on its own budget");
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    @Test
    public void test_a_skipped_acquisition_releases_the_locks_it_already_took() throws Exception {
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = new Thread(() -> {
            try {
                locks.lockWrite("db|two");
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.releaseWrite("db|two");
            }
        });
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS), "the holder thread must take the lock before the test proceeds");

        try {
            assertThrows(CollectionBusyException.class,
                    () -> locks.withWriteLocks(List.of("db|one", "db|two"), SHORT_BUDGET_MS, () -> "ran"));
            assertTrue(locks.tryLockWrite("db", "one"),
                    "db|one was acquired before the skip and must not be stranded by it");
            locks.releaseWrite("db|one");
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    @Test
    public void test_the_action_does_not_run_when_a_lock_is_unavailable() throws Exception {
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var ran = new java.util.concurrent.atomic.AtomicBoolean(false);
        final var holder = new Thread(() -> {
            try {
                locks.lockWrite("db|two");
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.releaseWrite("db|two");
            }
        });
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS), "the holder thread must take the lock before the test proceeds");

        try {
            assertThrows(CollectionBusyException.class,
                    () -> locks.withWriteLocks(List.of("db|two"), SHORT_BUDGET_MS, () -> {
                        ran.set(true);
                        return "ran";
                    }));
            assertFalse(ran.get(), "a half-applied write must never run without every lock it asked for");
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }
}
