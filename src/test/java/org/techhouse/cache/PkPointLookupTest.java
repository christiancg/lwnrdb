package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PkPointLookupTest {
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
            .getThreadMXBean();
    private static final String DB = TestGlobals.DB;
    private static final String COLL = TestGlobals.COLL;

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final Configuration configuration = Configuration.getInstance();
    private Long savedMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void insert(int count) throws Exception {
        final var entries = new ArrayList<DbEntry>(count);
        for (var i = 0; i < count; i++) {
            final var data = new JsonObject();
            data.addProperty("_id", id(i));
            data.addProperty("n", i);
            entries.add(DbEntry.fromJsonObject(DB, COLL, data));
        }
        fs.bulkInsertIntoCollection(DB, COLL, entries);
    }

    private static String id(int i) {
        return "doc-" + String.format("%06d", i);
    }

    private void admitPkIndex() throws Exception {
        locks.lock(DB, COLL);
        try {
            assertNotNull(userCache.getPkIndexAndLoadIfNecessary(DB, COLL));
        } finally {
            locks.release(DB, COLL);
        }
        assertTrue(userCache.pkIndexes().containsKey(Cache.getCollectionIdentifier(DB, COLL)));
    }

    private Thread holdWriteLock(CountDownLatch held, long millis, Runnable whileHeld) {
        return Thread.ofPlatform().start(() -> {
            try {
                locks.lock(DB, COLL);
                try {
                    whileHeld.run();
                    held.countDown();
                    Thread.sleep(millis);
                } finally {
                    locks.release(DB, COLL);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void addRowInMemoryOnly() {
        try {
            userCache.getPkIndexAndLoadIfNecessary(DB, COLL).add(new PkIndexEntry(DB, COLL, "zz-late", 0L, 1L, 0L));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void aResidentLockFreeLookupAllocatesLittle() throws Exception {
        insert(1000);
        admitPkIndex();
        for (var warm = 0; warm < 2000; warm++) {
            assertNotNull(userCache.pkRowFor(DB, COLL, id(warm % 1000)));
        }
        final var thread = Thread.currentThread().threadId();
        final var before = THREADS.getThreadAllocatedBytes(thread);
        for (var call = 0; call < 100; call++) {
            userCache.pkRowFor(DB, COLL, id(call));
        }
        final var perCall = (THREADS.getThreadAllocatedBytes(thread) - before) / 100;

        assertTrue(perCall < 2048, "a resident lock-free point lookup allocated " + perCall + " bytes");
        assertEquals(id(7), userCache.pkRowFor(DB, COLL, id(7)).getValue());
    }

    @Test
    public void aBriefWriterIsWaitedForAndTheResidentListAnswers() throws Exception {
        insert(50);
        admitPkIndex();
        final var held = new CountDownLatch(1);
        final var writer = holdWriteLock(held, 20L, this::addRowInMemoryOnly);
        assertTrue(held.await(5, TimeUnit.SECONDS));

        final var found = userCache.pkRowFor(DB, COLL, "zz-late");
        writer.join(5_000L);

        assertNotNull(found, "the reader waited out the writer and read the list it had just changed");
    }

    @Test
    public void aLongHoldFallsBackToTheDiskScan() throws Exception {
        insert(50);
        admitPkIndex();
        final var held = new CountDownLatch(1);
        final var writer = holdWriteLock(held, 2_000L, this::addRowInMemoryOnly);
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            final var startedAt = System.nanoTime();
            final var late = userCache.pkRowFor(DB, COLL, "zz-late");
            final var existing = userCache.pkRowFor(DB, COLL, id(3));
            final var waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertNull(late, "the disk does not hold a row the writer has not committed");
            assertEquals(id(3), existing.getValue());
            assertTrue(waitedMillis < 1_500L, "the wait is budgeted, waited " + waitedMillis + " ms");
        } finally {
            writer.interrupt();
            writer.join(5_000L);
        }
    }

    @Test
    public void aNonResidentLockFreeLookupNeverAdmitsAndAllocatesLittle() throws Exception {
        insert(100_000);
        assertNotNull(userCache.pkRowFor(DB, COLL, id(1)));
        final var thread = Thread.currentThread().threadId();
        final var before = THREADS.getThreadAllocatedBytes(thread);

        final var found = userCache.pkRowFor(DB, COLL, id(99_999));
        final var allocated = THREADS.getThreadAllocatedBytes(thread) - before;

        assertEquals(id(99_999), found.getValue());
        assertNull(userCache.pkRowFor(DB, COLL, "absent"));
        assertFalse(userCache.pkIndexes().containsKey(Cache.getCollectionIdentifier(DB, COLL)));
        assertTrue(allocated < 2L << 20, "a point scan of 100k rows allocated " + allocated + " bytes");
        assertEquals(100_000, userCache.pkCountForCoverage(DB, COLL));
    }

    @Test
    public void aLockHolderWithARefusalMemoPointScans() throws Exception {
        insert(1000);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 1024L);
        final var cache = new UserCache();
        final var spied = spy(fs);
        TestUtils.setPrivateField(cache, "fs", spied);
        locks.lock(DB, COLL);
        try {
            assertEquals(id(5), cache.pkRowFor(DB, COLL, id(5)).getValue());
            assertNotNull(cache.sizes().refusedPk(Cache.getCollectionIdentifier(DB, COLL)));
            assertEquals(id(6), cache.pkRowFor(DB, COLL, id(6)).getValue());
            assertNull(cache.pkRowFor(DB, COLL, "absent"));
            assertEquals(1000, cache.pkCountForCoverage(DB, COLL));
            assertEquals(1000, cache.pkIndexSize(DB, COLL));
        } finally {
            locks.release(DB, COLL);
        }

        verify(spied, never()).readWholePkIndexFile(DB, COLL);
        verify(spied, atLeastOnce()).pkRowsFor(org.mockito.ArgumentMatchers.eq(DB),
                org.mockito.ArgumentMatchers.eq(COLL), anyCollection());
    }

    @Test
    public void anUnrecognisedPkIndexIsAnErrorNotAMissingEntry() throws Exception {
        insert(10);
        Files.writeString(new java.io.File(TestGlobals.PATH + "/" + DB + "/" + COLL + "/" + COLL + "-pk.idx").toPath(),
                "garbage\nmore garbage\n", StandardCharsets.UTF_8);
        final var processor = IocContainer.get(OperationProcessor.class);
        final AtomicReference<String> codes = new AtomicReference<>("");
        for (final var dirty : new boolean[]{true, false}) {
            final var request = new FindByIdRequest(DB, COLL);
            request.set_id(id(1));
            request.setDirtyRead(dirty);
            codes.set(codes.get() + processor.processMessage(request).getErrorCode() + ";");
        }

        assertEquals("500-8;500-8;", codes.get());
    }
}
