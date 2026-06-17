package org.techhouse.unit.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.*;
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

    private long savedMaxMemoryBytes;

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
        savedMaxMemoryBytes = Configuration.getInstance().getMaxMemoryBytes();
    }

    @AfterEach
    public void tearDown() throws IOException, InterruptedException, NoSuchFieldException, IllegalAccessException {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxMemoryBytes", savedMaxMemoryBytes);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.stopSweepThread();
        TestUtils.setPrivateField(mm, "counters", new ConcurrentHashMap<>());
        TestUtils.standardTearDown();
    }

    private void setMaxMemory(long bytes) throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(Configuration.getInstance(), "maxMemoryBytes", bytes);
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
        setMaxMemory(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "myDb", "myColl", null);
        assertNull(mm.getCounter(AccessKind.COLLECTION, "myDb", "myColl", null));
    }

    @Test
    public void test_isCachingDisabled_when_config_minus_one() throws NoSuchFieldException, IllegalAccessException {
        setMaxMemory(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertTrue(mm.isCachingDisabled());
        assertFalse(mm.isCacheUnlimited());
    }

    @Test
    public void test_isCacheUnlimited_when_config_zero() throws NoSuchFieldException, IllegalAccessException {
        setMaxMemory(0L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertTrue(mm.isCacheUnlimited());
        assertFalse(mm.isCachingDisabled());
    }

    @Test
    public void test_runEvictionSweep_noop_when_unlimited() throws Exception {
        setMaxMemory(0L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.runEvictionSweep();
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_runEvictionSweep_noop_when_disabled() throws Exception {
        setMaxMemory(-1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.runEvictionSweep();
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_runEvictionSweep_noop_when_cache_under_cap() throws Exception {
        setMaxMemory(10L * 1024L * 1024L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.runEvictionSweep();
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_runEvictionSweep_evicts_lowest_LFU_first() throws Exception {
        setMaxMemory(1L);
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
    public void test_runEvictionSweep_skips_resource_held_by_reader() throws Exception {
        setMaxMemory(1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "lockedColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "userDb", "lockedColl", null);
        final var locks = IocContainer.get(org.techhouse.concurrency.ResourceLocking.class);
        locks.lockRead("userDb", "lockedColl");
        try {
            mm.runEvictionSweep();
            final var remaining = cache.listCacheableResources();
            assertTrue(remaining.stream().anyMatch(r -> r.collName().equals("lockedColl")),
                    "a resource held by a reader must not be evicted");
        } finally {
            locks.releaseRead("userDb", "lockedColl");
        }
    }

    @Test
    public void test_runEvictionSweep_respects_priority_tier() throws Exception {
        // PK index sizes by ESTIMATED_PK_ENTRY_BYTES (small); collection by entry bytes.
        // Set cap so PK index alone fits, but collection + PK index together do not.
        final var cache = IocContainer.get(Cache.class);
        seedPkIndex(cache, "coll");
        seedCollectionCache(cache, "coll", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "userDb", "coll", null);
        mm.recordAccess(AccessKind.PK_INDEX, "userDb", "coll", null);
        // size the cap to exactly the PK index size so the collection must go.
        final var pkBytes = cache.listCacheableResources().stream()
                .filter(r -> r.kind() == AccessKind.PK_INDEX)
                .mapToLong(CacheableResource::estimatedSizeBytes)
                .sum();
        setMaxMemory(pkBytes);
        mm.runEvictionSweep();
        final var remaining = cache.listCacheableResources();
        assertTrue(remaining.stream().anyMatch(r -> r.kind() == AccessKind.PK_INDEX),
                "PK_INDEX should survive eviction");
        assertTrue(remaining.stream().noneMatch(r -> r.kind() == AccessKind.COLLECTION),
                "COLLECTION should be evicted first");
    }

    @Test
    public void test_runEvictionSweep_never_touches_admin() throws Exception {
        setMaxMemory(1L);
        final var cache = IocContainer.get(Cache.class);
        seedRawCollection(cache);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.runEvictionSweep();
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
    public void test_buildKey_handles_null_indexKey() {
        final var key = MemoryManagement.buildKey(AccessKind.COLLECTION, "db", "coll", null);
        assertTrue(key.contains("COLLECTION"));
        assertTrue(key.contains("db"));
        assertTrue(key.contains("coll"));
    }

    @Test
    public void test_startSweepThread_noop_when_disabled() throws NoSuchFieldException, IllegalAccessException {
        setMaxMemory(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.startSweepThread();
        final var scheduler = TestUtils.getPrivateField(mm, "scheduler", java.util.concurrent.ScheduledExecutorService.class);
        assertNull(scheduler);
    }

    @Test
    public void test_stopSweepThread_safe_when_not_started() {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.stopSweepThread();
    }

    @Test
    public void test_startSweepThread_starts_scheduler_when_enabled() throws Exception {
        setMaxMemory(1024L);
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
    public void test_loadProfileFromAdmin_no_throw_when_empty() {
        final var mm = IocContainer.get(MemoryManagement.class);
        assertDoesNotThrow(mm::loadProfileFromAdmin);
    }

    @Test
    public void test_loadProfileFromAdmin_skipped_when_caching_disabled() throws Exception {
        setMaxMemory(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertDoesNotThrow(mm::loadProfileFromAdmin);
    }

    @Test
    public void test_loadProfileFromAdmin_reads_existing_records() throws Exception {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "userDb", "userColl", null);
        org.techhouse.ops.AdminOperationHelper.upsertCollectionUsage(
                new org.techhouse.bckg_ops.events.CollectionUsageEvent(AccessKind.COLLECTION, "userDb", "userColl", null,
                        System.currentTimeMillis()));
        TestUtils.setPrivateField(mm, "counters", new ConcurrentHashMap<>());
        mm.loadProfileFromAdmin();
        assertNotNull(mm.getCounter(AccessKind.COLLECTION, "userDb", "userColl", null));
    }

    @Test
    public void test_runEvictionSweep_handles_field_index_tier() throws Exception {
        setMaxMemory(1L);
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
        setMaxMemory(1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "coll", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        final var sweepRunning = TestUtils.getPrivateField(mm, "sweepRunning", java.util.concurrent.atomic.AtomicBoolean.class);
        sweepRunning.set(true);
        try {
            mm.runEvictionSweep();
            assertEquals(1, cache.listCacheableResources().size());
        } finally {
            sweepRunning.set(false);
        }
    }

    @Test
    public void test_runEvictionSweepSafely_swallows_exception() throws Exception {
        final var mm = IocContainer.get(MemoryManagement.class);
        final var method = MemoryManagement.class.getDeclaredMethod("runEvictionSweepSafely");
        method.setAccessible(true);
        method.invoke(mm);
    }

    @Test
    public void test_submitCleanupTask_invokes_background_manager() throws Exception {
        final var mm = IocContainer.get(MemoryManagement.class);
        final var method = MemoryManagement.class.getDeclaredMethod("submitCleanupTask");
        method.setAccessible(true);
        method.invoke(mm);
    }

    @Test
    public void test_tier_ordinal_covers_all_kinds() throws Exception {
        setMaxMemory(1L);
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

    @Test
    public void test_admission_check_admits_when_cache_has_room() throws Exception {
        setMaxMemory(1024L * 1024L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(AdmissionDecision.ADMIT, mm.admissionCheck(100L));
    }

    @Test
    public void test_admission_check_admits_when_unlimited() throws Exception {
        setMaxMemory(0L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(AdmissionDecision.ADMIT, mm.admissionCheck(1_000_000_000L));
    }

    @Test
    public void test_admission_check_rejects_when_caching_disabled() throws Exception {
        setMaxMemory(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(AdmissionDecision.REJECT, mm.admissionCheck(1L));
    }

    @Test
    public void test_admission_check_rejects_when_admission_would_exceed_cap() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "coll", 10);
        final var existing = cache.listCacheableResources().stream()
                .mapToLong(CacheableResource::estimatedSizeBytes).sum();
        setMaxMemory(existing + 100L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(AdmissionDecision.ADMIT, mm.admissionCheck(50L));
        assertEquals(AdmissionDecision.REJECT, mm.admissionCheck(200L));
    }

    @Test
    public void test_userCacheBytes_reflects_resources() throws Exception {
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(0L, mm.userCacheBytes());
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "coll", 10);
        assertTrue(mm.userCacheBytes() > 0L);
    }

    @Test
    public void test_usageRetentionMillis_is_24h() {
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(24L * 60L * 60L * 1000L, mm.usageRetentionMillis());
    }

    @Test
    public void test_ensureHeadroomForBytes_noop_under_budget() throws Exception {
        setMaxMemory(10L * 1024L * 1024L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.ensureHeadroomForBytes(100L);
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_ensureHeadroomForBytes_noop_when_disabled() throws Exception {
        setMaxMemory(-1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.ensureHeadroomForBytes(1_000_000L);
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_ensureHeadroomForBytes_noop_when_unlimited() throws Exception {
        setMaxMemory(0L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.ensureHeadroomForBytes(1_000_000L);
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_ensureHeadroomForBytes_evicts_lfu_to_make_room() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache(cache, "hot", 10);
        seedCollectionCache(cache, "cold", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        for (int i = 0; i < 5; i++) {
            mm.recordAccess(AccessKind.COLLECTION, "userDb", "hot", null);
        }
        mm.recordAccess(AccessKind.COLLECTION, "userDb", "cold", null);
        // Cap leaves no room: any incoming page must trigger eviction of the LFU resource.
        setMaxMemory(1L);
        mm.ensureHeadroomForBytes(1L);
        final var remaining = cache.listCacheableResources();
        assertTrue(remaining.stream().noneMatch(r -> r.collName().equals("cold")),
                "cold should be evicted first to make headroom");
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
