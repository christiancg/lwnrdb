package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.custom_types.JsonTime;
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
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class UserCacheTest {

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Retrieving field index loads data from the file system if not present in cache
    @Test
    public void test_retrieving_field_index_loads_data()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Mocking FileSystem and setting up necessary data
        FileSystem fsMock = mock(FileSystem.class);
        UserCache cache = new UserCache();
        Field fsField = UserCache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        String dbName = "test_db";
        String collName = "test_coll";
        String fieldName = "test_field";

        List<FieldIndexEntry<Double>> fieldIndexEntries = new ArrayList<>();
        fieldIndexEntries.add(new FieldIndexEntry<>(dbName, collName, 1.5, new HashSet<>(List.of("1", "2"))));

        when(fsMock.readWholeFieldIndexFiles(dbName, collName, fieldName, Double.class)).thenReturn(fieldIndexEntries);

        // Calling the method under test
        List<FieldIndexEntry<Double>> result = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                Double.class);

        // Assertions
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(1.5, result.getFirst().getValue());
    }

    @Test
    public void test_returns_primary_key_index_from_cache()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        List<PkIndexEntry> expectedPkIndex = List.of(new PkIndexEntry(dbName, collName, "value1", 0, 10, 0));
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", type);
        pkIndexMap.put(collectionIdentifier, expectedPkIndex);

        // Act
        List<PkIndexEntry> actualPkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);

        // Assert
        assertEquals(expectedPkIndex, actualPkIndex);
    }

    // Returns list of FieldIndexEntry when index is already loaded
    @Test
    public void test_returns_list_when_index_loaded() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        UserCache cache = new UserCache();
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";
        Class<String> indexType = String.class;
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        String indexIdentifier = Cache.getIndexIdentifier(fieldName, indexType);

        FieldIndexEntry<String> entry = new FieldIndexEntry<>(dbName, collName, "value", Set.of("id1", "id2"));
        Map<String, List<FieldIndexEntry<?>>> indexMap = new ConcurrentHashMap<>();
        indexMap.put(indexIdentifier, List.of(entry));

        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        fieldIndexMap.put(collectionIdentifier, indexMap);

        // Act
        List<FieldIndexEntry<String>> result = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                indexType);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("value", result.getFirst().getValue());
    }

    // Handles empty or null field index map
    @Test
    public void test_handles_empty_or_null_field_index_map()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        // Changing the accessibility of fs for testing
        Field fsField = cache.getClass().getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";
        Class<String> indexType = String.class;

        when(fsMock.readWholeFieldIndexFiles(dbName, collName, fieldName, indexType)).thenReturn(null);

        // Act
        List<FieldIndexEntry<String>> result = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                indexType);

        // Assert
        assertNull(result);
    }

    // Retrieves IDs for Double values using the appropriate index
    @Test
    public void test_retrieves_ids_for_double_values() throws IOException {
        // Arrange
        var cache = mock(UserCache.class);
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

    // Handles the case where the collection is not in the cache
    @Test
    public void handles_collection_not_in_cache() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        // Arrange
        String dbName = TestGlobals.DB;
        String collName = TestGlobals.COLL;
        PkIndexEntry idxEntry = new PkIndexEntry(dbName, collName, "testValue", 0, 100, 0);
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = UserCache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        DbEntry expectedEntry = new DbEntry();
        expectedEntry.setDatabaseName(dbName);
        expectedEntry.setCollectionName(collName);
        expectedEntry.set_id("testValue");

        when(fsMock.getById(idxEntry)).thenReturn(expectedEntry);

        // Act
        DbEntry result = cache.getById(dbName, collName, idxEntry);

        // Assert
        assertEquals(expectedEntry, result);
    }

    // Evicting an entry from a populated collection
    @Test
    public void evict_entry_from_populated_collection() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        String pk = "testPk";
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        Map<String, DbEntry> collection = new HashMap<>();
        collection.put(pk, new DbEntry());

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeColl);

        collectionMap.put(collectionIdentifier, collection);

        cache.evictEntry(dbName, collName, pk);

        assertFalse(collectionMap.get(collectionIdentifier).containsKey(pk));
    }

    // Evicting an entry when the collection map is empty
    @Test
    public void evict_entry_from_empty_collection_map() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        String pk = "testPk";

        cache.evictEntry(dbName, collName, pk);

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeColl);

        assertNull(collectionMap.get(Cache.getCollectionIdentifier(dbName, collName)));
    }

    // Successfully evicts all collections and their primary key indexes for a given database name
    @Test
    public void test_evict_database_success() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collectionName = dbName + "_collection";

        PkIndexEntry pkIndexEntry = new PkIndexEntry(dbName, collectionName, "123", 0, 100, 0);
        DbEntry dbEntry = new DbEntry();

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeColl);

        pkIndexMap.put(collectionName, List.of(pkIndexEntry));
        collectionMap.put(collectionName, Map.of("key", dbEntry));

        cache.evictDatabase(dbName);

        assertTrue(pkIndexMap.isEmpty());
        assertTrue(collectionMap.isEmpty());
    }

    // Evicts collections when the database name is an empty string
    @Test
    public void test_evict_database_empty_string() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "";
        String collectionName = dbName + "_collection";

        PkIndexEntry pkIndexEntry = new PkIndexEntry(dbName, collectionName, "123", 0, 100, 0);
        DbEntry dbEntry = new DbEntry();

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeColl);

        pkIndexMap.put(collectionName, List.of(pkIndexEntry));
        collectionMap.put(collectionName, Map.of("key", dbEntry));

        cache.evictDatabase(dbName);

        assertTrue(pkIndexMap.isEmpty());
        assertTrue(collectionMap.isEmpty());
    }

    // evictCollection removes the correct collection from pkIndexMap
    @Test
    public void test_evict_collection_removes_from_pkIndexMap() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        String collIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        List<PkIndexEntry> pkIndexEntries = new ArrayList<>();

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);

        pkIndexMap.put(collIdentifier, pkIndexEntries);

        cache.evictCollection(dbName, collName);

        assertFalse(pkIndexMap.containsKey(collIdentifier));
    }

    // evictCollection is called with a non-existent dbName and collName
    @Test
    public void test_evict_collection_with_non_existent_collection()
            throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "nonExistentDb";
        String collName = "nonExistentColl";
        String collIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        cache.evictCollection(dbName, collName);

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeColl);

        assertFalse(pkIndexMap.containsKey(collIdentifier));
        assertFalse(collectionMap.containsKey(collIdentifier));
    }

    // Returns true when the field index is present in the fieldIndexMap
    @Test
    public void test_returns_true_when_field_index_present() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        String fieldName = "testField";
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        Map<String, List<FieldIndexEntry<?>>> fieldIndexes = new HashMap<>();
        fieldIndexes.put(fieldName, new ArrayList<>());

        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);

        fieldIndexMap.put(collectionIdentifier, fieldIndexes);

        boolean result = cache.hasLoadedIndex(dbName, collName, fieldName);

        assertTrue(result);
    }

    // Returns false when fieldIndexMap is empty
    @Test
    public void test_returns_false_when_field_index_map_empty() {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        String fieldName = "testField";

        boolean result = cache.hasLoadedIndex(dbName, collName, fieldName);

        assertFalse(result);
    }

    // getIdsFromIndex with a JsonCustom value returns results from custom index
    @Test
    public void test_get_ids_from_index_with_custom_type() throws IOException {
        var cache = mock(UserCache.class);
        var dbName = "db";
        var collName = "coll";
        var fieldName = "time";
        var timeValue = new JsonTime("#time(10:00:00)");
        var operator = new FieldOperator(FieldOperatorType.EQUALS, fieldName, timeValue);
        @SuppressWarnings("unchecked")
        List<FieldIndexEntry<Object>> idx = (List<FieldIndexEntry<Object>>) (List<?>) List
                .of(new FieldIndexEntry<>(dbName, collName, timeValue, Set.of("id1")));
        when(cache.getFieldIndexAndLoadIfNecessary(eq(dbName), eq(collName), eq(fieldName), any())).thenReturn(idx);
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, (Object) timeValue)).thenCallRealMethod();

        var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, (Object) timeValue);

        assertNotNull(result);
        assertTrue(result.contains("id1"));
    }

    // getIdsFromIndex with JsonArray containing JsonString elements
    @Test
    public void test_get_ids_from_index_with_json_array_of_strings() throws IOException {
        var cache = mock(UserCache.class);
        var dbName = "db";
        var collName = "coll";
        var fieldName = "tag";
        var arr = new JsonArray();
        arr.add(new JsonString("alpha"));
        arr.add(new JsonString("beta"));
        var operator = new FieldOperator(FieldOperatorType.IN, fieldName, arr);
        var idx = List.of(new FieldIndexEntry<>(dbName, collName, "alpha", Set.of("id1")),
                new FieldIndexEntry<>(dbName, collName, "gamma", Set.of("id2")));
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, String.class)).thenReturn(idx);
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr)).thenCallRealMethod();

        var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr);

        assertNotNull(result);
        assertTrue(result.contains("id1"));
        assertFalse(result.contains("id2"));
    }

    // getIdsFromIndex with JsonArray containing JsonNumber elements
    @Test
    public void test_get_ids_from_index_with_json_array_of_numbers() throws IOException {
        var cache = mock(UserCache.class);
        var dbName = "db";
        var collName = "coll";
        var fieldName = "score";
        var arr = new JsonArray();
        arr.add(new JsonNumber(10.0));
        arr.add(new JsonNumber(20.0));
        var operator = new FieldOperator(FieldOperatorType.IN, fieldName, arr);
        @SuppressWarnings("unchecked")
        List<FieldIndexEntry<Number>> idx = (List<FieldIndexEntry<Number>>) (List<?>) List.of(
                new FieldIndexEntry<>(dbName, collName, 10.0, Set.of("id1")),
                new FieldIndexEntry<>(dbName, collName, 30.0, Set.of("id2")));
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class)).thenReturn(idx);
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr)).thenCallRealMethod();

        var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr);

        assertNotNull(result);
        assertTrue(result.contains("id1"));
        assertFalse(result.contains("id2"));
    }

    // getIdsFromIndex with JsonArray containing JsonBoolean elements
    @Test
    public void test_get_ids_from_index_with_json_array_of_booleans() throws IOException {
        var cache = mock(UserCache.class);
        var dbName = "db";
        var collName = "coll";
        var fieldName = "active";
        var arr = new JsonArray();
        arr.add(new JsonBoolean(true));
        var operator = new FieldOperator(FieldOperatorType.IN, fieldName, arr);
        var idx = List.of(new FieldIndexEntry<>(dbName, collName, true, Set.of("id1")),
                new FieldIndexEntry<>(dbName, collName, false, Set.of("id2")));
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Boolean.class)).thenReturn(idx);
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr)).thenCallRealMethod();

        var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr);

        assertNotNull(result);
        assertTrue(result.contains("id1"));
        assertFalse(result.contains("id2"));
    }

    // getIdsFromIndex with JsonArray containing a non-primitive first element returns null
    @Test
    public void test_get_ids_from_index_with_json_array_non_primitive_returns_null() throws IOException {
        var cache = new UserCache();
        var arr = new JsonArray();
        JsonObject nested = new JsonObject();
        nested.addProperty("key", "value");
        arr.add(nested);
        var operator = new FieldOperator(FieldOperatorType.IN, "field", arr);

        var result = cache.getIdsFromIndex("db", "coll", "field", operator, arr);

        assertNull(result);
    }

    @Test
    public void test_evictPkIndex_removes_only_pk_for_target_collection() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", type);
        pkIndexMap.put(Cache.getCollectionIdentifier("db1", "c1"),
                List.of(new PkIndexEntry("db1", "c1", "id1", 0, 1, 0)));
        pkIndexMap.put(Cache.getCollectionIdentifier("db1", "c2"),
                List.of(new PkIndexEntry("db1", "c2", "id1", 0, 1, 0)));
        cache.evictPkIndex("db1", "c1");
        assertFalse(pkIndexMap.containsKey(Cache.getCollectionIdentifier("db1", "c1")));
        assertTrue(pkIndexMap.containsKey(Cache.getCollectionIdentifier("db1", "c2")));
    }

    @Test
    public void test_evictPkIndex_noop_for_admin() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", type);
        pkIndexMap.put(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases"),
                List.of(new PkIndexEntry(Globals.ADMIN_DB_NAME, "databases", "id1", 0, 1, 0)));
        cache.evictPkIndex(Globals.ADMIN_DB_NAME, "databases");
        assertTrue(pkIndexMap.containsKey(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases")));
    }

    @Test
    public void test_evictFieldIndex_removes_only_target_index() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        final Map<String, List<FieldIndexEntry<?>>> indexes = new ConcurrentHashMap<>();
        indexes.put("field|String", List.of(new FieldIndexEntry<>("db1", "c1", "v", Set.of("id1"))));
        indexes.put("other|String", List.of(new FieldIndexEntry<>("db1", "c1", "v", Set.of("id1"))));
        fieldIndexMap.put(Cache.getCollectionIdentifier("db1", "c1"), indexes);
        cache.evictFieldIndex("db1", "c1", "field|String");
        assertFalse(indexes.containsKey("field|String"));
        assertTrue(indexes.containsKey("other|String"));
    }

    @Test
    public void test_evictFieldIndex_removes_collection_entry_when_last_index_evicted() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        final Map<String, List<FieldIndexEntry<?>>> indexes = new ConcurrentHashMap<>();
        indexes.put("field|String", List.of(new FieldIndexEntry<>("db1", "c1", "v", Set.of("id1"))));
        fieldIndexMap.put(Cache.getCollectionIdentifier("db1", "c1"), indexes);
        cache.evictFieldIndex("db1", "c1", "field|String");
        assertFalse(fieldIndexMap.containsKey(Cache.getCollectionIdentifier("db1", "c1")));
    }

    @Test
    public void test_evictFieldIndex_noop_for_admin() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        final Map<String, List<FieldIndexEntry<?>>> indexes = new ConcurrentHashMap<>();
        indexes.put("field|String",
                List.of(new FieldIndexEntry<>(Globals.ADMIN_DB_NAME, "databases", "v", Set.of("id1"))));
        fieldIndexMap.put(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases"), indexes);
        cache.evictFieldIndex(Globals.ADMIN_DB_NAME, "databases", "field|String");
        assertTrue(fieldIndexMap.containsKey(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases")));
    }

    @Test
    public void test_evictCollectionDocuments_removes_only_documents_not_pk() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var pkType = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", pkType);
        final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
        pkIndexMap.put(Cache.getCollectionIdentifier("db1", "c1"),
                List.of(new PkIndexEntry("db1", "c1", "id1", 0, 1, 0)));
        collectionMap.put(Cache.getCollectionIdentifier("db1", "c1"), new ConcurrentHashMap<>());
        cache.evictCollectionDocuments("db1", "c1");
        assertTrue(pkIndexMap.containsKey(Cache.getCollectionIdentifier("db1", "c1")));
        assertFalse(collectionMap.containsKey(Cache.getCollectionIdentifier("db1", "c1")));
    }

    @Test
    public void test_evictCollectionDocuments_noop_for_admin() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
        collectionMap.put(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases"), new ConcurrentHashMap<>());
        cache.evictCollectionDocuments(Globals.ADMIN_DB_NAME, "databases");
        assertTrue(collectionMap.containsKey(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases")));
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

    @Test
    public void test_listCacheableResources_includes_pk_and_field_indexes() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var pkType = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", pkType);
        pkIndexMap.put(Cache.getCollectionIdentifier("db1", "c1"),
                List.of(new PkIndexEntry("db1", "c1", "id1", 0, 1, 0)));
        final var fieldType = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", fieldType);
        final Map<String, List<FieldIndexEntry<?>>> indexes = new ConcurrentHashMap<>();
        indexes.put("f|String", List.of(new FieldIndexEntry<>("db1", "c1", "v", Set.of("id1"))));
        fieldIndexMap.put(Cache.getCollectionIdentifier("db1", "c1"), indexes);
        final var resources = cache.listCacheableResources();
        assertTrue(resources.stream().anyMatch(r -> r.kind() == org.techhouse.cache.AccessKind.PK_INDEX));
        assertTrue(resources.stream().anyMatch(r -> r.kind() == org.techhouse.cache.AccessKind.FIELD_INDEX));
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
        // Tight cap that an entry's byteSize will exceed.
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
        // Cap smaller than the seeded "old" collection — adding "new" should be refused.
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
        org.techhouse.cache.MemoryManagement mmMock = mock(org.techhouse.cache.MemoryManagement.class);
        when(mmMock.admissionCheck(anyLong())).thenReturn(org.techhouse.cache.AdmissionDecision.ADMIT);
        TestUtils.setPrivateField(cache, "memoryManagement", mmMock);

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
        org.techhouse.cache.MemoryManagement mmMock = mock(org.techhouse.cache.MemoryManagement.class);
        when(mmMock.admissionCheck(anyLong())).thenReturn(org.techhouse.cache.AdmissionDecision.ADMIT);
        TestUtils.setPrivateField(cache, "memoryManagement", mmMock);

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

    @Test
    public void test_getEntriesByIds_admission_rejected_does_not_populate_cache() throws Exception {
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);
        org.techhouse.cache.MemoryManagement mmMock = mock(org.techhouse.cache.MemoryManagement.class);
        when(mmMock.admissionCheck(anyLong())).thenReturn(org.techhouse.cache.AdmissionDecision.REJECT);
        TestUtils.setPrivateField(cache, "memoryManagement", mmMock);

        final var collId = Cache.getCollectionIdentifier("userDb", "c1");
        injectPkIndex(cache, collId, List.of(new PkIndexEntry("userDb", "c1", "id1", 0, 50, 0)));
        final var readObj = new JsonObject();
        readObj.addProperty(Globals.PK_FIELD, "id1");
        when(fsMock.getByIndexEntries(anyList())).thenReturn(List.of(DbEntry.fromJsonObject("userDb", "c1", readObj)));

        final var result = cache.getEntriesByIds("userDb", "c1", Set.of("id1"));

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

    @Test
    public void test_getEntriesByIds_skips_ids_not_in_pk_index() throws Exception {
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);
        org.techhouse.cache.MemoryManagement mmMock = mock(org.techhouse.cache.MemoryManagement.class);
        when(mmMock.admissionCheck(anyLong())).thenReturn(org.techhouse.cache.AdmissionDecision.ADMIT);
        TestUtils.setPrivateField(cache, "memoryManagement", mmMock);

        final var collId = Cache.getCollectionIdentifier("userDb", "c1");
        injectPkIndex(cache, collId, List.of(new PkIndexEntry("userDb", "c1", "id1", 0, 50, 0)));

        final var result = cache.getEntriesByIds("userDb", "c1", Set.of("missing"));

        // An id absent from the PK index resolves to nothing, so no targeted read happens.
        assertTrue(result.isEmpty());
        verify(fsMock, never()).getByIndexEntries(anyList());
    }

}
