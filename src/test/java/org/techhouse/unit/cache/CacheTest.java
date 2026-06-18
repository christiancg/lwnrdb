package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
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

public class CacheTest {

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws IOException, InterruptedException, NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Loading admin data populates the databases and collections maps correctly
    @Test
    public void test_load_admin_data_populates_maps_correctly()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        Cache cache = new Cache();

        // Act
        cache.loadAdminData();

        final var jsonDb = new JsonObject();
        jsonDb.add(Globals.PK_FIELD, "test_create");
        final var arrDb = new JsonArray();
        arrDb.add("test_create_collection");
        jsonDb.add("collections", arrDb);
        final var adminDbEntry = AdminDbEntry.fromJsonObject(jsonDb);
        final var pkIndexEntry = new PkIndexEntry(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                "test_create", 0, 100, 0);
        cache.putAdminDbEntry(adminDbEntry, pkIndexEntry);

        final var jsonColl = new JsonObject();
        jsonColl.add(Globals.PK_FIELD, "test_create");
        final var arrColl = new JsonArray();
        arrColl.add("test_index");
        jsonColl.add("indexes", arrColl);
        jsonColl.add("entryCount", new JsonNumber(0));
        final var adminCollEntry = AdminCollEntry.fromJsonObject(jsonColl);
        cache.putAdminCollectionEntry(adminCollEntry, pkIndexEntry);

