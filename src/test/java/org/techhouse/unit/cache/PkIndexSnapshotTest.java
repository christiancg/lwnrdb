package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PkIndexSnapshotTest {
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void publishCachedIndex() throws Exception {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL)
                    .add(new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "x1", 0, 1, 0));
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    @Test
    public void test_a_lock_holder_still_gets_the_shared_list_to_mutate() throws Exception {
        publishCachedIndex();

        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var first = userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
            final var second = userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);

            assertSame(first, second,
                    "writers mutate the cached list in place, so a lock holder must keep getting that instance");
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    @Test
    public void test_a_lock_free_reader_gets_its_own_snapshot() throws Exception {
        publishCachedIndex();

        final var shared = sharedListUnderLock();
        final var snapshot = userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);

        assertNotSame(shared, snapshot,
                "a lock-free reader binary-searched the list writers reshape in place, so it saw wrong rows"
                        + " or threw while a bulk save was mid-flight");
        assertEquals(shared.size(), snapshot.size(), "the snapshot must still answer with the same entries");
    }

    @Test
    public void test_a_snapshot_is_unaffected_by_a_later_structural_change() throws Exception {
        publishCachedIndex();
        final var snapshot = userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        final var sizeWhenTaken = snapshot.size();

        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL)
                    .add(new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "x2", 0, 1, 0));
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        assertEquals(sizeWhenTaken, snapshot.size(), "the reader's view must not change underneath it");
    }

    @Test
    public void test_a_lock_free_read_does_not_block_on_a_writer() throws Exception {
        publishCachedIndex();
        final var writerHolds = new CountDownLatch(1);
        final var releaseWriter = new CountDownLatch(1);
        final var writer = new Thread(() -> {
            try {
                locks.lock(TestGlobals.DB, TestGlobals.COLL);
                writerHolds.countDown();
                releaseWriter.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            }
        });
        writer.start();
        assertTrue(writerHolds.await(5, TimeUnit.SECONDS), "the writer must hold the lock before the test proceeds");

        try {
            final var startedAt = System.currentTimeMillis();
            final var snapshot = userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);

            assertTrue(System.currentTimeMillis() - startedAt < 2000,
                    "a dirty read must never wait on the collection write lock; it falls back to the disk tier,"
                            + " which the per-file locks already make safe");
            org.junit.jupiter.api.Assertions.assertNotNull(snapshot);
        } finally {
            releaseWriter.countDown();
            writer.join(5000);
        }
    }

    private java.util.List<PkIndexEntry> sharedListUnderLock() throws Exception {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            return userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }
}
