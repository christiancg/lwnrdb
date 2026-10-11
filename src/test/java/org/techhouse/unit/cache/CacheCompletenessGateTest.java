package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class CacheCompletenessGateTest extends CacheFixtureSupport {
    @Test
    public void test_a_dirty_read_does_not_admit_into_the_shared_cache() throws Exception {
        final var cache = new Cache();
        final var fs = IocContainer.get(FileSystem.class);
        TestUtils.createTestDatabaseAndCollection();
        for (int i = 1; i <= 2; i++) {
            final var o = new JsonObject();
            o.addProperty(Globals.PK_FIELD, "d" + i);
            final var e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, o);
            e.setPage(0L);
            fs.insertIntoCollection(e);
        }

        final var loaded = cache.getWholeCollection(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(2, loaded.size(), "the reader still gets a complete answer");
        assertNull(cache.userCache().getCachedCollection(TestGlobals.DB, TestGlobals.COLL),
                "a reader holding no collection lock must not publish its snapshot as the shared cache");
    }

    @Test
    public void test_a_locked_read_does_admit_into_the_shared_cache() throws Exception {
        final var cache = new Cache();
        final var fs = IocContainer.get(FileSystem.class);
        final var locks = IocContainer.get(org.techhouse.concurrency.ResourceLocking.class);
        TestUtils.createTestDatabaseAndCollection();
        final var o = new JsonObject();
        o.addProperty(Globals.PK_FIELD, "d1");
        final var e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, o);
        e.setPage(0L);
        fs.insertIntoCollection(e);

        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            cache.getWholeCollection(TestGlobals.DB, TestGlobals.COLL);
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }

        assertNotNull(cache.userCache().getCachedCollection(TestGlobals.DB, TestGlobals.COLL),
                "a properly locked scan still populates the shared cache");
    }

    @Test
    public void test_get_whole_collection_reloads_when_cache_incomplete_vs_pk_index() throws Exception {
        final var cache = new Cache();
        final var fs = IocContainer.get(FileSystem.class);
        TestUtils.createTestDatabaseAndCollection();

        for (int i = 1; i <= 2; i++) {
            final var o = new JsonObject();
            o.addProperty(Globals.PK_FIELD, "id" + i);
            final var e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, o);
            e.setPage(0L);
            fs.insertIntoCollection(e);
        }

        final var collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
        final var cachedObj = new JsonObject();
        cachedObj.addProperty(Globals.PK_FIELD, "id1");
        injectCachedEntry(collId, DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, cachedObj));
        // A lagging page count of 1 would previously have wrongly accepted the 1-entry cache as complete.
        final var pageEntry = new org.techhouse.data.admin.AdminPageEntry(TestGlobals.DB, TestGlobals.COLL, 0);
        pageEntry.setEntryCount(1);
        injectPages(collId, new ArrayList<>(List.of(pageEntry)));

        final var result = cache.getWholeCollection(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(2, result.size(), "PK index size (2) must force a reload of the incomplete cache");
        assertTrue(result.containsKey("id1"));
        assertTrue(result.containsKey("id2"));
    }

    @Test
    public void test_initializeStreamIfNecessary_skips_cache_when_disabled() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", -1L);
        try {
            Cache cache = IocContainer.get(Cache.class);
            TestUtils.createTestDatabaseAndCollection();
            final var stream = cache.initializeStreamIfNecessary(null, TestGlobals.DB, TestGlobals.COLL);
            assertNotNull(stream);
            stream.close();
            final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
            };
            final var userCache = IocContainer.get(org.techhouse.cache.UserCache.class);
            final var collectionMap = TestUtils.getPrivateField(userCache, "collectionMap", collType);
            assertFalse(collectionMap.containsKey(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_streamCollection_fully_cached_streams_from_cache() throws Exception {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);

        final var collId = Cache.getCollectionIdentifier("userDb", "c1");
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "id1");
        injectCachedEntry(collId, DbEntry.fromJsonObject("userDb", "c1", obj));
        injectPkIndex(collId,
                new ArrayList<>(List.of(new org.techhouse.data.PkIndexEntry("userDb", "c1", "id1", 0, 1, 0))));
        final var pageEntry = new org.techhouse.data.admin.AdminPageEntry("userDb", "c1", 0);
        pageEntry.setEntryCount(1);
        injectPages(collId, new ArrayList<>(List.of(pageEntry)));

        final List<DbEntry> result;
        try (var stream = cache.streamCollection("userDb", "c1")) {
            result = stream.toList();
        }

        assertEquals(1, result.size());
        assertEquals("id1", result.getFirst().get_id());
        verify(fsMock, never()).readWholeCollectionPage(anyString(), anyString(), anyLong());
        verify(fsMock, never()).streamEntries(anyString(), anyString());
    }

    @Test
    public void test_streamCollection_not_cached_streams_pages_with_headroom_check() throws Exception {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);
        org.techhouse.cache.MemoryManagement mmMock = mock(org.techhouse.cache.MemoryManagement.class);
        TestUtils.setPrivateField(cache, "memoryManagement", mmMock);

        final var collId = Cache.getCollectionIdentifier("userDb", "c1");
        final var pageEntry = new org.techhouse.data.admin.AdminPageEntry("userDb", "c1", 0);
        pageEntry.setEntryCount(2);
        pageEntry.setPageSize(1234L);
        injectPages(collId, new ArrayList<>(List.of(pageEntry)));

        final var page = new HashMap<String, DbEntry>();
        final var o1 = new JsonObject();
        o1.addProperty(Globals.PK_FIELD, "id1");
        final var o2 = new JsonObject();
        o2.addProperty(Globals.PK_FIELD, "id2");
        page.put("id1", DbEntry.fromJsonObject("userDb", "c1", o1));
        page.put("id2", DbEntry.fromJsonObject("userDb", "c1", o2));
        when(fsMock.readWholeCollectionPage("userDb", "c1", 0L)).thenReturn(page);

        final List<DbEntry> result;
        try (var stream = cache.streamCollection("userDb", "c1")) {
            result = stream.toList();
        }

        assertEquals(2, result.size());
        verify(mmMock).ensureHeadroomForBytes(1234L);
        verify(fsMock).readWholeCollectionPage("userDb", "c1", 0L);
    }

    @Test
    public void test_streamCollection_no_page_metadata_falls_back_to_stream_entries() throws Exception {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);

        final var o1 = new JsonObject();
        o1.addProperty(Globals.PK_FIELD, "id1");
        when(fsMock.streamEntries("userDb", "c1")).thenReturn(Stream.of(DbEntry.fromJsonObject("userDb", "c1", o1)));

        final List<DbEntry> result;
        try (var stream = cache.streamCollection("userDb", "c1")) {
            result = stream.toList();
        }

        assertEquals(1, result.size());
        verify(fsMock).streamEntries("userDb", "c1");
    }

    @Test
    public void test_streamCollection_caching_disabled_uses_disk_path() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", -1L);
        try {
            Cache cache = new Cache();
            FileSystem fsMock = mock(FileSystem.class);
            TestUtils.setPrivateField(cache, "fs", fsMock);
            org.techhouse.cache.MemoryManagement mmMock = mock(org.techhouse.cache.MemoryManagement.class);
            TestUtils.setPrivateField(cache, "memoryManagement", mmMock);

            final var collId = Cache.getCollectionIdentifier("userDb", "c1");
            final var cachedObj = new JsonObject();
            cachedObj.addProperty(Globals.PK_FIELD, "stale");
            injectCachedEntry(collId, DbEntry.fromJsonObject("userDb", "c1", cachedObj));
            final var pageEntry = new org.techhouse.data.admin.AdminPageEntry("userDb", "c1", 0);
            pageEntry.setEntryCount(1);
            injectPages(collId, new ArrayList<>(List.of(pageEntry)));
            final var page = new HashMap<String, DbEntry>();
            final var o1 = new JsonObject();
            o1.addProperty(Globals.PK_FIELD, "fromDisk");
            page.put("fromDisk", DbEntry.fromJsonObject("userDb", "c1", o1));
            when(fsMock.readWholeCollectionPage("userDb", "c1", 0L)).thenReturn(page);

            final List<DbEntry> result;
            try (var stream = cache.streamCollection("userDb", "c1")) {
                result = stream.toList();
            }

            assertEquals(1, result.size());
            assertEquals("fromDisk", result.getFirst().get_id());
            verify(fsMock).readWholeCollectionPage("userDb", "c1", 0L);
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_the_completeness_gate_rejects_a_superset() throws Exception {
        final var cache = new Cache();
        final var dbName = "supersetDb";
        final var collName = "supersetColl";
        final var live = new DbEntry();
        live.set_id("live");
        final var deleted = new DbEntry();
        deleted.set_id("deleted");
        cache.addEntryToCache(dbName, collName, live);
        cache.addEntryToCache(dbName, collName, deleted);
        cache.getPkIndexAndLoadIfNecessary(dbName, collName)
                .add(new org.techhouse.data.PkIndexEntry(dbName, collName, "live", 0, 1, 0));

        final var result = cache.getWholeCollection(dbName, collName);

        assertFalse(result.containsKey("deleted"),
                "a cached map holding more documents than the PK index is not complete, it is poisoned: serving"
                        + " it returns a deleted document from every later fully locked scan");
    }

    @Test
    public void test_stream_rejects_a_cached_map_larger_than_the_pk_index() throws Exception {
        final var cache = new Cache();
        final var fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);
        when(fsMock.streamEntries(anyString(), anyString())).thenReturn(java.util.stream.Stream.of());

        final var collId = Cache.getCollectionIdentifier("supersetDb", "c1");
        final var live = new JsonObject();
        live.addProperty(Globals.PK_FIELD, "live");
        final var deleted = new JsonObject();
        deleted.addProperty(Globals.PK_FIELD, "deleted");
        injectCachedEntry(collId, DbEntry.fromJsonObject("supersetDb", "c1", live));
        injectCachedEntry(collId, DbEntry.fromJsonObject("supersetDb", "c1", deleted));
        injectPkIndex(collId,
                new ArrayList<>(List.of(new org.techhouse.data.PkIndexEntry("supersetDb", "c1", "live", 0, 1, 0))));

        final List<DbEntry> result;
        try (var stream = cache.streamCollection("supersetDb", "c1")) {
            result = stream.toList();
        }

        assertTrue(result.stream().noneMatch(e -> "deleted".equals(e.get_id())),
                "a cached map holding more documents than the PK index is not complete, it is poisoned: serving it"
                        + " returns a deleted document from every later fully locked scan");
    }
}
