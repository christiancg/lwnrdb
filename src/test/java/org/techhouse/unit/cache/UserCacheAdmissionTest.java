package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class UserCacheAdmissionTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_addEntryToCache_skips_when_caching_disabled() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", -1L);
        try {
            UserCache cache = IocContainer.get(UserCache.class);
            final var obj = new JsonObject();
            obj.addProperty(Globals.PK_FIELD, "id1");
            cache.addEntryToCache("userDb", "c1", DbEntry.fromJsonObject("userDb", "c1", obj));
            final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
            };
            final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
            assertFalse(collectionMap.containsKey(Cache.getCollectionIdentifier("userDb", "c1")));
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_addEntryToCache_caches_admin_even_when_disabled() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", -1L);
        try {
            UserCache cache = IocContainer.get(UserCache.class);
            final var obj = new JsonObject();
            obj.addProperty(Globals.PK_FIELD, "id1");
            cache.addEntryToCache(Globals.ADMIN_DB_NAME, "databases",
                    DbEntry.fromJsonObject(Globals.ADMIN_DB_NAME, "databases", obj));
            final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
            };
            final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
            assertTrue(collectionMap.containsKey(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases")));
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_addEntriesToCache_skips_when_caching_disabled() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", -1L);
        try {
            UserCache cache = IocContainer.get(UserCache.class);
            final var obj = new JsonObject();
            obj.addProperty(Globals.PK_FIELD, "id1");
            cache.addEntriesToCache("userDb", "c1", List.of(DbEntry.fromJsonObject("userDb", "c1", obj)));
            final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
            };
            final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
            assertFalse(collectionMap.containsKey(Cache.getCollectionIdentifier("userDb", "c1")));
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_addEntryToCache_refuses_when_over_cap() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", 1L);
        try {
            UserCache cache = IocContainer.get(UserCache.class);
            final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
            };
            final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
            collectionMap.clear();
            final var obj = new JsonObject();
            obj.addProperty(Globals.PK_FIELD, "id1");
            obj.addProperty("v", "x".repeat(128));
            cache.addEntryToCache("userDb", "c1", DbEntry.fromJsonObject("userDb", "c1", obj));
            assertFalse(collectionMap.containsKey(Cache.getCollectionIdentifier("userDb", "c1")),
                    "entry should not be admitted when it exceeds the cap");
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_addEntryToCache_admits_when_within_cap() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", 1024L * 1024L);
        try {
            UserCache cache = IocContainer.get(UserCache.class);
            final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
            };
            final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
            collectionMap.clear();
            final var obj = new JsonObject();
            obj.addProperty(Globals.PK_FIELD, "id1");
            cache.addEntryToCache("userDb", "c1", DbEntry.fromJsonObject("userDb", "c1", obj));
            assertTrue(collectionMap.containsKey(Cache.getCollectionIdentifier("userDb", "c1")),
                    "small entry should be admitted under a generous cap");
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_addEntriesToCache_refuses_when_total_over_cap() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", 1L);
        try {
            UserCache cache = IocContainer.get(UserCache.class);
            final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
            };
            final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
            collectionMap.clear();
            final var obj1 = new JsonObject();
            obj1.addProperty(Globals.PK_FIELD, "id1");
            obj1.addProperty("v", "x".repeat(128));
            final var obj2 = new JsonObject();
            obj2.addProperty(Globals.PK_FIELD, "id2");
            obj2.addProperty("v", "y".repeat(128));
            cache.addEntriesToCache("userDb", "c1", List.of(DbEntry.fromJsonObject("userDb", "c1", obj1),
                    DbEntry.fromJsonObject("userDb", "c1", obj2)));
            assertFalse(collectionMap.containsKey(Cache.getCollectionIdentifier("userDb", "c1")));
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_getById_skips_cache_when_disabled() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", -1L);
        try {
            UserCache cache = IocContainer.get(UserCache.class);
            FileSystem fsMock = mock(FileSystem.class);
            final var fsField = UserCache.class.getDeclaredField("fs");
            fsField.setAccessible(true);
            final var originalFs = fsField.get(cache);
            fsField.set(cache, fsMock);
            try {
                final var stub = new DbEntry();
                stub.set_id("id1");
                final var pk = new PkIndexEntry("userDb", "c1", "id1", 0, 10, 0);
                when(fsMock.getById(pk)).thenReturn(stub);
                final var result = cache.getById("userDb", "c1", pk);
                assertEquals("id1", result.get_id());
                final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
                };
                final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
                assertFalse(collectionMap.containsKey(Cache.getCollectionIdentifier("userDb", "c1")));
            } finally {
                fsField.set(cache, originalFs);
            }
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_getById_admission_check_when_over_cap() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", 1L);
        try {
            UserCache cache = IocContainer.get(UserCache.class);
            FileSystem fsMock = mock(FileSystem.class);
            final var fsField = UserCache.class.getDeclaredField("fs");
            fsField.setAccessible(true);
            final var originalFs = fsField.get(cache);
            fsField.set(cache, fsMock);
            try {
                final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
                };
                final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
                collectionMap.clear();
                final var obj = new JsonObject();
                obj.addProperty(Globals.PK_FIELD, "id1");
                obj.addProperty("v", "x".repeat(128));
                final var entry = DbEntry.fromJsonObject("userDb", "c1", obj);
                final var pk = new PkIndexEntry("userDb", "c1", "id1", 0, 10, 0);
                when(fsMock.getById(pk)).thenReturn(entry);
                final var result = cache.getById("userDb", "c1", pk);
                assertEquals("id1", result.get_id());
                final var collection = collectionMap.get(Cache.getCollectionIdentifier("userDb", "c1"));
                assertTrue(collection == null || !collection.containsKey("id1"),
                        "oversized entry must not be admitted to the cache");
            } finally {
                fsField.set(cache, originalFs);
            }
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_shouldCache_refuses_new_entry_when_over_cap() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", 100L);
        try {
            UserCache cache = IocContainer.get(UserCache.class);
            final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
            };
            final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
            collectionMap.clear();
            // Seed an existing cached collection that already exceeds the cap (~217B > 100B).
            final var existing = new java.util.concurrent.ConcurrentHashMap<String, DbEntry>();
            final var seed = new JsonObject();
            seed.addProperty(Globals.PK_FIELD, "seed");
            seed.addProperty("v", "z".repeat(200));
            existing.put("seed", DbEntry.fromJsonObject("userDb", "old", seed));
            collectionMap.put(Cache.getCollectionIdentifier("userDb", "old"), existing);
            // Admission check is pure: it does not run a sweep — the async sweep thread does that.
            // So "new" is refused while the cache is over cap.
            final var obj = new JsonObject();
            obj.addProperty(Globals.PK_FIELD, "id1");
            cache.addEntryToCache("userDb", "new", DbEntry.fromJsonObject("userDb", "new", obj));
            assertTrue(collectionMap.containsKey(Cache.getCollectionIdentifier("userDb", "old")));
            assertFalse(collectionMap.containsKey(Cache.getCollectionIdentifier("userDb", "new")));
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    private static void injectPkIndex(UserCache cache, String collId, List<PkIndexEntry> entries)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", type);
        pkIndexMap.put(collId, new ArrayList<>(entries));
    }

    @Test
    public void test_getEntriesByIds_admission_rejected_does_not_populate_cache() throws Exception {
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);
        final var config = Configuration.getInstance();
        final var savedMaxMemory = TestUtils.getPrivateField(config, "maxMemoryBytes", Long.class);
        TestUtils.setPrivateField(config, "maxMemoryBytes", 1L);

        final var collId = Cache.getCollectionIdentifier("userDb", "c1");
        injectPkIndex(cache, collId, List.of(new PkIndexEntry("userDb", "c1", "id1", 0, 50, 0)));
        final var readObj = new JsonObject();
        readObj.addProperty(Globals.PK_FIELD, "id1");
        when(fsMock.getByIndexEntries(anyList())).thenReturn(List.of(DbEntry.fromJsonObject("userDb", "c1", readObj)));

        final List<DbEntry> result;
        try {
            result = cache.getEntriesByIds("userDb", "c1", Set.of("id1"));
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", savedMaxMemory);
        }

        assertEquals(1, result.size());
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", type);
        assertFalse(collectionMap.containsKey(collId), "rejected admission must not populate the cache");
    }

    @Test
    public void test_getEntriesByIds_caching_disabled_reads_directly() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", -1L);
        try {
            UserCache cache = new UserCache();
            FileSystem fsMock = mock(FileSystem.class);
            TestUtils.setPrivateField(cache, "fs", fsMock);

            final var collId = Cache.getCollectionIdentifier("userDb", "c1");
            injectPkIndex(cache, collId, List.of(new PkIndexEntry("userDb", "c1", "id1", 0, 50, 0)));
            final var readObj = new JsonObject();
            readObj.addProperty(Globals.PK_FIELD, "id1");
            when(fsMock.getByIndexEntries(anyList()))
                    .thenReturn(List.of(DbEntry.fromJsonObject("userDb", "c1", readObj)));

            final var result = cache.getEntriesByIds("userDb", "c1", Set.of("id1"));

            assertEquals(1, result.size());
            verify(fsMock).getByIndexEntries(anyList());
            final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
            };
            final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", type);
            assertFalse(collectionMap.containsKey(collId), "caching disabled must not populate the cache");
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    private static DbEntry cacheEntry(String id, int v) {
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, id);
        obj.addProperty("v", v);
        return DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
    }

    @Test
    public void test_addEntryToCache_refreshes_resident_document_despite_admission_reject() throws Exception {
        final var cache = IocContainer.get(UserCache.class);
        final var config = Configuration.getInstance();
        final var originalMax = config.getMaxMemoryBytes();
        final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        TestUtils.getPrivateField(cache, "collectionMap", collType).clear();
        try {
            TestUtils.setPrivateField(config, "maxMemoryBytes", 100_000_000L);
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, cacheEntry("a", 1));
            TestUtils.setPrivateField(config, "maxMemoryBytes", 1L);
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, cacheEntry("a", 2));
            final var refreshed = cache.getCachedCollection(TestGlobals.DB, TestGlobals.COLL).get("a");
            assertEquals(2, refreshed.getData().get("v").asJsonNumber().asInteger());
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, cacheEntry("b", 9));
            assertNull(cache.getCachedCollection(TestGlobals.DB, TestGlobals.COLL).get("b"));
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", originalMax);
        }
    }

    @Test
    public void test_addEntriesToCache_refreshes_resident_and_gates_new_under_reject() throws Exception {
        final var cache = IocContainer.get(UserCache.class);
        final var config = Configuration.getInstance();
        final var originalMax = config.getMaxMemoryBytes();
        final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        TestUtils.getPrivateField(cache, "collectionMap", collType).clear();
        try {
            TestUtils.setPrivateField(config, "maxMemoryBytes", 100_000_000L);
            cache.addEntriesToCache(TestGlobals.DB, TestGlobals.COLL, List.of(cacheEntry("a", 1)));
            TestUtils.setPrivateField(config, "maxMemoryBytes", 1L);
            cache.addEntriesToCache(TestGlobals.DB, TestGlobals.COLL, List.of(cacheEntry("a", 2), cacheEntry("b", 9)));
            final var cached = cache.getCachedCollection(TestGlobals.DB, TestGlobals.COLL);
            assertEquals(2, cached.get("a").getData().get("v").asJsonNumber().asInteger());
            assertNull(cached.get("b"));
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", originalMax);
        }
    }
}
