package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class DatabaseBarrierLockingTest {
    private static final String DB = "barrierdb";

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

    private static boolean onAnotherThread(ResourceLockingAttempt attempt) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return attempt.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }).get(5, TimeUnit.SECONDS);
    }

    private interface ResourceLockingAttempt {
        boolean run() throws InterruptedException;
    }

    @Test
    public void test_shared_holders_do_not_block_each_other() throws Exception {
        final var rl = new ResourceLocking();
        assertTrue(rl.tryLockDatabaseShared(DB, 100));

        assertTrue(onAnotherThread(() -> {
            final var acquired = rl.tryLockDatabaseShared(DB, 100);
            rl.releaseDatabaseShared(DB);
            return acquired;
        }));
        rl.releaseDatabaseShared(DB);
    }

    @Test
    public void test_exclusive_times_out_while_a_shared_holder_exists() throws Exception {
        final var rl = new ResourceLocking();
        assertTrue(rl.tryLockDatabaseShared(DB, 100));

        assertFalse(onAnotherThread(() -> rl.tryLockDatabaseExclusive(DB, 100)));
        rl.releaseDatabaseShared(DB);
        assertTrue(onAnotherThread(() -> {
            final var acquired = rl.tryLockDatabaseExclusive(DB, 100);
            rl.releaseDatabaseExclusive(DB);
            return acquired;
        }));
    }

    @Test
    public void test_shared_times_out_while_the_database_is_held_exclusively() throws Exception {
        final var rl = new ResourceLocking();
        assertTrue(rl.tryLockDatabaseExclusive(DB, 100));

        assertFalse(onAnotherThread(() -> rl.tryLockDatabaseShared(DB, 100)));
        rl.releaseDatabaseExclusive(DB);
    }

    @Test
    public void test_the_database_lock_never_shares_a_key_with_a_collection_lock() throws Exception {
        final var rl = new ResourceLocking();
        assertTrue(rl.tryLockDatabaseExclusive(DB, 100));

        assertTrue(onAnotherThread(() -> {
            final var acquired = rl.tryLockWrite(DB, "coll", 100);
            rl.release(DB, "coll");
            return acquired;
        }));
        rl.releaseDatabaseExclusive(DB);
    }

    @Test
    public void test_remove_database_lock_evicts_only_when_idle() throws Exception {
        final var rl = new ResourceLocking();
        assertTrue(rl.tryLockDatabaseShared(DB, 100));

        rl.removeDatabaseLock(DB);
        assertNotNull(locks(rl).get(DB), "a held barrier must survive eviction");

        rl.releaseDatabaseShared(DB);
        rl.removeDatabaseLock(DB);
        assertNull(locks(rl).get(DB));
    }
}
