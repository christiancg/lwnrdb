package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.cache.CacheableResource;
import org.techhouse.cache.Measure;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.FieldIndexBatch;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class UserCacheFootprintTest {
    private static final String DB = "footprintDb";
    private static final String COLL = "footprintColl";

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static DbEntry entry(String id, String payload) {
        final var data = new JsonObject();
        data.addProperty("payload", payload);
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(DB);
        dbEntry.setCollectionName(COLL);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return dbEntry;
    }

    private static long trackedCollectionBytes(UserCache cache) {
        var total = 0L;
        for (final var resource : cache.listCacheableResources()) {
            if (resource.kind() == AccessKind.COLLECTION && DB.equals(resource.dbName())) {
                total += resource.estimatedSizeBytes();
            }
        }
        return total;
    }

    private static long recomputedCollectionBytes(UserCache cache) {
        final var cached = cache.getCachedCollection(DB, COLL);
        if (cached == null) {
            return 0L;
        }
        var total = 0L;
        for (final var dbEntry : cached.values()) {
            total += CacheSizeEstimator.estimateCollectionEntry(dbEntry);
        }
        return total;
    }

    private static void assertTrackedMatchesRecomputed(UserCache cache) {
        assertEquals(recomputedCollectionBytes(cache), trackedCollectionBytes(cache),
                "tracked footprint must equal a full recomputation");
    }

    @Test
    public void test_tracked_footprint_matches_after_inserts() {
        final var cache = new UserCache();
        for (var i = 0; i < 25; i++) {
            cache.addEntryToCache(DB, COLL, entry("id" + i, "payload-" + i));
        }
        assertTrue(trackedCollectionBytes(cache) > 0);
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_matches_after_replacing_an_entry_with_a_bigger_one() {
        final var cache = new UserCache();
        cache.addEntryToCache(DB, COLL, entry("id1", "small"));
        final var before = trackedCollectionBytes(cache);

        cache.addEntryToCache(DB, COLL, entry("id1", "a considerably longer payload than before"));

        assertTrue(trackedCollectionBytes(cache) > before);
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_matches_after_replacing_an_entry_with_a_smaller_one() {
        final var cache = new UserCache();
        cache.addEntryToCache(DB, COLL, entry("id1", "a considerably longer payload than before"));
        final var before = trackedCollectionBytes(cache);

        cache.addEntryToCache(DB, COLL, entry("id1", "small"));

        assertTrue(trackedCollectionBytes(cache) < before);
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_matches_after_bulk_insert() {
        final var cache = new UserCache();
        final var entries = new ArrayList<DbEntry>();
        for (var i = 0; i < 30; i++) {
            entries.add(entry("bulk" + i, "payload-" + i));
        }
        cache.addEntriesToCache(DB, COLL, entries);
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_matches_after_evicting_one_entry() {
        final var cache = new UserCache();
        for (var i = 0; i < 10; i++) {
            cache.addEntryToCache(DB, COLL, entry("id" + i, "payload-" + i));
        }
        final var before = trackedCollectionBytes(cache);

        cache.evictEntry(DB, COLL, "id3");

        assertTrue(trackedCollectionBytes(cache) < before);
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_is_zero_after_evicting_the_collection() {
        final var cache = new UserCache();
        for (var i = 0; i < 10; i++) {
            cache.addEntryToCache(DB, COLL, entry("id" + i, "payload-" + i));
        }
        cache.evictCollection(DB, COLL);

        assertEquals(0L, trackedCollectionBytes(cache));
        assertTrackedMatchesRecomputed(cache);
    }

    @Test
    public void test_tracked_footprint_matches_under_mixed_traffic() {
        final var cache = new UserCache();
        for (var round = 0; round < 8; round++) {
            for (var i = 0; i < 12; i++) {
                cache.addEntryToCache(DB, COLL, entry("id" + i, "payload-" + round + "-" + "x".repeat(round)));
            }
            if (round % 3 == 0) {
                cache.evictEntry(DB, COLL, "id" + round);
            }
            assertTrackedMatchesRecomputed(cache);
        }
    }

    private static final String FIELD = "status";
    private static final long COUNT_ONLY_BYTES_PER_ENTRY = CacheSizeEstimator.estimateFieldIndexSizeByCount(1);

    private static final AtomicInteger FIELD_INDEX_ITERATIONS = new AtomicInteger();

    private static final class IterationCountingList extends ArrayList<FieldIndexEntry<?>> {
        @Override
        @SuppressWarnings("NullableProblems")
        public Iterator<FieldIndexEntry<?>> iterator() {
            FIELD_INDEX_ITERATIONS.incrementAndGet();
            return super.iterator();
        }
    }

    private static IterationCountingList publishFieldIndex(UserCache cache) throws Exception {
        FIELD_INDEX_ITERATIONS.set(0);
        final var published = new IterationCountingList();
        for (var i = 0; i < 3; i++) {
            published.add(new FieldIndexEntry<>(DB, COLL, "value-" + i, Set.of("id" + i, "other" + i)));
        }
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final Map<String, List<FieldIndexEntry<?>>> indexes = new ConcurrentHashMap<>();
        indexes.put(Cache.getIndexIdentifier(FIELD, String.class), published);
        TestUtils.getPrivateField(cache, "fieldIndexMap", type).put(Cache.getCollectionIdentifier(DB, COLL), indexes);
        return published;
    }

    private static long fieldIndexBytes(UserCache cache) {
        return cache.listCacheableResources().stream().filter(resource -> resource.kind() == AccessKind.FIELD_INDEX)
                .mapToLong(CacheableResource::estimatedSizeBytes).sum();
    }

    @Test
    public void test_a_field_index_is_not_iterated_while_its_writer_holds_the_lock() throws Exception {
        final var cache = new UserCache();
        final var published = publishFieldIndex(cache);
        final var locks = IocContainer.get(ResourceLocking.class);
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

        final var estimate = fieldIndexBytes(cache);

        release.countDown();
        writer.join(5_000);
        assertEquals(published.size() * COUNT_ONLY_BYTES_PER_ENTRY, estimate,
                "an index whose writer is mid-append is estimated by its size alone");
        assertEquals(0, FIELD_INDEX_ITERATIONS.get(), "the estimate must not walk a list its writer is appending to");
    }

    @Test
    public void test_a_field_index_is_measured_in_full_when_its_lock_is_free() throws Exception {
        final var cache = new UserCache();
        final var published = publishFieldIndex(cache);

        assertTrue(fieldIndexBytes(cache) > published.size() * COUNT_ONLY_BYTES_PER_ENTRY,
                "with no writer the estimate counts every value and id");
    }

    @Test
    public void test_the_index_writer_can_still_measure_its_own_index() throws Exception {
        final var cache = new UserCache();
        final var published = publishFieldIndex(cache);
        final var locks = IocContainer.get(ResourceLocking.class);
        locks.lockIndex(DB, COLL, FIELD);
        try {
            assertTrue(fieldIndexBytes(cache) > published.size() * COUNT_ONLY_BYTES_PER_ENTRY,
                    "the worker loading an index measures it in full under its own write lock");
        } finally {
            locks.releaseIndex(DB, COLL, FIELD);
        }
    }

    private static final long FINITE_BUDGET = 1L << 30;

    private static long withFiniteBudget(TestBody body) throws Exception {
        final var configuration = Configuration.getInstance();
        final var original = configuration.getMaxMemoryBytes();
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", FINITE_BUDGET);
        try {
            FIELD_INDEX_ITERATIONS.set(0);
            body.run();
            return FIELD_INDEX_ITERATIONS.get();
        } finally {
            TestUtils.setPrivateField(configuration, "maxMemoryBytes", original);
        }
    }

    @FunctionalInterface
    private interface TestBody {
        void run() throws Exception;
    }

    @Test
    public void admissionDoesNotRemeasureResidentIndexes() throws Exception {
        final var cache = new UserCache();
        publishFieldIndex(cache);

        final var iterations = withFiniteBudget(() -> cache.addEntryToCache(DB, COLL, entry("admitted", "payload")));

        assertEquals(0, iterations, "admission sums remembered sizes and must not walk a resident index");
        assertNotNull(cache.getCachedCollection(DB, COLL).get("admitted"));
    }

    @Test
    public void sweepStillMeasuresFully() throws Exception {
        final var userCache = IocContainer.get(UserCache.class);
        final var memoryManagement = IocContainer.get(MemoryManagement.class);
        publishFieldIndex(userCache);
        try {
            assertEquals(0, withFiniteBudget(() -> memoryManagement.ensureHeadroomForBytes(1L)),
                    "the write path's headroom check uses remembered sizes");
            assertTrue(withFiniteBudget(memoryManagement::runEvictionSweep) > 0,
                    "the 5 s sweep re-measures every resident index");
            assertTrue(withFiniteBudget(() -> userCache.listCacheableResources(Measure.FULL)) > 0);
            assertEquals(0, withFiniteBudget(() -> userCache.listCacheableResources(Measure.REMEMBERED)));
        } finally {
            userCache.evictCollection(DB, COLL);
        }
    }

    private static long rememberedStatusBytes(UserCache cache, Measure measure) {
        return cache.listCacheableResources(measure).stream()
                .filter(resource -> resource.kind() == AccessKind.FIELD_INDEX
                        && TestGlobals.DB.equals(resource.dbName())
                        && Cache.getIndexIdentifier(FIELD, String.class).equals(resource.indexKey()))
                .mapToLong(CacheableResource::estimatedSizeBytes).sum();
    }

    @Test
    public void batchUpdatesRememberedAverage() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        final var userCache = IocContainer.get(UserCache.class);
        final var locks = IocContainer.get(ResourceLocking.class);
        IocContainer.get(FileSystem.class).writeIndexFile(TestGlobals.DB, TestGlobals.COLL, FIELD, Map.of(String.class,
                List.of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "v", new HashSet<>(Set.of("a"))))));
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            userCache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
        final var before = rememberedStatusBytes(userCache, Measure.REMEMBERED);

        locks.lockIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        try {
            final var batch = FieldIndexBatch.open(TestGlobals.DB, TestGlobals.COLL, FIELD);
            for (var i = 0; i < 50; i++) {
                batch.apply("a-rather-long-document-identifier-" + i, new JsonString("v"));
            }
            batch.flush();
        } finally {
            locks.releaseIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        }

        final var remembered = rememberedStatusBytes(userCache, Measure.REMEMBERED);
        final var measured = rememberedStatusBytes(userCache, Measure.FULL);
        assertTrue(remembered > before,
                "50 new ids must grow the remembered footprint: " + before + " -> " + remembered);
        assertEquals(measured, remembered, 64, "the remembered size tracks a full measurement");
    }
}
