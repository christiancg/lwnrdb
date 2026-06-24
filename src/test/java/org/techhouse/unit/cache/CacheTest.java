package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

public class CacheTest {

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_concatenates_dbname_and_coll_name_correctly() {
        String dbName = "testDb";
        String collName = "testColl";
        String expected = "testDb" + Globals.COLL_IDENTIFIER_SEPARATOR + "testColl";
        String result = Cache.getCollectionIdentifier(dbName, collName);
        assertEquals(expected, result);
    }

    @Test
    public void test_handles_empty_dbname_and_coll_name_gracefully() {
        String dbName = "a";
        String collName = "a";
        String expected = "a" + Globals.COLL_IDENTIFIER_SEPARATOR + "a";
        String result = Cache.getCollectionIdentifier(dbName, collName);
        assertEquals(expected, result);
    }

    // Returns correct identifier for standard field names and types
    @Test
    public void test_returns_correct_identifier_for_standard_field_names_and_types() {
        String fieldName = "username";
        Class<?> fieldType = String.class;
        String expected = "username|String";
        String actual = Cache.getIndexIdentifier(fieldName, fieldType);
        assertEquals(expected, actual);
    }

    @Test
    public void test_handles_empty_field_name_correctly() {
        String fieldName = "";
        Class<?> fieldType = Integer.class;
        String expected = "|Integer";
        String actual = Cache.getIndexIdentifier(fieldName, fieldType);
        assertEquals(expected, actual);
    }

