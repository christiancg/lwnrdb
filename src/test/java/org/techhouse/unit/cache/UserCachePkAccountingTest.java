package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.cache.CacheableResource;
import org.techhouse.cache.Measure;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class UserCachePkAccountingTest {
    private static final String DB = "pkAccountingDb";
    private static final String COLL = "pkAccountingColl";
    private static final String FIELD = "status";
    private static final long GENEROUS_CAP = 64L << 20;

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration config = Configuration.getInstance();
    private Long savedMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        PK_LIST_ARMED.set(false);
        savedMaxMemory = TestUtils.getPrivateField(config, "maxMemoryBytes", Long.class);
        TestUtils.setPrivateField(config, "maxMemoryBytes", GENEROUS_CAP);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static final AtomicBoolean PK_LIST_ARMED = new AtomicBoolean();

    private static final class ArmableList extends ArrayList<PkIndexEntry> {
        @Override
        @SuppressWarnings("NullableProblems")
        public Iterator<PkIndexEntry> iterator() {
            if (PK_LIST_ARMED.get()) {
                throw new AssertionError("the sweep walked the cached PK index");
            }
            return super.iterator();
        }
    }

    private static ArmableList pkRows(int count) {
        final var rows = new ArmableList();
        for (var i = 0; i < count; i++) {
            rows.add(new PkIndexEntry(DB, COLL, "row-" + i, i * 10L, 10, 0));
        }
        return rows;
    }

    private UserCache cacheReading(List<PkIndexEntry> fromDisk) throws Exception {
        final var cache = new UserCache();
        final var fsMock = mock(FileSystem.class);
        when(fsMock.readWholePkIndexFile(DB, COLL)).thenReturn(fromDisk);
        TestUtils.setPrivateField(cache, "fs", fsMock);
        return cache;
    }

    private List<PkIndexEntry> admitUnderLock(UserCache cache) throws InterruptedException, IOException {
        locks.lock(DB, COLL);
        try {
            return cache.getPkIndexAndLoadIfNecessary(DB, COLL);
        } finally {
            locks.release(DB, COLL);
        }
    }

    private static long pkBytes(UserCache cache) {
        return cache.listCacheableResources().stream().filter(resource -> resource.kind() == AccessKind.PK_INDEX)
                .mapToLong(CacheableResource::estimatedSizeBytes).sum();
    }

    @Test
    public void test_sweep_never_iterates_the_pk_list() throws Exception {
        final var cache = cacheReading(pkRows(5));
        final var cached = admitUnderLock(cache);
        final var recordedPerEntry = CacheSizeEstimator.pkIndexBytesPerEntry(pkRows(5));

        cached.add(new PkIndexEntry(DB, COLL, "row-5-" + "x".repeat(58), 50L, 10, 0));

        assertEquals(6 * recordedPerEntry, pkBytes(cache), "the sweep charges size() times the recorded bytes per row");
    }

    @Test
    public void test_pk_index_is_not_admitted_without_the_collection_lock() throws Exception {
        final var cache = cacheReading(pkRows(3));
        cache.getPkIndexAndLoadIfNecessary(DB, COLL);
        assertEquals(0L, pkBytes(cache));
    }

    @Test
    public void test_pk_index_is_not_measured_when_caching_is_disabled() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", -1L);
        final var rows = pkRows(3);
        PK_LIST_ARMED.set(true);
        final var cache = cacheReading(rows);
        admitUnderLock(cache);
        assertEquals(0L, pkBytes(cache));
    }

    @Test
    public void test_pk_record_cleared_on_eviction() throws Exception {
        final var rows = pkRows(4);
        final var cache = cacheReading(rows);
        admitUnderLock(cache);
        assertTrue(pkBytes(cache) > 0L);

        cache.evictPkIndex(DB, COLL);
        assertEquals(0L, pkBytes(cache));

        final var collId = Cache.getCollectionIdentifier(DB, COLL);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        TestUtils.getPrivateField(cache, "pkIndexMap", type).put(collId, pkRows(2));
        assertEquals(2 * CacheSizeEstimator.pkIndexBytesPerEntry(List.of()), pkBytes(cache),
                "an index with no recorded size falls back to the typical row, not the evicted record");
    }

    @Test
    public void test_pk_record_cleared_with_its_collection_and_database() throws Exception {
        final var cache = cacheReading(pkRows(2));
        admitUnderLock(cache);
        cache.evictCollection(DB, COLL);
        assertEquals(0L, pkBytes(cache));

        admitUnderLock(cache);
        assertTrue(pkBytes(cache) > 0L);
        cache.evictDatabase(DB);
        assertEquals(0L, pkBytes(cache));
    }

    private static Map<String, List<FieldIndexEntry<?>>> publishFieldIndex(UserCache cache, int entries)
            throws Exception {
        final List<FieldIndexEntry<?>> published = new ArrayList<>();
        for (var i = 0; i < entries; i++) {
            published.add(new FieldIndexEntry<>(DB, COLL, "a-rather-long-value-" + i,
                    new java.util.HashSet<>(List.of("id-with-a-long-name-" + i))));
        }
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final Map<String, List<FieldIndexEntry<?>>> indexes = new ConcurrentHashMap<>();
        indexes.put(Cache.getIndexIdentifier(FIELD, String.class), published);
        TestUtils.getPrivateField(cache, "fieldIndexMap", type).put(Cache.getCollectionIdentifier(DB, COLL), indexes);
        return indexes;
    }

    private static long fieldIndexBytes(UserCache cache) {
        return cache.listCacheableResources().stream().filter(resource -> resource.kind() == AccessKind.FIELD_INDEX)
                .mapToLong(CacheableResource::estimatedSizeBytes).sum();
    }

    private long fieldIndexBytesWhileItsWriterHoldsTheLock(UserCache cache) throws InterruptedException {
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var writer = new Thread(() -> {
            try {
                locks.lockIndex(DB, COLL, FIELD);
                held.countDown();
                release.await();
                locks.releaseIndex(DB, COLL, FIELD);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            return fieldIndexBytes(cache);
        } finally {
            release.countDown();
            writer.join(5_000);
        }
    }

    @Test
    public void test_busy_field_index_reuses_its_last_measure_scaled_by_count() throws Exception {
        final var cache = new UserCache();
        final var indexes = publishFieldIndex(cache, 3);
        final var measured = fieldIndexBytes(cache);
        final var list = indexes.values().iterator().next();
        list.add(list.getFirst());

        assertEquals(4 * (measured / 3), fieldIndexBytesWhileItsWriterHoldsTheLock(cache));
    }

    @Test
    public void test_busy_field_index_after_eviction_uses_the_count_model() throws Exception {
        final var cache = new UserCache();
        publishFieldIndex(cache, 3);
        assertTrue(fieldIndexBytes(cache) > CacheSizeEstimator.estimateFieldIndexSizeByCount(3));

        cache.evictFieldIndexAllTypes(DB, COLL, FIELD);
        publishFieldIndex(cache, 3);

        assertEquals(CacheSizeEstimator.estimateFieldIndexSizeByCount(3),
                fieldIndexBytesWhileItsWriterHoldsTheLock(cache),
                "an evicted index's remembered size must not outlive it");
    }

    @Test
    public void test_single_field_index_eviction_forgets_its_measure() throws Exception {
        final var cache = new UserCache();
        publishFieldIndex(cache, 2);
        fieldIndexBytes(cache);

        cache.evictFieldIndex(DB, COLL, Cache.getIndexIdentifier(FIELD, String.class));
        publishFieldIndex(cache, 2);

        assertEquals(CacheSizeEstimator.estimateFieldIndexSizeByCount(2),
                fieldIndexBytesWhileItsWriterHoldsTheLock(cache));
    }

    @Test
    public void test_the_footprint_is_the_sum_of_the_remembered_resources() throws Exception {
        final var cache = cacheReading(pkRows(7));
        admitUnderLock(cache);
        final var footprint = UserCache.class.getDeclaredMethod("footprintBytes");
        footprint.setAccessible(true);

        final var remembered = cache.listCacheableResources(Measure.REMEMBERED).stream()
                .mapToLong(CacheableResource::estimatedSizeBytes).sum();

        assertTrue(remembered > 0L);
        assertEquals(remembered, (long) footprint.invoke(cache));
    }
}
