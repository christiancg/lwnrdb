package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.cache.UserCache;
import org.techhouse.cache.WholeLoads;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.IndexLogChange;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class UserCacheWholeLoadTest {
    private final Configuration config = Configuration.getInstance();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private Long savedMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        savedMaxMemory = TestUtils.getPrivateField(config, "maxMemoryBytes", Long.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static List<PkIndexEntry> threeRows() {
        final var rows = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 3; i++) {
            rows.add(new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "row-" + i, i * 10L, 10, 0));
        }
        return rows;
    }

    @Test
    public void oversizedPkIndexIsNotReadForAdmission() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", 64L * 1024);
        final var cache = new UserCache();
        final var wholeLoads = TestUtils.getPrivateField(cache, "wholeLoads", WholeLoads.class);
        final var permitsDuringRead = new AtomicInteger(-1);
        final var fsMock = mock(FileSystem.class);
        when(fsMock.pkIndexFileBytes(anyString(), anyString())).thenReturn(1L << 30);
        when(fsMock.readWholePkIndexFile(anyString(), anyString())).thenAnswer(_ -> {
            permitsDuringRead.set(wholeLoads.availablePermits());
            return threeRows();
        });
        TestUtils.setPrivateField(cache, "fs", fsMock);
        final var permits = wholeLoads.availablePermits();

        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            assertEquals(3, cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).size());
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }

        assertEquals(permits - 1, permitsDuringRead.get(), "a load the estimate cannot fit runs under a permit");
        assertEquals(permits, wholeLoads.availablePermits());
    }

    @Test
    public void aPkIndexThatFitsNeedsNoPermitAndIsAdmitted() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", 64L << 20);
        final var cache = new UserCache();
        final var wholeLoads = TestUtils.getPrivateField(cache, "wholeLoads", WholeLoads.class);
        final var permitsDuringRead = new AtomicInteger(-1);
        final var fsMock = mock(FileSystem.class);
        when(fsMock.pkIndexFileBytes(anyString(), anyString())).thenReturn(1024L);
        when(fsMock.readWholePkIndexFile(anyString(), anyString())).thenAnswer(_ -> {
            permitsDuringRead.set(wholeLoads.availablePermits());
            return threeRows();
        });
        TestUtils.setPrivateField(cache, "fs", fsMock);

        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
            assertFalse(cache.listCacheableResources().isEmpty(), "the load was admitted");
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }

        assertEquals(wholeLoads.availablePermits(), permitsDuringRead.get());
    }

    @Test
    public void fieldAndHashIndexLoadsTheEstimateCannotFitRunUnderAPermit() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", 64L * 1024);
        final var cache = new UserCache();
        final var wholeLoads = TestUtils.getPrivateField(cache, "wholeLoads", WholeLoads.class);
        final var permits = wholeLoads.availablePermits();
        final var permitsDuringRead = new ArrayList<Integer>();
        final var fsMock = mock(FileSystem.class);
        when(fsMock.indexFileBytes(anyString(), anyString(), anyString(), anyString())).thenReturn(1L << 30);
        when(fsMock.readWholeFieldIndexFiles(anyString(), anyString(), anyString(), any())).thenAnswer(_ -> {
            permitsDuringRead.add(wholeLoads.availablePermits());
            return List.of();
        });
        when(fsMock.readWholeHashIndexFile(anyString(), anyString(), anyString(), any())).thenAnswer(_ -> {
            permitsDuringRead.add(wholeLoads.availablePermits());
            return List.of();
        });
        TestUtils.setPrivateField(cache, "fs", fsMock);

        cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "f", String.class);
        cache.getHashIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "f", IndexKind.OBJECT);

        assertEquals(List.of(permits - 1, permits - 1), permitsDuringRead);
    }

    @Test
    public void transientLoadsAreLimitedToPermits() throws Exception {
        final var concurrent = new AtomicInteger();
        final var highest = new AtomicInteger();
        final var proceed = new CountDownLatch(1);
        final var fsMock = mock(FileSystem.class);
        when(fsMock.readWholePkIndexFile(anyString(), anyString())).thenAnswer(_ -> {
            highest.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            final var released = proceed.await(10, TimeUnit.SECONDS);
            concurrent.decrementAndGet();
            assertTrue(released);
            return List.of();
        });
        final var wholeLoads = new WholeLoads(() -> fsMock, 2);
        try (var pool = Executors.newFixedThreadPool(5)) {
            try {
                for (var i = 0; i < 5; i++) {
                    final var collName = TestGlobals.COLL + i;
                    pool.execute(() -> {
                        try {
                            wholeLoads.transientPkIndex(TestGlobals.DB, collName);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }
                final var deadline = System.currentTimeMillis() + 5_000L;
                while (concurrent.get() < 2 && System.currentTimeMillis() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(0, wholeLoads.availablePermits());
                assertEquals(2, concurrent.get(), "the other loads wait for a permit");
            } finally {
                proceed.countDown();
            }
        }
        assertEquals(2, highest.get());
        assertEquals(2, wholeLoads.availablePermits());
    }

    private interface Call {
        Object run() throws Exception;
    }

    private static final class Caller implements Runnable {
        private final Call call;
        private final Thread thread = new Thread(this);
        private volatile Object result;
        private volatile Throwable failure;
        private volatile boolean interruptedOnFailure;

        Caller(Call call) {
            this.call = call;
        }

        @Override
        public void run() {
            try {
                result = call.run();
            } catch (Exception | Error e) {
                failure = e;
                interruptedOnFailure = Thread.currentThread().isInterrupted();
            }
        }

        boolean isParked() {
            final var state = thread.getState();
            return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
        }

        void interrupt() {
            thread.interrupt();
        }
    }

    private static List<Caller> startAll(int count, Call call) {
        final var callers = new ArrayList<Caller>();
        for (var i = 0; i < count; i++) {
            final var caller = new Caller(call);
            callers.add(caller);
            caller.thread.start();
        }
        return callers;
    }

    private static void awaitParked(List<Caller> callers) {
        final var deadline = System.currentTimeMillis() + 10_000L;
        while (!callers.stream().allMatch(Caller::isParked)) {
            assertTrue(System.currentTimeMillis() < deadline, "every caller should be parked on the shared load");
            Thread.onSpinWait();
        }
    }

    private static void joinAll(List<Caller> callers) throws InterruptedException {
        for (final var caller : callers) {
            caller.thread.join(10_000L);
            assertFalse(caller.thread.isAlive());
        }
    }

    private static FileSystem pkReadsBlockingFirst(AtomicInteger reads, CountDownLatch release) throws IOException {
        final var fsMock = mock(FileSystem.class);
        when(fsMock.pkIndexFileBytes(anyString(), anyString())).thenReturn(1024L);
        when(fsMock.readWholePkIndexFile(anyString(), anyString())).thenAnswer(_ -> {
            if (reads.incrementAndGet() == 1) {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return threeRows();
        });
        return fsMock;
    }

    @Test
    public void concurrentAdmittedPkLoadsReadTheFileOnce() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", 64L << 20);
        final var cache = new UserCache();
        final var reads = new AtomicInteger();
        final var release = new CountDownLatch(1);
        TestUtils.setPrivateField(cache, "fs", pkReadsBlockingFirst(reads, release));

        final var callers = startAll(8, () -> {
            locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
            try {
                return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
            } finally {
                locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
            }
        });
        awaitParked(callers);
        release.countDown();
        joinAll(callers);

        assertEquals(1, reads.get(), "the callers share one read of pk.idx");
        final var first = callers.getFirst().result;
        for (final var caller : callers) {
            assertNull(caller.failure);
            assertSame(first, caller.result, "every caller holds the one admitted list");
        }
        assertEquals(3, ((List<?>) first).size());
    }

    @Test
    public void aFailedSharedLoadFailsEveryWaiterAndTheNextCallRetries() throws Exception {
        final var reads = new AtomicInteger();
        final var release = new CountDownLatch(1);
        final var fsMock = mock(FileSystem.class);
        when(fsMock.readWholePkIndexFile(anyString(), anyString())).thenAnswer(_ -> {
            if (reads.incrementAndGet() == 1) {
                assertTrue(release.await(10, TimeUnit.SECONDS));
                throw new IOException("disk said no");
            }
            return threeRows();
        });
        final var wholeLoads = new WholeLoads(() -> fsMock, 2);

        final var callers = startAll(4, () -> wholeLoads.pkIndex(TestGlobals.DB, TestGlobals.COLL, true, _ -> true));
        awaitParked(callers);
        release.countDown();
        joinAll(callers);

        for (final var caller : callers) {
            assertInstanceOf(IOException.class, caller.failure);
            assertEquals("disk said no", caller.failure.getMessage());
        }
        assertEquals(1, reads.get());
        assertEquals(0, wholeLoads.loadsInFlight());
        assertEquals(3, wholeLoads.pkIndex(TestGlobals.DB, TestGlobals.COLL, true, _ -> true).size());
        assertEquals(2, reads.get(), "the next call after a failure reads again");
    }

    @Test
    public void anInterruptedWaiterGetsAnInterruptedIoException() throws Exception {
        final var reads = new AtomicInteger();
        final var release = new CountDownLatch(1);
        final var fsMock = pkReadsBlockingFirst(reads, release);
        final var wholeLoads = new WholeLoads(() -> fsMock, 2);
        final Call load = () -> wholeLoads.pkIndex(TestGlobals.DB, TestGlobals.COLL, true, _ -> true);
        final var leader = startAll(1, load);
        awaitParked(leader);
        final var waiter = startAll(1, load);
        awaitParked(waiter);

        waiter.getFirst().interrupt();
        joinAll(waiter);
        release.countDown();
        joinAll(leader);

        assertInstanceOf(InterruptedIOException.class, waiter.getFirst().failure);
        assertTrue(waiter.getFirst().interruptedOnFailure, "the interrupt status is kept");
        assertEquals(3, ((List<?>) leader.getFirst().result).size());
        assertEquals(1, reads.get());
    }

    @Test
    public void aWaiterLoadsItselfWhenTheLoaderWasInterrupted() throws Exception {
        final var reads = new AtomicInteger();
        final var fsMock = mock(FileSystem.class);
        when(fsMock.readWholePkIndexFile(anyString(), anyString())).thenAnswer(_ -> {
            if (reads.incrementAndGet() == 1) {
                try {
                    assertFalse(new CountDownLatch(1).await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new InterruptedIOException("the loader was interrupted");
            }
            return threeRows();
        });
        final var wholeLoads = new WholeLoads(() -> fsMock, 2);
        final Call load = () -> wholeLoads.pkIndex(TestGlobals.DB, TestGlobals.COLL, true, _ -> true);
        final var leader = startAll(1, load);
        awaitParked(leader);
        final var waiter = startAll(1, load);
        awaitParked(waiter);

        leader.getFirst().interrupt();
        joinAll(leader);
        joinAll(waiter);

        assertInstanceOf(InterruptedIOException.class, leader.getFirst().failure);
        assertNull(waiter.getFirst().failure, "another caller's interrupt is not the waiter's failure");
        assertEquals(3, ((List<?>) waiter.getFirst().result).size());
        assertEquals(2, reads.get());
    }

    @Test
    public void differentCollectionsLoadInParallel() throws Exception {
        final var bothReading = new CountDownLatch(2);
        final var fsMock = mock(FileSystem.class);
        when(fsMock.readWholePkIndexFile(anyString(), anyString())).thenAnswer(_ -> {
            bothReading.countDown();
            assertTrue(bothReading.await(10, TimeUnit.SECONDS), "the other collection's load runs at the same time");
            return threeRows();
        });
        final var wholeLoads = new WholeLoads(() -> fsMock, 2);
        final var collection = new AtomicInteger();
        final var callers = startAll(2, () -> wholeLoads.pkIndex(TestGlobals.DB,
                TestGlobals.COLL + collection.incrementAndGet(), true, _ -> true));
        joinAll(callers);

        for (final var caller : callers) {
            assertNull(caller.failure);
            assertEquals(3, ((List<?>) caller.result).size());
        }
    }

    @Test
    public void aLockFreeCallerNeverSharesTheLoadOfALockHolder() throws Exception {
        final var reads = new AtomicInteger();
        final var release = new CountDownLatch(1);
        final var fsMock = pkReadsBlockingFirst(reads, release);
        final var wholeLoads = new WholeLoads(() -> fsMock, 2);
        final var locked = startAll(1, () -> wholeLoads.pkIndex(TestGlobals.DB, TestGlobals.COLL, true, _ -> true));
        awaitParked(locked);

        final var unlocked = wholeLoads.transientPkIndex(TestGlobals.DB, TestGlobals.COLL);
        release.countDown();
        joinAll(locked);

        assertEquals(3, unlocked.size());
        assertEquals(2, reads.get(), "a lock holder may mutate a list it could not admit, so it is never shared");
    }

    @Test
    public void estimateCountsDeadPkRowsAndTheIndexLog() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        final var fs = IocContainer.get(FileSystem.class);
        final var inserted = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 200; i++) {
            final var data = new JsonObject();
            data.addProperty(Globals.PK_FIELD, UUID.randomUUID().toString());
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
            entry.setPage(0L);
            inserted.add(fs.insertIntoCollection(entry));
        }
        final var liveBytesBefore = fs.pkIndexFileBytes(TestGlobals.DB, TestGlobals.COLL);
        for (var i = 0; i < 100; i++) {
            fs.deleteFromCollection(inserted.get(i));
        }
        final var live = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        final var pkFileBytes = fs.pkIndexFileBytes(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(100, live.size());
        assertTrue(pkFileBytes > liveBytesBefore, "the delete rows stay in the log");
        assertTrue(CacheSizeEstimator.estimatePkIndexSizeFromFileBytes(pkFileBytes) >= CacheSizeEstimator
                .estimatePkIndexSize(live));

        final var ids = new HashSet<String>();
        for (final var row : live) {
            ids.add(row.getValue());
        }
        final Map<Class<?>, List<FieldIndexEntry<?>>> base = Map.of(String.class,
                List.of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "open", new HashSet<>(ids))));
        fs.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, "status", base);
        final var baseBytes = fs.indexFileBytes(TestGlobals.DB, TestGlobals.COLL, "status", "String");
        final var added = new ArrayList<String>();
        for (var i = 0; i < 50; i++) {
            added.add(UUID.randomUUID().toString());
        }
        final List<FieldIndexEntry<?>> survivors = List.of(base.get(String.class).getFirst(),
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "closed", new HashSet<>(added)));
        fs.appendIndexLog(TestGlobals.DB, TestGlobals.COLL, "status", "String",
                List.of(new IndexLogChange("closed", added, List.of())), survivors);
        final var loaded = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, "status", String.class);
        final var indexBytes = fs.indexFileBytes(TestGlobals.DB, TestGlobals.COLL, "status", "String");

        assertEquals(Set.of("open", "closed"), Set.copyOf(loaded.stream().map(FieldIndexEntry::getValue).toList()));
        assertTrue(indexBytes > baseBytes, "the .idxlog counts too");
        final List<FieldIndexEntry<?>> resident = new ArrayList<>(loaded);
        assertTrue(CacheSizeEstimator.estimateFieldIndexSizeFromFileBytes(indexBytes) >= CacheSizeEstimator
                .estimateFieldIndexSize(resident));
        assertEquals(0L, fs.indexFileBytes(TestGlobals.DB, TestGlobals.COLL, Globals.PK_FIELD, "String"));
    }
}