    // The facade composes the AdminCache and UserCache IoC singletons
    @Test
    public void test_facade_constructs_subcaches_from_ioc() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        final var adminCache = TestUtils.getPrivateField(cache, "adminCache", org.techhouse.cache.AdminCache.class);
        final var userCache = TestUtils.getPrivateField(cache, "userCache", org.techhouse.cache.UserCache.class);
        assertNotNull(adminCache);
        assertNotNull(userCache);
        assertSame(IocContainer.get(org.techhouse.cache.AdminCache.class), adminCache);
        assertSame(IocContainer.get(org.techhouse.cache.UserCache.class), userCache);
    }

    // A user-cache method routed through the facade lands in the UserCache store
    @Test
    public void test_facade_delegates_user_method() {
        Cache cache = new Cache();
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "u1");
        cache.addEntryToCache("userDb", "c1", DbEntry.fromJsonObject("userDb", "c1", obj));
        final var userCache = IocContainer.get(org.techhouse.cache.UserCache.class);
        final var cached = userCache.getCachedCollection("userDb", "c1");
        assertNotNull(cached);
        assertTrue(cached.containsKey("u1"));
    }

    // An admin-cache method routed through the facade lands in the AdminCache store
    @Test
    public void test_facade_delegates_admin_method() {
        Cache cache = new Cache();
        cache.updatePageSizeInMemory("userDb", "c1", 0L, 100L);
        final var adminCache = IocContainer.get(org.techhouse.cache.AdminCache.class);
        assertNotNull(adminCache.getAdminPageEntries("userDb", "c1"));
        assertEquals(0L, cache.getAdminPageEntry("userDb", "c1", 0L).getPage());
    }

    // Returns the whole collection from the cache if it exists and is complete
    @Test
    public void test_returns_whole_collection_from_cache_if_exists_and_complete()
            throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String dbName = "testDb";
        String collName = "testColl";
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        final var pageEntry = new org.techhouse.data.admin.AdminPageEntry(dbName, collName, 0);
        pageEntry.setEntryCount(2);
        final var pageList = new java.util.ArrayList<org.techhouse.data.admin.AdminPageEntry>();
        pageList.add(pageEntry);

        final var typePages = new ReflectionUtils.TypeToken<Map<String, List<org.techhouse.data.admin.AdminPageEntry>>>() {
        };
        final var adminCache = IocContainer.get(org.techhouse.cache.AdminCache.class);
        final var pagesMap = TestUtils.getPrivateField(adminCache, "pages", typePages);
        pagesMap.put(collectionIdentifier, pageList);

        DbEntry entry1 = new DbEntry();
        entry1.set_id("1");
        DbEntry entry2 = new DbEntry();
        entry2.set_id("2");

        Map<String, DbEntry> wholeCollection = new HashMap<>();
        wholeCollection.put("1", entry1);
        wholeCollection.put("2", entry2);

        final var typeCollMap = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var userCache = IocContainer.get(org.techhouse.cache.UserCache.class);
        final var collectionMap = TestUtils.getPrivateField(userCache, "collectionMap", typeCollMap);
        collectionMap.put(collectionIdentifier, wholeCollection);

        Map<String, DbEntry> result = cache.getWholeCollection(dbName, collName);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("1"));
        assertTrue(result.containsKey("2"));
    }

    // Handles IOException when reading the collection from the file system
    @Test
    public void test_handles_ioexception_when_reading_collection_from_file_system()
            throws NoSuchFieldException, IllegalAccessException, IOException {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = Cache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        String dbName = "testDb";
        String collName = "testColl";

        when(fsMock.streamPages(dbName, collName)).thenThrow(new IOException("File not found"));

        assertThrows(RuntimeException.class, () -> cache.getWholeCollection(dbName, collName));
    }

    // Returns the provided resultStream if it is not null
    @Test
    public void test_returns_provided_resultStream_if_not_null() throws IOException {
        Cache cache = new Cache();
        Stream<JsonObject> mockStream = Stream.of(new JsonObject());
        Stream<JsonObject> result = cache.initializeStreamIfNecessary(mockStream, "dbName", "collName");
        assertEquals(mockStream, result);
    }

    // Handles IOException when reading the collection from the file system
    @Test
    public void test_handles_ioexception_when_reading_collection()
            throws NoSuchFieldException, IllegalAccessException, IOException {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = Cache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        when(fsMock.streamEntries(anyString(), anyString())).thenThrow(IOException.class);
        assertThrows(IOException.class, () -> cache.initializeStreamIfNecessary(null, "dbName", "collName"));
    }

    @Test
    public void test_get_whole_collection_returns_cached_when_pages_metadata_missing() {
        Cache cache = new Cache();
        String dbName = "myDb";
        String collName = "myColl";
        DbEntry e = new DbEntry();
        e.set_id("1");
        cache.addEntryToCache(dbName, collName, e);

        Map<String, DbEntry> result = cache.getWholeCollection(dbName, collName);
        assertEquals(1, result.size());
    }

    // Regression for the completeness gate: it must use the synchronous PK index size, not the
    // background-updated (lagging) admin page entry count. A cache missing a freshly-committed
    // document is reloaded from disk even when a stale low page count would have accepted it.
    @Test
    public void test_get_whole_collection_reloads_when_cache_incomplete_vs_pk_index() throws Exception {
        final var cache = new Cache();
        final var fs = IocContainer.get(FileSystem.class);
        TestUtils.createTestDatabaseAndCollection();

        // Two committed documents: insertIntoCollection writes the .dat pages and the synchronous PK index.
        for (int i = 1; i <= 2; i++) {
            final var o = new JsonObject();
            o.addProperty(Globals.PK_FIELD, "id" + i);
            final var e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, o);
            e.setPage(0L);
            fs.insertIntoCollection(e);
        }

        // Cache holds only one of the two docs (e.g. the second insert was not admitted under memory pressure).
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
            // Create the collection on disk so readWholeCollection works.
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

    // ── getEntriesByIds / streamCollection (page-streaming read path) ─────────

    private static void injectCachedEntry(String collId, DbEntry entry)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        // Document cache lives on the UserCache singleton, which the facade reads through.
        final var userCache = IocContainer.get(org.techhouse.cache.UserCache.class);
        final var collectionMap = TestUtils.getPrivateField(userCache, "collectionMap", type);
        final var inner = collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>());
        inner.put(entry.get_id(), entry);
    }

    private static void injectPages(String collId, List<org.techhouse.data.admin.AdminPageEntry> pageList)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, List<org.techhouse.data.admin.AdminPageEntry>>>() {
        };
        // Page metadata lives on the AdminCache singleton, which the facade reads through.
        final var adminCache = IocContainer.get(org.techhouse.cache.AdminCache.class);
        final var pages = TestUtils.getPrivateField(adminCache, "pages", type);
        pages.put(collId, pageList);
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
            // Even though an entry is cached, caching-disabled must bypass the cache branch.
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

    // shiftPkPositionsAfterCompaction routes to the admin cache for the admin database and to the user
    // cache otherwise.
    @Test
    public void test_shift_pk_positions_routes_admin_vs_user() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        final var adminMock = mock(org.techhouse.cache.AdminCache.class);
        final var userMock = mock(org.techhouse.cache.UserCache.class);
        TestUtils.setPrivateField(cache, "adminCache", adminMock);
        TestUtils.setPrivateField(cache, "userCache", userMock);

        cache.shiftPkPositionsAfterCompaction(
                new org.techhouse.fs.PkCompaction(Globals.ADMIN_DB_NAME, "collections", 0, 10, 5));
        cache.shiftPkPositionsAfterCompaction(new org.techhouse.fs.PkCompaction("userDb", "userColl", 1, 20, 7));
        // A null compaction (no survivor moved) is a no-op.
        cache.shiftPkPositionsAfterCompaction(null);

        verify(adminMock).shiftPkPositionsAfterCompaction("collections", 0, 10, 5);
        verify(userMock).shiftPkPositionsAfterCompaction("userDb", "userColl", 1, 20, 7);
        verifyNoMoreInteractions(adminMock, userMock);
    }
}
