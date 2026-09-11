package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class UserCacheEntryTest {
    // Mockito mocks skip field initializers, so the real getIdsFromIndex (called via thenCallRealMethod)
    // would see a null index lock. Inject a real ResourceLocking so the read lock can be acquired.
    private static void injectRealLocking(UserCache mock) {
        try {
            final var rlField = UserCache.class.getDeclaredField("rl");
            rlField.setAccessible(true);
            rlField.set(mock, new ResourceLocking());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Retrieves IDs for Double values using the appropriate index
    @Test
    public void test_retrieves_ids_for_double_values() throws IOException {
        // Arrange
        var cache = mock(UserCache.class);
        injectRealLocking(cache);
        var dbName = "testDB";
        var collName = "testCollection";
        var fieldName = "testField";
        var operator = new FieldOperator(FieldOperatorType.EQUALS, fieldName, new JsonNumber(10.0));
        var value = 10.0;

        var indexEntries = List.of(new FieldIndexEntry<Number>(dbName, collName, value, Set.of("id1", "id2")));
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class)).thenReturn(indexEntries);

        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, value)).thenCallRealMethod();

        // Act
        var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, value);

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("id1"));
        assertTrue(result.contains("id2"));
    }

    // Handles null values in JsonArray gracefully
    @Test
    public void test_handles_null_values_in_json_array() throws IOException {
        // Arrange
        var cache = new UserCache();
        var dbName = "testDB";
        var collName = "testCollection";
        var fieldName = "testField";
        var operator = new FieldOperator(FieldOperatorType.IN, fieldName, new JsonArray());
        var jsonArray = new JsonArray();
        jsonArray.add((JsonBaseElement) null);

        // Act
        var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, jsonArray);

        // Assert
        assertNull(result);
    }

    // Retrieves IDs for Boolean values using the appropriate index
    @Test
    public void test_retrieves_ids_for_boolean_values() throws IOException {
        var cache = mock(UserCache.class);
        injectRealLocking(cache);
        // Setup
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";
        FieldOperator operator = new FieldOperator(FieldOperatorType.EQUALS, fieldName, new JsonBoolean(true));

        // Mocking
        List<FieldIndexEntry<Boolean>> booleanIndex = new ArrayList<>();
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Boolean.class))
                .thenReturn(booleanIndex);

        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, true)).thenCallRealMethod();

        // Execution
        Set<String> result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, true);

        // Assertions
        assertTrue(result.isEmpty());
    }

    // Retrieves IDs for String values using the appropriate index
    @Test
    public void test_retrieves_ids_for_string_values() throws IOException {
        var cache = mock(UserCache.class);
        injectRealLocking(cache);
        // Setup
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";
        FieldOperator operator = new FieldOperator(FieldOperatorType.EQUALS, fieldName, new JsonString("test"));

        // Mocking
        List<FieldIndexEntry<String>> stringIndex = new ArrayList<>();
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, String.class)).thenReturn(stringIndex);

        // Execution
        Set<String> result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, "test");

        // Assertions
        assertTrue(result.isEmpty());
    }

    // Adding a new entry to an empty cache
    @Test
    public void test_add_entry_to_empty_cache() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(Globals.PK_FIELD, "123");
        DbEntry entry = DbEntry.fromJsonObject(dbName, collName, jsonObject);

        cache.addEntryToCache(dbName, collName, entry);

        final var typeCollMap = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeCollMap);

        String collId = Cache.getCollectionIdentifier(dbName, collName);
        assertTrue(collectionMap.containsKey(collId));
        assertTrue(collectionMap.get(collId).containsKey("123"));
    }

    // Adding an entry with a null ID
    @Test
    public void test_add_entry_with_null_id() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(Globals.PK_FIELD, "123");
        DbEntry entry = DbEntry.fromJsonObject(dbName, collName, jsonObject);

        cache.addEntryToCache(dbName, collName, entry);

        final var typeCollMap = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeCollMap);

        String collId = Cache.getCollectionIdentifier(dbName, collName);
        assertTrue(collectionMap.containsKey(collId));
        assertNotNull(entry.get_id());
        assertTrue(collectionMap.get(collId).containsKey(entry.get_id()));
    }

    // Adding entries to an empty cache
    @Test
    public void test_adding_entries_to_empty_cache() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        var jsonObject1 = new JsonObject();
        jsonObject1.addProperty(Globals.PK_FIELD, "1");
        var jsonObject2 = new JsonObject();
        jsonObject2.addProperty(Globals.PK_FIELD, "2");
        List<DbEntry> entries = List.of(DbEntry.fromJsonObject(dbName, collName, jsonObject1),
                DbEntry.fromJsonObject(dbName, collName, jsonObject2));

        cache.addEntriesToCache(dbName, collName, entries);

        final var typeCollMap = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeCollMap);

        String collId = Cache.getCollectionIdentifier(dbName, collName);
        assertEquals(2, collectionMap.get(collId).size());
        assertTrue(collectionMap.get(collId).containsKey("1"));
        assertTrue(collectionMap.get(collId).containsKey("2"));
    }

    // Adding entries with duplicate IDs (last one wins, matching upsert semantics)
    @Test
    public void test_adding_entries_with_duplicate_ids() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";

        var jsonObject1 = new JsonObject();
        jsonObject1.addProperty(Globals.PK_FIELD, "1");
        var jsonObject2 = new JsonObject();
        jsonObject2.addProperty(Globals.PK_FIELD, "1");

        List<DbEntry> entries = List.of(DbEntry.fromJsonObject(dbName, collName, jsonObject1),
                DbEntry.fromJsonObject(dbName, collName, jsonObject2));

        cache.addEntriesToCache(dbName, collName, entries);

        final var typeCollMap = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeCollMap);

        String collId = Cache.getCollectionIdentifier(dbName, collName);
        assertEquals(1, collectionMap.get(collId).size());
        assertTrue(collectionMap.get(collId).containsKey("1"));
    }

    // Retrieves an entry from the cache if it exists
    @Test
    public void retrieves_entry_from_cache_if_exists() throws Exception {
        // Arrange
        String dbName = "testDb";
        String collName = "testColl";
        PkIndexEntry idxEntry = new PkIndexEntry(dbName, collName, "testValue", 0, 100, 0);
        UserCache cache = new UserCache();
        DbEntry expectedEntry = new DbEntry();
        expectedEntry.setDatabaseName(dbName);
        expectedEntry.setCollectionName(collName);
        expectedEntry.set_id("testValue");

        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        final var typeCollMap = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeCollMap);
        collectionMap.putIfAbsent(collectionIdentifier, new ConcurrentHashMap<>());
        collectionMap.get(collectionIdentifier).put("testValue", expectedEntry);

        // Act
        DbEntry result = cache.getById(dbName, collName, idxEntry);

        // Assert
        assertEquals(expectedEntry, result);
    }

    @Test
    public void test_listCacheableResources_excludes_admin_entries() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
        final var inner = new ConcurrentHashMap<String, DbEntry>();
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "id1");
        inner.put("id1", DbEntry.fromJsonObject("userDb", "c1", obj));
        collectionMap.put(Cache.getCollectionIdentifier("userDb", "c1"), inner);
        collectionMap.put(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases"), new ConcurrentHashMap<>());
        final var resources = cache.listCacheableResources();
        assertTrue(resources.stream().anyMatch(r -> r.dbName().equals("userDb")));
        assertTrue(resources.stream().noneMatch(r -> r.dbName().equals(Globals.ADMIN_DB_NAME)));
    }

    // ── getEntriesByIds / streamCollection (page-streaming read path) ─────────

    private static void injectPkIndex(UserCache cache, String collId, List<PkIndexEntry> entries)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", type);
        pkIndexMap.put(collId, new ArrayList<>(entries));
    }

    private static void injectCachedEntry(UserCache cache, String collId, DbEntry entry)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", type);
        final var inner = collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>());
        inner.put(entry.get_id(), entry);
    }

    @Test
    public void test_getEntriesByIds_empty_or_null_returns_empty() throws Exception {
        UserCache cache = new UserCache();
        assertTrue(cache.getEntriesByIds("userDb", "c1", Set.of()).isEmpty());
        assertTrue(cache.getEntriesByIds("userDb", "c1", null).isEmpty());
    }

    @Test
    public void test_getEntriesByIds_serves_from_cache_without_fs_read() throws Exception {
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);

        final var collId = Cache.getCollectionIdentifier("userDb", "c1");
        injectPkIndex(cache, collId, List.of(new PkIndexEntry("userDb", "c1", "id1", 0, 50, 0)));
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "id1");
        injectCachedEntry(cache, collId, DbEntry.fromJsonObject("userDb", "c1", obj));

        final var result = cache.getEntriesByIds("userDb", "c1", Set.of("id1"));

        assertEquals(1, result.size());
        assertEquals("id1", result.getFirst().get_id());
        verify(fsMock, never()).getByIndexEntries(anyList());
    }

    @Test
    public void test_getEntriesByIds_targeted_read_for_missing_and_populates_cache() throws Exception {
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);

        final var collId = Cache.getCollectionIdentifier("userDb", "c1");
        final var pk1 = new PkIndexEntry("userDb", "c1", "id1", 0, 50, 0);
        final var pk2 = new PkIndexEntry("userDb", "c1", "id2", 50, 50, 0);
        injectPkIndex(cache, collId, List.of(pk1, pk2));
        // id1 already cached; id2 must be read from disk.
        final var cachedObj = new JsonObject();
        cachedObj.addProperty(Globals.PK_FIELD, "id1");
        injectCachedEntry(cache, collId, DbEntry.fromJsonObject("userDb", "c1", cachedObj));

        final var readObj = new JsonObject();
        readObj.addProperty(Globals.PK_FIELD, "id2");
        final var readEntry = DbEntry.fromJsonObject("userDb", "c1", readObj);
        when(fsMock.getByIndexEntries(anyList())).thenReturn(List.of(readEntry));

        final var result = cache.getEntriesByIds("userDb", "c1", new HashSet<>(Set.of("id1", "id2")));

        assertEquals(2, result.size());
        // Only the missing entry should have been targeted-read.
        final var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        //noinspection unchecked
        verify(fsMock).getByIndexEntries(captor.capture());
        //noinspection unchecked
        final List<PkIndexEntry> requested = captor.getValue();
        assertEquals(1, requested.size());
        assertEquals("id2", requested.getFirst().getValue());

        // The freshly read entry should now be cached.
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", type);
        assertTrue(collectionMap.get(collId).containsKey("id2"));
    }
}
