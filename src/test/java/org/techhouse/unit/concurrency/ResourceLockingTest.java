package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
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

    // A write lock is held exclusively: a reader on another thread blocks until the writer releases.
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

    // Multiple readers share the lock: a second reader is not blocked by the first.
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

    // A read lock held by another thread blocks a writer (tryLockWrite fails), then succeeds once free.
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
    public void test_lock_with_empty_database_name() {
        final var rl = new ResourceLocking();
        assertDoesNotThrow(() -> {
            rl.lock("", "coll");
            rl.release("", "coll");
        });
    }
}
