package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.IdleLogCheckpoint;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IdleLogCheckpointTest {
    private AtomicLong nextDue;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        nextDue = TestUtils.getPrivateStaticField(IdleLogCheckpoint.class, "nextDue", AtomicLong.class);
        nextDue.set(0L);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walIdleCheckpointMs",
                ConfigKey.WAL_IDLE_CHECKPOINT_MS.defaultValue());
        nextDue.set(0L);
        TestUtils.standardTearDown();
    }

    @Test
    public void test_zero_turns_the_idle_checkpoint_off() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walIdleCheckpointMs", 0L);

        assertEquals(0, IdleLogCheckpoint.runDue(5_000L));
        assertEquals(0L, nextDue.get(), "a disabled pass never books the next one");
    }

    @Test
    public void test_a_pass_runs_at_most_once_per_scan_interval() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walIdleCheckpointMs", 400L);

        assertTrue(IdleLogCheckpoint.runDue(10_000L) >= 0);
        assertEquals(10_400L, nextDue.get(), "an idle threshold under a second is also the scan interval");
        assertEquals(0, IdleLogCheckpoint.runDue(10_399L));
        assertEquals(10_400L, nextDue.get());

        TestUtils.setPrivateField(Configuration.getInstance(), "walIdleCheckpointMs", 60_000L);
        IdleLogCheckpoint.runDue(10_400L);
        assertEquals(11_400L, nextDue.get(), "a long threshold is still scanned every second");
    }

    @Test
    public void test_a_collection_whose_write_lock_is_held_elsewhere_is_skipped_until_it_is_free() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walIdleCheckpointMs", 1L);
        TestUtils.createTestDatabaseAndCollection();
        final var document = new JsonObject();
        document.addProperty(Globals.PK_FIELD, "d1");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document);
        entry.setPage(0L);
        IocContainer.get(FileSystem.class).insertIntoCollection(entry);
        final var log = new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL,
                TestGlobals.COLL + ".wal");
        final var dirtyLength = log.length();
        Thread.sleep(5);

        final var locked = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = Thread.ofVirtual().start(() -> holdWriteLock(locked, release));
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            IdleLogCheckpoint.runDue(nextDue.get());
            assertEquals(dirtyLength, log.length(), "the idle checkpoint ran under another thread's write lock");
        } finally {
            release.countDown();
            holder.join(5_000L);
        }

        IdleLogCheckpoint.runDue(nextDue.get());
        assertNotEquals(dirtyLength, log.length(), "a free collection's idle log is checkpointed");
    }

    private static void holdWriteLock(CountDownLatch locked, CountDownLatch release) {
        final var locks = IocContainer.get(ResourceLocking.class);
        try {
            locks.lock(TestGlobals.DB, TestGlobals.COLL);
            try {
                locked.countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS));
            } finally {
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
