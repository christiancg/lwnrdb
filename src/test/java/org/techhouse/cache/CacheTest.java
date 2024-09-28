package org.techhouse.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.*;
import org.techhouse.fs.FileSystem;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CacheTest {

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws IOException, InterruptedException {
        TestUtils.standardTearDown();
    }

    // Loading admin data populates the databases and collections maps correctly
    @Test
    public void test_load_admin_data_populates_maps_correctly() throws IOException, NoSuchFieldException, IllegalAccessException {
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
        final var pkIndexEntry = new PkIndexEntry(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, "test_create", 0, 100);
        cache.putAdminDbEntry(adminDbEntry, pkIndexEntry);

        final var jsonColl = new JsonObject();
        jsonColl.add(Globals.PK_FIELD, "test_create");
        final var arrColl = new JsonArray();
        arrColl.add("test_index");
        jsonColl.add("indexes", arrColl);
        jsonColl.add("entryCount", new JsonDouble(0));
        final var adminCollEntry = AdminCollEntry.fromJsonObject(jsonColl);
        cache.putAdminCollectionEntry(adminCollEntry, pkIndexEntry);

        final var databasesField = Cache.class.getDeclaredField("databases");
        databasesField.setAccessible(true);
        final var databases = (Map<String, AdminDbEntry>)databasesField.get(cache);
        final var collectionsField = Cache.class.getDeclaredField("collections");
        collectionsField.setAccessible(true);
        final var collections = (Map<String, AdminDbEntry>)collectionsField.get(cache);

        // Assert
        Assertions.assertFalse(databases.isEmpty());
        Assertions.assertFalse(collections.isEmpty());
    }

    // Loading admin data when the file system is empty
    @Test
    public void test_load_admin_data_when_file_system_is_empty() throws IOException, IllegalAccessException, NoSuchFieldException {
        // Arrange
        Cache cache = new Cache();

        // Act
        cache.loadAdminData();

        final var databasesField = Cache.class.getDeclaredField("databases");
        databasesField.setAccessible(true);
        final var databases = (Map<String, AdminDbEntry>)databasesField.get(cache);
        final var collectionsField = Cache.class.getDeclaredField("collections");
        collectionsField.setAccessible(true);
        final var collections = (Map<String, AdminDbEntry>)collectionsField.get(cache);
        // Assert
        Assertions.assertTrue(databases.isEmpty());
        Assertions.assertTrue(collections.isEmpty());
    }

    // Retrieving field index loads data from the file system if not present in cache
    @Test
    public void test_retrieving_field_index_loads_data() throws IOException, NoSuchFieldException, IllegalAccessException {
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
        List<FieldIndexEntry<Double>> result = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Double.class);
    
        // Assertions
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(1.5, result.getFirst().getValue());
    }

    @Test
    public void test_concatenates_dbname_and_collname_correctly() {
        String dbName = "testDb";
        String collName = "testColl";
        String expected = "testDb" + Globals.COLL_IDENTIFIER_SEPARATOR + "testColl";
        String result = Cache.getCollectionIdentifier(dbName, collName);
        assertEquals(expected, result);
    }

    @Test
    public void test_handles_empty_dbname_and_collname_gracefully() {
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
    public void test_returns_primary_key_index_from_cache() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        Cache cache = new Cache();
        String dbName = "testDb";
        String collName = "testColl";
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        List<PkIndexEntry> expectedPkIndex = List.of(new PkIndexEntry(dbName, collName, "value1", 0, 10));
        Field pkIndexMapField = Cache.class.getDeclaredField("pkIndexMap");
        pkIndexMapField.setAccessible(true);
        final var pkIndexMap = (Map<String, List<PkIndexEntry>>) pkIndexMapField.get(cache);
        pkIndexMap.put(collectionIdentifier, expectedPkIndex);

        // Act
        List<PkIndexEntry> actualPkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);

        // Assert
        assertEquals(expectedPkIndex, actualPkIndex);
    }

    // Returns indexes from fieldIndexMap if already loaded
    @Test
    public void test_returns_indexes_from_fieldIndexMap_if_already_loaded() throws NoSuchFieldException, IllegalAccessException {
        // Arrange
        Cache cache = new Cache();
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        Map<String, List<FieldIndexEntry<?>>> expectedIndexes = new ConcurrentHashMap<>();
        expectedIndexes.put("type1", List.of(new FieldIndexEntry<>(dbName, collName, "value1", Set.of("id1"))));

        Field fieldIndexMapField = Cache.class.getDeclaredField("fieldIndexMap");
        fieldIndexMapField.setAccessible(true);
        final var fieldIndexMap = (Map<String, Map<String, List<FieldIndexEntry<?>>>>) fieldIndexMapField.get(cache);
        fieldIndexMap.put(collectionIdentifier, expectedIndexes);

        // Act
        Map<String, List<FieldIndexEntry<?>>> actualIndexes = cache.getAllFieldIndexesAndLoadIfNecessary(dbName, collName, fieldName);

        // Assert
        assertEquals(expectedIndexes, actualIndexes);
    }

    // Returns null if readAllWholeFieldIndexFiles returns null
    @Test
    public void test_returns_null_if_readAllWholeFieldIndexFiles_returns_null() throws NoSuchFieldException, IllegalAccessException {
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
        Map<String, List<FieldIndexEntry<?>>> actualIndexes = cache.getAllFieldIndexesAndLoadIfNecessary(dbName, collName, fieldName);

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

        Field fieldIndexMapField = Cache.class.getDeclaredField("fieldIndexMap");
        fieldIndexMapField.setAccessible(true);
        final var fieldIndexMap = (Map<String, Map<String, List<FieldIndexEntry<?>>>>) fieldIndexMapField.get(cache);
        fieldIndexMap.put(collectionIdentifier, indexMap);

        // Act
        List<FieldIndexEntry<String>> result = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, indexType);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("value", result.getFirst().getValue());
    }

    // Handles empty or null field index map
    @Test
    public void test_handles_empty_or_null_field_index_map() throws IOException, NoSuchFieldException, IllegalAccessException {
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
        List<FieldIndexEntry<String>> result = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, indexType);

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
        var operator = new FieldOperator(FieldOperatorType.EQUALS, fieldName, new JsonDouble(10.0));
        var value = 10.0;

        var indexEntries = List.of(new FieldIndexEntry<>(dbName, collName, value, Set.of("id1", "id2")));
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Double.class))
                .thenReturn(indexEntries);

        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, value))
                .thenCallRealMethod();

        // Act
        var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, value);

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("id1"));
        assertTrue(result.contains("id2"));
    }

    // Handles null values in JsonArray gracefully
    @Test
    public void test_handles_null_values_in_jsonarray() throws IOException {
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
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Boolean.class)).thenReturn(booleanIndex);

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
}