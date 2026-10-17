package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                    pool.execute(() -> {
                        try {
                            wholeLoads.transientPkIndex(TestGlobals.DB, TestGlobals.COLL);
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
