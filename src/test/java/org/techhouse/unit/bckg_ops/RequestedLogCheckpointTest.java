package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.events.WalCheckpointEvent;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.OutsideWrites;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class RequestedLogCheckpointTest {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final AtomicInteger requests = new AtomicInteger();
    private File log;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        fs.onCheckpointRequest(() -> {
            requests.incrementAndGet();
            return true;
        });
        log = new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL,
                TestGlobals.COLL + ".wal");
        for (var i = 0; i < 8; i++) {
            insert("seed" + i);
        }
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", OutsideWrites.logEnd(log));
    }

    @AfterEach
    public void tearDown() throws Exception {
        fs.onCheckpointRequest(null);
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes",
                ConfigKey.WAL_CHECKPOINT_BYTES.defaultValue());
        TestUtils.standardTearDown();
    }

    private static void insert(String id) throws Exception {
        final var document = new JsonObject();
        document.addProperty(Globals.PK_FIELD, id);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document);
        entry.setPage(0L);
        fs.insertIntoCollection(entry);
    }

    private static void processRequests() throws Exception {
        EventProcessorHelper.processMaintenance(new WalCheckpointEvent());
    }

    @Test
    public void aRequestedCheckpointRunsBesideAReader() throws Exception {
        insert("full");
        final var fullLength = log.length();
        assertEquals(1, requests.get());

        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            processRequests();
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }

        assertTrue(log.length() < fullLength, "a reader does not hold the background checkpoint off");
    }

    @Test
    public void aRequestedCheckpointWaitsOutAWriter() throws Exception {
        insert("full");
        final var fullLength = log.length();
        final var locked = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = Thread.ofVirtual().start(() -> holdWriteLock(locked, release));
        assertTrue(locked.await(5, TimeUnit.SECONDS));
        final var worker = Thread.ofVirtual().start(() -> {
            try {
                processRequests();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            Thread.sleep(100L);
            assertEquals(fullLength, log.length(), "the checkpoint ran while a writer held the collection");
        } finally {
            release.countDown();
            holder.join(5_000L);
        }
        worker.join(5_000L);

        assertNotEquals(fullLength, log.length(), "the checkpoint runs once the writer lets go");
    }

    private static void holdWriteLock(CountDownLatch locked, CountDownLatch release) {
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
