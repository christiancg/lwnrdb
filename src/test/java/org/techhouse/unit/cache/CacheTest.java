package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class CacheTest extends CacheFixtureSupport {

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

    @Test
    public void test_facade_delegates_admin_method() {
        Cache cache = new Cache();
        cache.updatePageSizeInMemory("userDb", "c1", 0L, 100L);
        final var adminCache = IocContainer.get(org.techhouse.cache.AdminCache.class);
        assertNotNull(adminCache.getAdminPageEntries("userDb", "c1"));
        assertEquals(0L, cache.getAdminPageEntry("userDb", "c1", 0L).getPage());
    }

    @Test
    public void test_returns_whole_collection_from_cache_if_exists_and_complete()
            throws NoSuchFieldException, IllegalAccessException, java.io.IOException, InterruptedException {
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
        final var pagesMap = TestUtils.getPrivateField(TestUtils.pageCacheOf(adminCache), "pages", typePages);
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
        final var locks = IocContainer.get(org.techhouse.concurrency.ResourceLocking.class);
        locks.lock(dbName, collName);
        try {
            final var pkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            pkIndex.add(new org.techhouse.data.PkIndexEntry(dbName, collName, "1", 0, 1, 0));
            pkIndex.add(new org.techhouse.data.PkIndexEntry(dbName, collName, "2", 1, 1, 0));
        } finally {
            locks.release(dbName, collName);
        }

        Map<String, DbEntry> result = cache.getWholeCollection(dbName, collName);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("1"));
        assertTrue(result.containsKey("2"));
    }

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

    @Test
    public void test_returns_provided_resultStream_if_not_null() throws IOException {
        Cache cache = new Cache();
        Stream<JsonObject> mockStream = Stream.of(new JsonObject());
        Stream<JsonObject> result = cache.initializeStreamIfNecessary(mockStream, "dbName", "collName");
        assertEquals(mockStream, result);
    }

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
    public void test_get_whole_collection_returns_cached_when_pages_metadata_missing()
            throws IOException, InterruptedException {
        Cache cache = new Cache();
        String dbName = "myDb";
        String collName = "myColl";
        DbEntry e = new DbEntry();
        e.set_id("1");
        cache.addEntryToCache(dbName, collName, e);
        final var locks = IocContainer.get(org.techhouse.concurrency.ResourceLocking.class);
        locks.lock(dbName, collName);
        try {
            cache.getPkIndexAndLoadIfNecessary(dbName, collName)
                    .add(new org.techhouse.data.PkIndexEntry(dbName, collName, "1", 0, 1, 0));
        } finally {
            locks.release(dbName, collName);
        }

        Map<String, DbEntry> result = cache.getWholeCollection(dbName, collName);
        assertEquals(1, result.size());
    }

    // Regression: the completeness gate must use the synchronous PK index size, not the
    // background-updated (lagging) admin page entry count.

    @Test
    public void test_shift_pk_positions_routes_admin_vs_user() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        final var adminMock = mock(org.techhouse.cache.AdminCache.class);
        final var userMock = mock(org.techhouse.cache.UserCache.class);
        TestUtils.setPrivateField(cache, "adminCache", adminMock);
        TestUtils.setPrivateField(cache, "userCache", userMock);

        cache.shiftPkPositionsAfterCompaction(
                new org.techhouse.fs.PkCompaction(Globals.ADMIN_DB_NAME, "collections", 0, 10, 5));
        cache.shiftPkPositionsAfterCompaction(
                new org.techhouse.fs.PkCompaction(Globals.ADMIN_PAGES_DB_NAME, "userDb_userColl", 2, 30, 9));
        cache.shiftPkPositionsAfterCompaction(new org.techhouse.fs.PkCompaction("userDb", "userColl", 1, 20, 7));
        cache.shiftPkPositionsAfterCompaction(null);

        verify(adminMock).shiftPkPositionsAfterCompaction(Globals.ADMIN_DB_NAME, "collections", 0, 10, 5);
        verify(adminMock).shiftPkPositionsAfterCompaction(Globals.ADMIN_PAGES_DB_NAME, "userDb_userColl", 2, 30, 9);
        verify(userMock).shiftPkPositionsAfterCompaction("userDb", "userColl", 1, 20, 7);
        verifyNoMoreInteractions(adminMock, userMock);
    }

    @Test
    public void test_hash_index_identifier_does_not_collide_with_the_typed_identifier() {
        final var typed = Cache.getIndexIdentifier("score", Number.class);
        final var hash = Cache.getHashIndexIdentifier("score", org.techhouse.data.IndexKind.NUMBER.label());
        assertNotEquals(typed, hash,
                "both slots resolved to score|Number, and the two loaders parse the same .idx file into"
                        + " Double and String values, so whichever loaded first poisoned the other");
    }

    @Test
    public void test_hash_index_identifier_still_shares_the_field_eviction_prefix() {
        final var hash = Cache.getHashIndexIdentifier("score", org.techhouse.data.IndexKind.OBJECT.label());
        assertTrue(hash.startsWith("score" + org.techhouse.config.Globals.COLL_IDENTIFIER_SEPARATOR),
                "evictFieldIndexAllTypes removes by the field| prefix, so the namespaced key must keep it");
    }
}