        final var typeDbs = new ReflectionUtils.TypeToken<Map<String, AdminDbEntry>>() {
        };
        final var databases = TestUtils.getPrivateField(cache, "databases", typeDbs);
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);

        // Assert
        Assertions.assertFalse(databases.isEmpty());
        Assertions.assertFalse(collections.isEmpty());
    }

    // Loading admin data when the file system is empty
    @Test
    public void test_load_admin_data_when_file_system_is_empty()
            throws IOException, IllegalAccessException, NoSuchFieldException {
        // Arrange
        Cache cache = new Cache();

        // Act
        cache.loadAdminData();

        final var typeDbs = new ReflectionUtils.TypeToken<Map<String, AdminDbEntry>>() {
        };
        final var databases = TestUtils.getPrivateField(cache, "databases", typeDbs);
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);
        // Assert
        Assertions.assertTrue(databases.isEmpty());
        Assertions.assertTrue(collections.isEmpty());
    }

    // Retrieving field index loads data from the file system if not present in cache
    @Test
    public void test_retrieving_field_index_loads_data()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Mocking FileSystem and setting up necessary data
        FileSystem fsMock = mock(FileSystem.class);
        Cache cache = new Cache();
        Field fsField = Cache.class.getDeclaredField("fs");
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

    @Test
    public void test_returns_primary_key_index_from_cache()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        Cache cache = new Cache();
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

    // Returns indexes from fieldIndexMap if already loaded
    @Test
    public void test_returns_indexes_from_fieldIndexMap_if_already_loaded()
            throws NoSuchFieldException, IllegalAccessException {
        // Arrange
        Cache cache = new Cache();
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        Map<String, List<FieldIndexEntry<?>>> expectedIndexes = new ConcurrentHashMap<>();
        expectedIndexes.put("type1", List.of(new FieldIndexEntry<>(dbName, collName, "value1", Set.of("id1"))));

        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        fieldIndexMap.put(collectionIdentifier, expectedIndexes);

        // Act
        Map<String, List<FieldIndexEntry<?>>> actualIndexes = cache.getAllFieldIndexesAndLoadIfNecessary(dbName,
                collName, fieldName);

        // Assert
        assertEquals(expectedIndexes, actualIndexes);
    }

    // Returns null if readAllWholeFieldIndexFiles returns null
    @Test
    public void test_returns_null_if_readAllWholeFieldIndexFiles_returns_null()
            throws NoSuchFieldException, IllegalAccessException {
        // Arrange
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        // Changing the accessibility of fs for testing
        Field fsField = cache.getClass().getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";
        when(fsMock.readAllWholeFieldIndexFiles(dbName, collName, fieldName)).thenReturn(new ConcurrentHashMap<>());

        // Act
        Map<String, List<FieldIndexEntry<?>>> actualIndexes = cache.getAllFieldIndexesAndLoadIfNecessary(dbName,
                collName, fieldName);

        // Assert
        assertTrue(actualIndexes.isEmpty());
    }

    // Returns list of FieldIndexEntry when index is already loaded
    @Test
    public void test_returns_list_when_index_loaded() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        Cache cache = new Cache();
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
        Cache cache = new Cache();
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
        var cache = mock(Cache.class);
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
        var cache = new Cache();
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
        var cache = mock(Cache.class);
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
        var cache = mock(Cache.class);
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
        Cache cache = new Cache();
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
        Cache cache = new Cache();
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
        Cache cache = new Cache();
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
        Cache cache = new Cache();
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
        Cache cache = new Cache();
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
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = Cache.class.getDeclaredField("fs");
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
        final var pagesMap = TestUtils.getPrivateField(cache, "pages", typePages);
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
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeCollMap);
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

    // Retrieve PkIndexEntry for existing database name
    @Test
    public void test_retrieve_pk_index_entry_existing_dbname() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        PkIndexEntry expectedEntry = new PkIndexEntry("testDb", "testCollection", "testValue", 1L, 100L, 0);

        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var databasesPkIndex = TestUtils.getPrivateField(cache, "databasesPkIndex", typeCollPk);

        databasesPkIndex.put("testDb", expectedEntry);

        PkIndexEntry result = cache.getPkIndexAdminDbEntry("testDb");

        assertNotNull(result);
        assertEquals(expectedEntry, result);
    }

    // Database name is an empty string
    @Test
    public void test_retrieve_pk_index_entry_empty_dbname() {
        Cache cache = new Cache();

        PkIndexEntry result = cache.getPkIndexAdminDbEntry("");

        assertNull(result);
    }

    // Adding a valid PkIndexEntry to databasesPkIndex
    @Test
    public void test_add_valid_pk_index_entry() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        PkIndexEntry entry = new PkIndexEntry("db1", "coll1", "value1", 0L, 100L, 0);
        cache.putPkIndexAdminDbEntry(entry);

        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var databasesPkIndex = TestUtils.getPrivateField(cache, "databasesPkIndex", typeCollPk);

        assertEquals(entry, databasesPkIndex.get("value1"));
    }

    // Retrieve an existing AdminDbEntry by its database name
    @Test
    public void test_retrieve_existing_admin_db_entry() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        AdminDbEntry entry = new AdminDbEntry("testDb");

        final var typeDbs = new ReflectionUtils.TypeToken<Map<String, AdminDbEntry>>() {
        };
        final var databases = TestUtils.getPrivateField(cache, "databases", typeDbs);

        databases.put("testDb", entry);

        AdminDbEntry result = cache.getAdminDbEntry("testDb");

        assertNotNull(result);
        assertEquals("testDb", result.get_id());
    }

    // Database name is an empty string
    @Test
    public void test_empty_database_name() {
        Cache cache = new Cache();

        AdminDbEntry result = cache.getAdminDbEntry("");

        assertNull(result);
    }

    // Retrieve existing PkIndexEntry for a valid collection identifier
    @Test
    public void test_retrieve_existing_pk_index_entry() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        PkIndexEntry expectedEntry = new PkIndexEntry("dbName", "collName", "value", 1L, 100L, 0);

        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var collectionsPkIndex = TestUtils.getPrivateField(cache, "collectionsPkIndex", typeCollPk);

        collectionsPkIndex.put("validCollId", expectedEntry);

        PkIndexEntry result = cache.getPkIndexAdminCollEntry("validCollId");

        assertNotNull(result);
        assertEquals(expectedEntry, result);
    }

    // Adds a PkIndexEntry to collectionsPkIndex map
    @Test
    public void test_adds_pk_index_entry_to_map() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        PkIndexEntry entry = new PkIndexEntry("db1", "coll1", "value1", 1L, 100L, 0);
        cache.putPkIndexAdminCollEntry(entry);

        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var collectionsPkIndex = TestUtils.getPrivateField(cache, "collectionsPkIndex", typeCollPk);

        assertEquals(entry, collectionsPkIndex.get("value1"));
    }

    // Retrieves an AdminCollEntry when the collection exists in the cache
    @Test
    public void test_retrieves_admin_coll_entry_when_exists_in_cache()
            throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String dbName = "testDB";
        String collName = "testCollection";
        AdminCollEntry expectedEntry = new AdminCollEntry(dbName, collName);

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);

        collections.put(Cache.getCollectionIdentifier(dbName, collName), expectedEntry);

        AdminCollEntry result = cache.getAdminCollectionEntry(dbName, collName);

        assertNotNull(result);
        assertEquals(expectedEntry, result);
    }

    // Handles null values for dbName and collName gracefully
    @Test
    public void test_handles_null_values_gracefully() {
        Cache cache = new Cache();

        AdminCollEntry result1 = cache.getAdminCollectionEntry(null, "testCollection");
        AdminCollEntry result2 = cache.getAdminCollectionEntry("testDB", null);
        AdminCollEntry result3 = cache.getAdminCollectionEntry(null, null);

        assertNull(result1);
        assertNull(result2);
        assertNull(result3);
    }

    // Successfully adds AdminDbEntry and PkIndexEntry to respective maps
    @Test
    public void test_successfully_adds_entries_to_maps() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        AdminDbEntry adminDbEntry = new AdminDbEntry("testDb");
        PkIndexEntry pkIndexEntry = new PkIndexEntry("testDb", "testCollection", "testValue", 1L, 100L, 0);

        cache.putAdminDbEntry(adminDbEntry, pkIndexEntry);

        final var typeDbs = new ReflectionUtils.TypeToken<Map<String, AdminDbEntry>>() {
        };
        final var databases = TestUtils.getPrivateField(cache, "databases", typeDbs);
        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var databasesPkIndex = TestUtils.getPrivateField(cache, "databasesPkIndex", typeCollPk);

        assertEquals(adminDbEntry, databases.get("testDb"));
        assertEquals(pkIndexEntry, databasesPkIndex.get("testDb"));
    }

    // Successfully removes an entry from databases map when dbName exists
    @Test
    public void test_remove_entry_when_dbname_exists() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String dbName = "testDb";
        AdminDbEntry adminDbEntry = new AdminDbEntry(dbName);
        PkIndexEntry pkIndexEntry = new PkIndexEntry(dbName, "testCollection", "testValue", 1L, 100L, 0);

        final var typeDbs = new ReflectionUtils.TypeToken<Map<String, AdminDbEntry>>() {
        };
        final var databases = TestUtils.getPrivateField(cache, "databases", typeDbs);
        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var databasesPkIndex = TestUtils.getPrivateField(cache, "databasesPkIndex", typeCollPk);

        databases.put(dbName, adminDbEntry);
        databasesPkIndex.put(dbName, pkIndexEntry);

        cache.removeAdminDbEntry(dbName);

        assertFalse(databases.containsKey(dbName));
        assertFalse(databasesPkIndex.containsKey(dbName));
    }

    // Successfully adds an AdminCollEntry and PkIndexEntry to their respective maps
    @Test
    public void test_successfully_adds_entries_to_collections_maps()
            throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        AdminCollEntry dbEntry = new AdminCollEntry("testDb", "testColl");
        PkIndexEntry indexEntry = new PkIndexEntry("testDb", "testColl", "testValue", 1L, 100L, 0);

        cache.putAdminCollectionEntry(dbEntry, indexEntry);

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);
        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var collectionsPkIndex = TestUtils.getPrivateField(cache, "collectionsPkIndex", typeCollPk);

        assertEquals(dbEntry, collections.get(dbEntry.get_id()));
        assertEquals(indexEntry, collectionsPkIndex.get(dbEntry.get_id()));
    }

    // Removing an existing collection identifier from collections map
    @Test
    public void test_remove_existing_collection_identifier() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String collIdentifier = "testCollection";
        AdminCollEntry adminCollEntry = new AdminCollEntry(TestGlobals.DB, collIdentifier);
        PkIndexEntry pkIndexEntry = new PkIndexEntry(TestGlobals.DB, collIdentifier, "testValue", 1L, 100L, 0);

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);
        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var collectionsPkIndex = TestUtils.getPrivateField(cache, "collectionsPkIndex", typeCollPk);

        collections.put(collIdentifier, adminCollEntry);
        collectionsPkIndex.put(collIdentifier, pkIndexEntry);

        cache.removeAdminCollEntry(collIdentifier);

        assertFalse(collections.containsKey(collIdentifier));
        assertFalse(collectionsPkIndex.containsKey(collIdentifier));
    }

    // Removing a collection identifier that does not exist in either map
    @Test
    public void test_remove_nonexistent_collection_identifier() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String collIdentifier = "nonExistentCollection";

        cache.removeAdminCollEntry(collIdentifier);

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);
        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var collectionsPkIndex = TestUtils.getPrivateField(cache, "collectionsPkIndex", typeCollPk);

        assertFalse(collections.containsKey(collIdentifier));
        assertFalse(collectionsPkIndex.containsKey(collIdentifier));
    }

    // Evicting an entry from a populated collection
    @Test
    public void evict_entry_from_populated_collection() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
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
        Cache cache = new Cache();
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
        Cache cache = new Cache();
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
        Cache cache = new Cache();
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
        Cache cache = new Cache();
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
        Cache cache = new Cache();
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

    // Returns true if the specified field index exists in the collection
    @Test
    public void test_field_index_exists() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String dbName = "testDb";
        String collName = "testCollection";
        String fieldName = "testField";

        Set<String> indexes = new HashSet<>(Arrays.asList("testField", "anotherField"));

        final var type = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", type);

        var coll = new AdminCollEntry(dbName, collName, indexes);

        collections.put(Cache.getCollectionIdentifier(dbName, collName), coll);

        boolean result = cache.hasIndex(dbName, collName, fieldName);
        assertTrue(result);
    }

    // Returns true when the field index is present in the fieldIndexMap
    @Test
    public void test_returns_true_when_field_index_present() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
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
        Cache cache = new Cache();
        String dbName = "testDb";
        String collName = "testColl";
        String fieldName = "testField";

        boolean result = cache.hasLoadedIndex(dbName, collName, fieldName);

        assertFalse(result);
    }

    @Test
    public void test_retrieve_indexes_for_valid_collection_identifier()
            throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        AdminCollEntry adminCollEntry = mock(AdminCollEntry.class);
        Set<String> indexes = new HashSet<>();
        indexes.add("index1");
        indexes.add("index2");
        when(adminCollEntry.getIndexes()).thenReturn(indexes);

        final var type = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", type);

        collections.put("testDb|testColl", adminCollEntry);
        Set<String> expectedIndexes = new HashSet<>();
        expectedIndexes.add("index1");
        expectedIndexes.add("index2");
        Set<String> actualIndexes = cache.getIndexesForCollection("testDb", "testColl");
        assertEquals(expectedIndexes, actualIndexes);
    }

    @Test
    public void test_select_page_for_insert_returns_zero_when_empty() {
        Cache cache = new Cache();
        long target = cache.selectPageForInsert("myDb", "myColl", 100);
        assertEquals(0L, target);
    }

    @Test
    public void test_select_page_for_insert_first_fit_picks_first_page_with_room()
            throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        final var p0 = new org.techhouse.data.admin.AdminPageEntry("myDb", "myColl", 0L);
        p0.setPageSize(Long.parseLong(System.getProperty("maxPageBytesOverride", "2097150"))); // near-full
        p0.setEntryCount(10);
        final var p1 = new org.techhouse.data.admin.AdminPageEntry("myDb", "myColl", 1L);
        p1.setPageSize(200L);
        p1.setEntryCount(1);
        final var p2 = new org.techhouse.data.admin.AdminPageEntry("myDb", "myColl", 2L);
        p2.setPageSize(Long.parseLong(System.getProperty("maxPageBytesOverride", "2097100"))); // also near-full
        p2.setEntryCount(5);

        final var list = new java.util.ArrayList<org.techhouse.data.admin.AdminPageEntry>();
        list.add(p0);
        list.add(p1);
        list.add(p2);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<org.techhouse.data.admin.AdminPageEntry>>>() {
        };
        final var pagesMap = TestUtils.getPrivateField(cache, "pages", type);
        pagesMap.put(Cache.getCollectionIdentifier("myDb", "myColl"), list);

        long target = cache.selectPageForInsert("myDb", "myColl", 100);
        assertEquals(1L, target, "Should pick first page with room");
    }

    @Test
    public void test_select_page_for_insert_allocates_new_page_when_none_fit()
            throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        final var p0 = new org.techhouse.data.admin.AdminPageEntry("myDb", "myColl", 0L);
        p0.setPageSize(2_097_150L); // 2MB-2 bytes, very near max default of 2MB
        p0.setEntryCount(10);
        final var p1 = new org.techhouse.data.admin.AdminPageEntry("myDb", "myColl", 1L);
        p1.setPageSize(2_097_150L);
        p1.setEntryCount(10);

        final var list = new java.util.ArrayList<org.techhouse.data.admin.AdminPageEntry>();
        list.add(p0);
        list.add(p1);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<org.techhouse.data.admin.AdminPageEntry>>>() {
        };
        final var pagesMap = TestUtils.getPrivateField(cache, "pages", type);
        pagesMap.put(Cache.getCollectionIdentifier("myDb", "myColl"), list);

        long target = cache.selectPageForInsert("myDb", "myColl", 100_000); // 100KB, doesn't fit anywhere
        assertEquals(2L, target, "Should allocate new page when no existing page has room");
    }

    @Test
    public void test_select_page_for_insert_with_pending_bytes() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        final var p0 = new org.techhouse.data.admin.AdminPageEntry("myDb", "myColl", 0L);
        p0.setPageSize(1_000_000L);
        p0.setEntryCount(10);

        final var list = new java.util.ArrayList<org.techhouse.data.admin.AdminPageEntry>();
        list.add(p0);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<org.techhouse.data.admin.AdminPageEntry>>>() {
        };
        final var pagesMap = TestUtils.getPrivateField(cache, "pages", type);
        pagesMap.put(Cache.getCollectionIdentifier("myDb", "myColl"), list);

        // 1MB existing + 500KB pending + 100KB new = 1.6MB, still under 2MB cap
        long target = cache.selectPageForInsert("myDb", "myColl", 100_000, Map.of(0L, 500_000L));
        assertEquals(0L, target, "Within-batch pending bytes still leave room on page 0");

        // 1MB existing + 1.5MB pending + 100KB new = 2.6MB, exceeds 2MB cap
        long target2 = cache.selectPageForInsert("myDb", "myColl", 100_000, Map.of(0L, 1_500_000L));
        assertEquals(1L, target2, "Pending bytes can push selection to a new page");
    }

    @Test
    public void test_remove_admin_page_entries_clears_both_maps() {
        Cache cache = new Cache();
        cache.addAdminPageEntries("myDb", "myColl", new org.techhouse.data.admin.AdminPageEntry("myDb", "myColl", 0L));
        cache.getAdminPagePkIndexes("myDb", "myColl")
                .add(new PkIndexEntry("admin", "pages_myColl", "myDb|myColl|0", 0L, 10L, 0L));

        assertNotNull(cache.getAdminPageEntries("myDb", "myColl"));
        cache.removeAdminPageEntries("myDb", "myColl");
        assertNull(cache.getAdminPageEntries("myDb", "myColl"));
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

    // getCollectionNamesForDatabase returns only collections belonging to the given database
    @Test
    public void test_get_collection_names_for_database() throws IOException, InterruptedException {
        TestUtils.createTestDatabaseAndCollection();
        Cache cache = IocContainer.get(Cache.class);
        List<String> names = cache.getCollectionNamesForDatabase(TestGlobals.DB);
        assertNotNull(names);
        assertTrue(names.contains(TestGlobals.COLL));
    }

    // getCollectionNamesForDatabase does not return collections from other databases
    @Test
    public void test_get_collection_names_excludes_other_databases() throws IOException, InterruptedException {
        TestUtils.createTestDatabaseAndCollection();
        Cache cache = IocContainer.get(Cache.class);
        List<String> names = cache.getCollectionNamesForDatabase("someOtherDb");
        assertNotNull(names);
        assertTrue(names.isEmpty());
    }

    // getAdminPageEntry returns the correct page entry
    @Test
    public void test_get_admin_page_entry_returns_correct_entry() {
        Cache cache = new Cache();
        cache.updatePageSizeInMemory("db", "coll", 0L, 100L);
        var entry = cache.getAdminPageEntry("db", "coll", 0L);
        assertNotNull(entry);
        assertEquals(0L, entry.getPage());
    }

    // getAdminPageEntry returns null for a page number that does not exist
    @Test
    public void test_get_admin_page_entry_returns_null_for_missing_page() {
        Cache cache = new Cache();
        cache.updatePageSizeInMemory("db", "coll", 0L, 100L);
        var entry = cache.getAdminPageEntry("db", "coll", 99L);
        assertNull(entry);
    }

    // getAdminPageEntry returns null when no pages exist for the collection
    @Test
    public void test_get_admin_page_entry_returns_null_when_no_pages() {
        Cache cache = new Cache();
        var entry = cache.getAdminPageEntry("db", "coll", 0L);
        assertNull(entry);
    }

    // getIdsFromIndex with a JsonCustom value returns results from custom index
    @Test
    public void test_get_ids_from_index_with_custom_type() throws IOException {
        var cache = mock(Cache.class);
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
        var cache = mock(Cache.class);
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
        var cache = mock(Cache.class);
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
        var cache = mock(Cache.class);
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
        var cache = new Cache();
        var arr = new JsonArray();
        JsonObject nested = new JsonObject();
        nested.addProperty("key", "value");
        arr.add(nested);
        var operator = new FieldOperator(FieldOperatorType.IN, "field", arr);

        var result = cache.getIdsFromIndex("db", "coll", "field", operator, arr);

        assertNull(result);
    }

    @Test
    public void test_load_admin_data_with_existing_users_populates_users_map() throws Exception {
        // Persist a user to disk
        final var userEntry = new org.techhouse.data.admin.AdminUserEntry("cachetest_user", "hash", false,
                new java.util.HashSet<>(), new java.util.HashMap<>(), new java.util.HashMap<>());
        org.techhouse.ops.AdminOperationHelper.saveUserEntry(userEntry);

        // Clear in-memory user maps so loadAdminData must reload from disk
        Cache cache = IocContainer.get(Cache.class);
        TestUtils.setPrivateField(cache, "users", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "usersPkIndex", new ConcurrentHashMap<>());

        // Reload — should find the persisted user
        cache.loadAdminData();

        assertNotNull(cache.getAdminUserEntry("cachetest_user"));
    }

    // loadAdminData with pre-existing databases and collections populates all maps (L49-67, L92-98)
    @Test
    public void test_load_admin_data_with_existing_databases_and_collections() throws Exception {
        // Save a database and collection to disk
        TestUtils.createTestDatabaseAndCollection();

        // Clear the in-memory cache so loadAdminData must reload from disk
        Cache cache = IocContainer.get(Cache.class);
        TestUtils.setPrivateField(cache, "databases", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "collections", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "databasesPkIndex", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "collectionsPkIndex", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "pages", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "pagesPkIndexes", new ConcurrentHashMap<>());

        // Reload admin data — should find the saved database and collection
        cache.loadAdminData();

        // Verify databases and collections were loaded
        assertNotNull(cache.getAdminDbEntry(TestGlobals.DB));
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_evictPkIndex_removes_only_pk_for_target_collection() throws Exception {
        Cache cache = IocContainer.get(Cache.class);
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
        Cache cache = IocContainer.get(Cache.class);
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
        Cache cache = IocContainer.get(Cache.class);
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
        Cache cache = IocContainer.get(Cache.class);
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
        Cache cache = IocContainer.get(Cache.class);
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
        Cache cache = IocContainer.get(Cache.class);
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
        Cache cache = IocContainer.get(Cache.class);
        final var collType = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
        collectionMap.put(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases"), new ConcurrentHashMap<>());
        cache.evictCollectionDocuments(Globals.ADMIN_DB_NAME, "databases");
        assertTrue(collectionMap.containsKey(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, "databases")));
    }

    @Test
    public void test_listCacheableResources_excludes_admin_entries() throws Exception {
        Cache cache = IocContainer.get(Cache.class);
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
        Cache cache = IocContainer.get(Cache.class);
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
            Cache cache = IocContainer.get(Cache.class);
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
            Cache cache = IocContainer.get(Cache.class);
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
            Cache cache = IocContainer.get(Cache.class);
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
    public void test_collection_usage_pk_index_round_trip() {
        Cache cache = IocContainer.get(Cache.class);
        final var pk = new PkIndexEntry(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME, "usage-id", 0, 10,
                0);
        cache.putPkIndexCollectionUsage(pk);
        assertEquals(pk, cache.getPkIndexCollectionUsage("usage-id"));
        assertTrue(cache.getCollectionUsagePkIndexes().containsKey("usage-id"));
        cache.removePkIndexCollectionUsage("usage-id");
        assertNull(cache.getPkIndexCollectionUsage("usage-id"));
    }

    @Test
    public void test_addEntryToCache_refuses_when_over_cap() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        // Tight cap that an entry's byteSize will exceed.
        TestUtils.setPrivateField(config, "maxMemoryBytes", 1L);
        try {
            Cache cache = IocContainer.get(Cache.class);
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
            Cache cache = IocContainer.get(Cache.class);
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
            Cache cache = IocContainer.get(Cache.class);
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
            Cache cache = IocContainer.get(Cache.class);
            FileSystem fsMock = mock(FileSystem.class);
            final var fsField = Cache.class.getDeclaredField("fs");
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
            Cache cache = IocContainer.get(Cache.class);
            FileSystem fsMock = mock(FileSystem.class);
            final var fsField = Cache.class.getDeclaredField("fs");
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
            final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", collType);
            assertFalse(collectionMap.containsKey(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
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
            Cache cache = IocContainer.get(Cache.class);
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

    private static void injectPkIndex(Cache cache, String collId, List<PkIndexEntry> entries)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", type);
        pkIndexMap.put(collId, new ArrayList<>(entries));
    }

    private static void injectCachedEntry(Cache cache, String collId, DbEntry entry)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", type);
        final var inner = collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>());
        inner.put(entry.get_id(), entry);
    }

    private static void injectPages(Cache cache, String collId, List<org.techhouse.data.admin.AdminPageEntry> pageList)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, List<org.techhouse.data.admin.AdminPageEntry>>>() {
        };
        final var pages = TestUtils.getPrivateField(cache, "pages", type);
        pages.put(collId, pageList);
    }

    @Test
    public void test_getEntriesByIds_empty_or_null_returns_empty() throws Exception {
        Cache cache = new Cache();
        assertTrue(cache.getEntriesByIds("userDb", "c1", Set.of()).isEmpty());
        assertTrue(cache.getEntriesByIds("userDb", "c1", null).isEmpty());
    }

    @Test
    public void test_getEntriesByIds_serves_from_cache_without_fs_read() throws Exception {
        Cache cache = new Cache();
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
        Cache cache = new Cache();
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
        verify(fsMock).getByIndexEntries(captor.capture());
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
        Cache cache = new Cache();
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
            Cache cache = new Cache();
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
        Cache cache = new Cache();
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

    @Test
    public void test_streamCollection_fully_cached_streams_from_cache() throws Exception {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);

        final var collId = Cache.getCollectionIdentifier("userDb", "c1");
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "id1");
        injectCachedEntry(cache, collId, DbEntry.fromJsonObject("userDb", "c1", obj));
        final var pageEntry = new org.techhouse.data.admin.AdminPageEntry("userDb", "c1", 0);
        pageEntry.setEntryCount(1);
        injectPages(cache, collId, new ArrayList<>(List.of(pageEntry)));

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
        injectPages(cache, collId, new ArrayList<>(List.of(pageEntry)));

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
            injectCachedEntry(cache, collId, DbEntry.fromJsonObject("userDb", "c1", cachedObj));
            final var pageEntry = new org.techhouse.data.admin.AdminPageEntry("userDb", "c1", 0);
            pageEntry.setEntryCount(1);
            injectPages(cache, collId, new ArrayList<>(List.of(pageEntry)));
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
}
