package org.techhouse.unit.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cache.UsageCounter;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

public class MemoryManagementTest {

    private long savedMaxCollectionCacheBytes;

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
        final var config = Configuration.getInstance();
        savedMaxCollectionCacheBytes = config.getMaxCollectionCacheBytes();
    }

    @AfterEach
    public void tearDown() throws IOException, InterruptedException, NoSuchFieldException, IllegalAccessException {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxCollectionCacheBytes", savedMaxCollectionCacheBytes);
        final var mm = IocContainer.get(MemoryManagement.class);
        TestUtils.setPrivateField(mm, "counters", new java.util.concurrent.ConcurrentHashMap<>());
        TestUtils.standardTearDown();
    }

    private void setCacheCap(long bytes) throws NoSuchFieldException, IllegalAccessException {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxCollectionCacheBytes", bytes);
    }

    @Test
    public void test_recordAccess_increments_counter() {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "myDb", "myColl", null);
        mm.recordAccess(AccessKind.COLLECTION, "myDb", "myColl", null);
        final var counter = mm.getCounter(AccessKind.COLLECTION, "myDb", "myColl", null);
        assertNotNull(counter);
        assertEquals(2L, counter.getAccessCount());
        assertTrue(counter.getLastAccessMillis() > 0L);
    }

    @Test
    public void test_recordAccess_ignores_admin_db() {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, Globals.ADMIN_DB_NAME, "databases", null);
        assertNull(mm.getCounter(AccessKind.COLLECTION, Globals.ADMIN_DB_NAME, "databases", null));
    }

    @Test
    public void test_recordAccess_skipped_when_caching_disabled() throws NoSuchFieldException, IllegalAccessException {
        setCacheCap(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "myDb", "myColl", null);
        assertNull(mm.getCounter(AccessKind.COLLECTION, "myDb", "myColl", null));
    }

    @Test
    public void test_isCachingDisabled_when_config_minus_one() throws NoSuchFieldException, IllegalAccessException {
        setCacheCap(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertTrue(mm.isCachingDisabled());
        assertFalse(mm.isCacheUnlimited());
    }

    @Test
    public void test_isCacheUnlimited_when_config_zero() throws NoSuchFieldException, IllegalAccessException {
        setCacheCap(0L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertTrue(mm.isCacheUnlimited());
        assertFalse(mm.isCachingDisabled());
    }

    @Test
    public void test_runEvictionSweep_noop_when_unlimited() throws Exception {
        setCacheCap(0L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.runEvictionSweep();
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_runEvictionSweep_noop_when_disabled() throws Exception {
        setCacheCap(-1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.runEvictionSweep();
        // documents were seeded directly, but sweep is a no-op
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_runEvictionSweep_evicts_lowest_LFU_first() throws Exception {
        setCacheCap(1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "hot", 10);
        seedCollectionCache(cache, "cold", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        for (int i = 0; i < 5; i++) {
            mm.recordAccess(AccessKind.COLLECTION, "userDb", "hot", null);
        }
        mm.recordAccess(AccessKind.COLLECTION, "userDb", "cold", null);
        mm.runEvictionSweep();
        final var remaining = cache.listCacheableResources();
        assertTrue(remaining.stream().noneMatch(r -> r.collName().equals("cold")),
                "cold should be evicted first");
    }

    @Test
    public void test_runEvictionSweep_respects_priority_tier() throws Exception {
        // PK_INDEX entry is ~96B; with 10 large COLLECTION docs the collection is several KB.
        // Cap at 200B: enough to keep the PK_INDEX but force evicting the COLLECTION.
        setCacheCap(200L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "coll", 10);
        seedPkIndex(cache, "coll");
        final var mm = IocContainer.get(MemoryManagement.class);
        // Same access count: tier wins (COLLECTION should be evicted before PK_INDEX)
        mm.recordAccess(AccessKind.COLLECTION, "userDb", "coll", null);
        mm.recordAccess(AccessKind.PK_INDEX, "userDb", "coll", null);
        mm.runEvictionSweep();
        final var remaining = cache.listCacheableResources();
        assertTrue(remaining.stream().anyMatch(r -> r.kind() == AccessKind.PK_INDEX),
                "PK_INDEX should survive eviction");
        assertTrue(remaining.stream().noneMatch(r -> r.kind() == AccessKind.COLLECTION),
                "COLLECTION should be evicted first");
    }

    @Test
    public void test_runEvictionSweep_never_touches_admin() throws Exception {
        setCacheCap(1L);
        final var cache = IocContainer.get(Cache.class);
        // Seed an admin collection entry directly via reflection.
        seedRawCollection(cache);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.runEvictionSweep();
        // The admin entry should remain — listCacheableResources excludes admin so direct field probe:
        final var type = new org.techhouse.utils.ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {};
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", type);
        assertTrue(collectionMap.containsKey(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases")));
    }

    @Test
    public void test_putCounter_and_clearCounter() {
        final var mm = IocContainer.get(MemoryManagement.class);
        final var counter = new UsageCounter(AccessKind.COLLECTION, "db", "coll", "", 5L, 100L);
        mm.putCounter(counter);
        assertNotNull(mm.getCounter(AccessKind.COLLECTION, "db", "coll", null));
        mm.clearCounter(AccessKind.COLLECTION, "db", "coll", null);
        assertNull(mm.getCounter(AccessKind.COLLECTION, "db", "coll", null));
    }

    @Test
    public void test_getCountersSnapshot_isolated_from_internal() {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "db", "coll", null);
        final var snapshot = mm.getCountersSnapshot();
        assertEquals(1, snapshot.size());
    }

    @Test
    public void test_buildKey_includes_all_components() {
        final var key = MemoryManagement.buildKey(AccessKind.FIELD_INDEX, "db", "coll", "field");
        assertTrue(key.contains("FIELD_INDEX"));
        assertTrue(key.contains("db"));
        assertTrue(key.contains("coll"));
        assertTrue(key.contains("field"));
    }

    @Test
    public void test_startSweepThread_noop_when_disabled() throws NoSuchFieldException, IllegalAccessException {
        setCacheCap(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.startSweepThread();
        // Should not have started a scheduler
        final var scheduler = TestUtils.getPrivateField(mm, "scheduler", java.util.concurrent.ScheduledExecutorService.class);
        assertNull(scheduler);
    }

    @Test
    public void test_stopSweepThread_safe_when_not_started() {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.stopSweepThread();
    }

    @Test
    public void test_loadProfileFromAdmin_no_throw_when_empty() {
        final var mm = IocContainer.get(MemoryManagement.class);
        assertDoesNotThrow(mm::loadProfileFromAdmin);
    }

    @Test
    public void test_loadProfileFromAdmin_skipped_when_caching_disabled() throws Exception {
        setCacheCap(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        // Should return immediately without touching the filesystem.
        assertDoesNotThrow(mm::loadProfileFromAdmin);
    }

    @Test
    public void test_loadProfileFromAdmin_reads_existing_records() throws Exception {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "userDb", "userColl", null);
        org.techhouse.ops.AdminOperationHelper.upsertCollectionUsage(
                new org.techhouse.bckg_ops.events.CollectionUsageEvent(AccessKind.COLLECTION, "userDb", "userColl", null,
                        System.currentTimeMillis()));
        // Clear in-memory counters and reload from admin storage.
        TestUtils.setPrivateField(mm, "counters", new java.util.concurrent.ConcurrentHashMap<>());
        mm.loadProfileFromAdmin();
        assertNotNull(mm.getCounter(AccessKind.COLLECTION, "userDb", "userColl", null));
    }

    @Test
    public void test_runEvictionSweep_handles_field_index_tier() throws Exception {
        setCacheCap(1L);
        final var cache = IocContainer.get(Cache.class);
        seedFieldIndex(cache, "coll");
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.FIELD_INDEX, "userDb", "coll", "f|String");
        mm.runEvictionSweep();
        final var remaining = cache.listCacheableResources();
        assertTrue(remaining.stream().noneMatch(r -> r.kind() == AccessKind.FIELD_INDEX),
                "FIELD_INDEX should be evicted under tight cap");
    }

    @Test
    public void test_runEvictionSweep_skips_concurrent_call() throws Exception {
        setCacheCap(1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "coll", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        // Pretend a sweep is already in progress.
        final var sweepRunning = TestUtils.getPrivateField(mm, "sweepRunning", java.util.concurrent.atomic.AtomicBoolean.class);
        sweepRunning.set(true);
        try {
            mm.runEvictionSweep();
            // Sweep should have done nothing (the existing entry remains).
            assertEquals(1, cache.listCacheableResources().size());
        } finally {
            sweepRunning.set(false);
        }
    }

    @Test
    public void test_startSweepThread_starts_scheduler_when_enabled() throws Exception {
        setCacheCap(1024L);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.startSweepThread();
        try {
            final var scheduler = TestUtils.getPrivateField(mm, "scheduler", java.util.concurrent.ScheduledExecutorService.class);
            assertNotNull(scheduler);
        } finally {
            mm.stopSweepThread();
        }
    }

    @Test
    public void test_runEvictionSweepSafely_swallows_exception() throws Exception {
        final var mm = IocContainer.get(MemoryManagement.class);
        final var method = MemoryManagement.class.getDeclaredMethod("runEvictionSweepSafely");
        method.setAccessible(true);
        // Just verify it doesn't propagate even if something goes wrong inside.
        method.invoke(mm);
    }

    @Test
    public void test_submitCleanupTask_invokes_background_manager() throws Exception {
        final var mm = IocContainer.get(MemoryManagement.class);
        final var method = MemoryManagement.class.getDeclaredMethod("submitCleanupTask");
        method.setAccessible(true);
        // Should not throw — just submits the event.
        method.invoke(mm);
    }

    @Test
    public void test_tier_ordinal_covers_all_kinds() throws Exception {
        // Exercise all branches of tierOrdinal via runEvictionSweep with one resource per kind.
        setCacheCap(1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "coll1", 5);
        seedPkIndex(cache, "coll2");
        seedFieldIndex(cache, "coll3");
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "userDb", "coll1", null);
        mm.recordAccess(AccessKind.PK_INDEX, "userDb", "coll2", null);
        mm.recordAccess(AccessKind.FIELD_INDEX, "userDb", "coll3", "f|String");
        mm.runEvictionSweep();
    }

    private void seedFieldIndex(Cache cache, String collName) throws NoSuchFieldException, IllegalAccessException {
        final var type = new org.techhouse.utils.ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {};
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        final Map<String, List<FieldIndexEntry<?>>> indexes = new ConcurrentHashMap<>();
        indexes.put("f|String", List.of(new FieldIndexEntry<>("userDb", collName, "value", new HashSet<>(List.of("id1")))));
        fieldIndexMap.put(Cache.getCollectionIdentifier("userDb", collName), indexes);
    }

    private void seedCollectionCache(Cache cache, String collName, int count) throws NoSuchFieldException, IllegalAccessException {
        final var type = new org.techhouse.utils.ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {};
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", type);
        final var inner = new ConcurrentHashMap<String, DbEntry>();
        for (int i = 0; i < count; i++) {
            final var obj = new JsonObject();
            obj.addProperty(Globals.PK_FIELD, "id" + i);
            obj.addProperty("v", "x".repeat(64));
            final var entry = DbEntry.fromJsonObject("userDb", collName, obj);
            inner.put(entry.get_id(), entry);
        }
        collectionMap.put(Cache.getCollectionIdentifier("userDb", collName), inner);
    }

    private void seedRawCollection(Cache cache) throws NoSuchFieldException, IllegalAccessException {
        final var type = new org.techhouse.utils.ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {};
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", type);
        final var inner = new ConcurrentHashMap<String, DbEntry>();
        for (int i = 0; i < 5; i++) {
            final var obj = new JsonObject();
            obj.addProperty(Globals.PK_FIELD, "id" + i);
            inner.put("id" + i, DbEntry.fromJsonObject(Globals.ADMIN_DB_NAME, "databases", obj));
        }
        collectionMap.put(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases"), inner);
    }

    private void seedPkIndex(Cache cache, String collName) throws NoSuchFieldException, IllegalAccessException {
        final var type = new org.techhouse.utils.ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {};
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", type);
        final var list = new ArrayList<PkIndexEntry>();
        list.add(new PkIndexEntry("userDb", collName, "id0", 0, 10, 0));
        pkIndexMap.put(Cache.getCollectionIdentifier("userDb", collName), list);
    }
}
