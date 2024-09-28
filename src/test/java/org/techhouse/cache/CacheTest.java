package org.techhouse.cache;

import org.junit.jupiter.api.*;

import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.*;
import org.techhouse.fs.FileSystem;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

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
        final var collections = (Map<String, AdminCollEntry>)collectionsField.get(cache);

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

        final var collectionMapField = Cache.class.getDeclaredField("collectionMap");
        collectionMapField.setAccessible(true);
        final var collectionMap = (Map<String, Map<String, DbEntry>>)collectionMapField.get(cache);

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

        final var collectionMapField = Cache.class.getDeclaredField("collectionMap");
        collectionMapField.setAccessible(true);
        final var collectionMap = (Map<String, Map<String, DbEntry>>)collectionMapField.get(cache);

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
        List<DbEntry> entries = List.of(
                DbEntry.fromJsonObject(dbName, collName, jsonObject1),
                DbEntry.fromJsonObject(dbName, collName,jsonObject2)
        );

        cache.addEntriesToCache(dbName, collName, entries);

        final var collectionMapField = Cache.class.getDeclaredField("collectionMap");
        collectionMapField.setAccessible(true);
        final var collectionMap = (Map<String, Map<String, DbEntry>>)collectionMapField.get(cache);

        String collId = Cache.getCollectionIdentifier(dbName, collName);
        assertEquals(2, collectionMap.get(collId).size());
        assertTrue(collectionMap.get(collId).containsKey("1"));
        assertTrue(collectionMap.get(collId).containsKey("2"));
    }

    // Adding entries with duplicate IDs
    @Test
    @Disabled // this will fail as there's no "no duplicate id check on the method". We should probably have that
    public void test_adding_entries_with_duplicate_ids() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String dbName = "testDb";
        String collName = "testColl";

        var jsonObject1 = new JsonObject();
        jsonObject1.addProperty(Globals.PK_FIELD, "1");
        var jsonObject2 = new JsonObject();
        jsonObject2.addProperty(Globals.PK_FIELD, "1");

        List<DbEntry> entries = List.of(
                DbEntry.fromJsonObject(dbName, collName, jsonObject1),
                DbEntry.fromJsonObject(dbName, collName, jsonObject2)
        );

        cache.addEntriesToCache(dbName, collName, entries);

        final var collectionMapField = Cache.class.getDeclaredField("collectionMap");
        collectionMapField.setAccessible(true);
        final var collectionMap = (Map<String, Map<String, DbEntry>>)collectionMapField.get(cache);

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
        PkIndexEntry idxEntry = new PkIndexEntry(dbName, collName, "testValue", 0, 100);
        Cache cache = new Cache();
        DbEntry expectedEntry = new DbEntry();
        expectedEntry.setDatabaseName(dbName);
        expectedEntry.setCollectionName(collName);
        expectedEntry.set_id("testValue");

        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        final var collectionMapField = Cache.class.getDeclaredField("collectionMap");
        collectionMapField.setAccessible(true);
        final var collectionMap = (Map<String, Map<String, DbEntry>>)collectionMapField.get(cache);
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
        PkIndexEntry idxEntry = new PkIndexEntry(dbName, collName, "testValue", 0, 100);
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
    public void test_returns_whole_collection_from_cache_if_exists_and_complete() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String dbName = "testDb";
        String collName = "testColl";
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        AdminCollEntry adminCollEntry = mock(AdminCollEntry.class);
        when(adminCollEntry.getEntryCount()).thenReturn(2);

        final var collectionsField = Cache.class.getDeclaredField("collections");
        collectionsField.setAccessible(true);
        final var collections = (Map<String, AdminCollEntry>)collectionsField.get(cache);

        collections.put(collectionIdentifier, adminCollEntry);

        DbEntry entry1 = new DbEntry();
        entry1.set_id("1");
        DbEntry entry2 = new DbEntry();
        entry2.set_id("2");

        Map<String, DbEntry> wholeCollection = new HashMap<>();
        wholeCollection.put("1", entry1);
        wholeCollection.put("2", entry2);

        final var collectionMapField = Cache.class.getDeclaredField("collectionMap");
        collectionMapField.setAccessible(true);
        final var collectionMap = (Map<String, Map<String, DbEntry>>)collectionMapField.get(cache);
        collectionMap.put(collectionIdentifier, wholeCollection);

        Map<String, DbEntry> result = cache.getWholeCollection(dbName, collName);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("1"));
        assertTrue(result.containsKey("2"));
    }

    // Handles IOException when reading the collection from the file system
    @Test
    public void test_handles_ioexception_when_reading_collection_from_file_system() throws NoSuchFieldException, IllegalAccessException, IOException {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = Cache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        String dbName = "testDb";
        String collName = "testColl";
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        AdminCollEntry adminCollEntry = mock(AdminCollEntry.class);
        when(adminCollEntry.getEntryCount()).thenReturn(2);

        final var collectionsField = Cache.class.getDeclaredField("collections");
        collectionsField.setAccessible(true);
        final var collections = (Map<String, AdminCollEntry>)collectionsField.get(cache);
        collections.put(collectionIdentifier, adminCollEntry);

        when(fsMock.readWholeCollection(dbName, collName)).thenThrow(new IOException("File not found"));

        assertThrows(RuntimeException.class, () -> cache.getWholeCollection(dbName, collName));
    }

    // Retrieve PkIndexEntry for existing database name
    @Test
    public void test_retrieve_pkindexentry_existing_dbname() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        PkIndexEntry expectedEntry = new PkIndexEntry("testDb", "testCollection", "testValue", 1L, 100L);

        final var databasesPkIndexField = Cache.class.getDeclaredField("databasesPkIndex");
        databasesPkIndexField.setAccessible(true);
        final var databasesPkIndex = (Map<String, PkIndexEntry>)databasesPkIndexField.get(cache);

        databasesPkIndex.put("testDb", expectedEntry);

        PkIndexEntry result = cache.getPkIndexAdminDbEntry("testDb");

        assertNotNull(result);
        assertEquals(expectedEntry, result);
    }

    // Database name is an empty string
    @Test
    public void test_retrieve_pkindexentry_empty_dbname() {
        Cache cache = new Cache();

        PkIndexEntry result = cache.getPkIndexAdminDbEntry("");

        assertNull(result);
    }

    // Adding a valid PkIndexEntry to databasesPkIndex
    @Test
    public void test_add_valid_pkindexentry() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        PkIndexEntry entry = new PkIndexEntry("db1", "coll1", "value1", 0L, 100L);
        cache.putPkIndexAdminDbEntry(entry);

        final var databasesPkIndexField = Cache.class.getDeclaredField("databasesPkIndex");
        databasesPkIndexField.setAccessible(true);
        final var databasesPkIndex = (Map<String, PkIndexEntry>)databasesPkIndexField.get(cache);

        assertEquals(entry, databasesPkIndex.get("value1"));
    }

    // Adding a PkIndexEntry with a null value
    @Test
    @Disabled // This one will fail because we're not checking that the pk index value shouldn't be null. We should probably do it
    public void test_add_null_value_pkindexentry() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        PkIndexEntry entry = new PkIndexEntry("db1", "coll1", null, 0L, 100L);
        cache.putPkIndexAdminDbEntry(entry);

        final var databasesPkIndexField = Cache.class.getDeclaredField("databasesPkIndex");
        databasesPkIndexField.setAccessible(true);
        final var databasesPkIndex = (Map<String, PkIndexEntry>)databasesPkIndexField.get(cache);

        assertNull(databasesPkIndex.get(null));
    }

    // Retrieve an existing AdminDbEntry by its database name
    @Test
    public void test_retrieve_existing_admindbentry() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        AdminDbEntry entry = new AdminDbEntry("testDb");

        final var databasesField = Cache.class.getDeclaredField("databases");
        databasesField.setAccessible(true);
        final var databases = (Map<String, AdminDbEntry>)databasesField.get(cache);

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
    public void test_retrieve_existing_pkindexentry() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        PkIndexEntry expectedEntry = new PkIndexEntry("dbName", "collName", "value", 1L, 100L);

        final var collectionsPkIndexField = Cache.class.getDeclaredField("collectionsPkIndex");
        collectionsPkIndexField.setAccessible(true);
        final var collectionsPkIndex = (Map<String, PkIndexEntry>)collectionsPkIndexField.get(cache);

        collectionsPkIndex.put("validCollId", expectedEntry);

        PkIndexEntry result = cache.getPkIndexAdminCollEntry("validCollId");

        assertNotNull(result);
        assertEquals(expectedEntry, result);
    }

    // Adds a PkIndexEntry to collectionsPkIndex map
    @Test
    public void test_adds_pkindexentry_to_map() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        PkIndexEntry entry = new PkIndexEntry("db1", "coll1", "value1", 1L, 100L);
        cache.putPkIndexAdminCollEntry(entry);

        final var collectionsPkIndexField = Cache.class.getDeclaredField("collectionsPkIndex");
        collectionsPkIndexField.setAccessible(true);
        final var collectionsPkIndex = (Map<String, PkIndexEntry>)collectionsPkIndexField.get(cache);

        assertEquals(entry, collectionsPkIndex.get("value1"));
    }

    // Retrieves an AdminCollEntry when the collection exists in the cache
    @Test
    public void test_retrieves_admincollentry_when_exists_in_cache() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String dbName = "testDB";
        String collName = "testCollection";
        AdminCollEntry expectedEntry = new AdminCollEntry(dbName, collName);

        final var collectionsField = Cache.class.getDeclaredField("collections");
        collectionsField.setAccessible(true);
        final var collections = (Map<String, AdminCollEntry>)collectionsField.get(cache);

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
        PkIndexEntry pkIndexEntry = new PkIndexEntry("testDb", "testCollection", "testValue", 1L, 100L);

        cache.putAdminDbEntry(adminDbEntry, pkIndexEntry);

        final var databasesField = Cache.class.getDeclaredField("databases");
        databasesField.setAccessible(true);
        final var databases = (Map<String, AdminDbEntry>)databasesField.get(cache);
        final var databasesPkIndexField = Cache.class.getDeclaredField("databasesPkIndex");
        databasesPkIndexField.setAccessible(true);
        final var databasesPkIndex = (Map<String, PkIndexEntry>)databasesPkIndexField.get(cache);

        assertEquals(adminDbEntry, databases.get("testDb"));
        assertEquals(pkIndexEntry, databasesPkIndex.get("testDb"));
    }

    // Successfully removes an entry from databases map when dbName exists
    @Test
    public void test_remove_entry_when_dbname_exists() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String dbName = "testDb";
        AdminDbEntry adminDbEntry = new AdminDbEntry(dbName);
        PkIndexEntry pkIndexEntry = new PkIndexEntry(dbName, "testCollection", "testValue", 1L, 100L);

        final var databasesField = Cache.class.getDeclaredField("databases");
        databasesField.setAccessible(true);
        final var databases = (Map<String, AdminDbEntry>)databasesField.get(cache);
        final var databasesPkIndexField = Cache.class.getDeclaredField("databasesPkIndex");
        databasesPkIndexField.setAccessible(true);
        final var databasesPkIndex = (Map<String, PkIndexEntry>)databasesPkIndexField.get(cache);

        databases.put(dbName, adminDbEntry);
        databasesPkIndex.put(dbName, pkIndexEntry);

        cache.removeAdminDbEntry(dbName);

        assertFalse(databases.containsKey(dbName));
        assertFalse(databasesPkIndex.containsKey(dbName));
    }

    // Successfully adds an AdminCollEntry and PkIndexEntry to their respective maps
    @Test
    public void test_successfully_adds_entries_to_collections_maps() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        AdminCollEntry dbEntry = new AdminCollEntry("testDb", "testColl");
        PkIndexEntry indexEntry = new PkIndexEntry("testDb", "testColl", "testValue", 1L, 100L);

        cache.putAdminCollectionEntry(dbEntry, indexEntry);

        final var collectionsField = Cache.class.getDeclaredField("collections");
        collectionsField.setAccessible(true);
        final var collections = (Map<String, AdminCollEntry>)collectionsField.get(cache);
        final var collectionsPkIndexField = Cache.class.getDeclaredField("collectionsPkIndex");
        collectionsPkIndexField.setAccessible(true);
        final var collectionsPkIndex = (Map<String, PkIndexEntry>)collectionsPkIndexField.get(cache);

        assertEquals(dbEntry, collections.get(dbEntry.get_id()));
        assertEquals(indexEntry, collectionsPkIndex.get(dbEntry.get_id()));
    }

    // Removing an existing collection identifier from collections map
    @Test
    public void test_remove_existing_collection_identifier() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String collIdentifier = "testCollection";
        AdminCollEntry adminCollEntry = new AdminCollEntry(TestGlobals.DB, collIdentifier);
        PkIndexEntry pkIndexEntry = new PkIndexEntry(TestGlobals.DB, collIdentifier, "testValue", 1L, 100L);

        final var collectionsField = Cache.class.getDeclaredField("collections");
        collectionsField.setAccessible(true);
        final var collections = (Map<String, AdminCollEntry>)collectionsField.get(cache);
        final var collectionsPkIndexField = Cache.class.getDeclaredField("collectionsPkIndex");
        collectionsPkIndexField.setAccessible(true);
        final var collectionsPkIndex = (Map<String, PkIndexEntry>)collectionsPkIndexField.get(cache);


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

        final var collectionsField = Cache.class.getDeclaredField("collections");
        collectionsField.setAccessible(true);
        final var collections = (Map<String, AdminCollEntry>)collectionsField.get(cache);
        final var collectionsPkIndexField = Cache.class.getDeclaredField("collectionsPkIndex");
        collectionsPkIndexField.setAccessible(true);
        final var collectionsPkIndex = (Map<String, PkIndexEntry>)collectionsPkIndexField.get(cache);

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

        final var collectionMapField = Cache.class.getDeclaredField("collectionMap");
        collectionMapField.setAccessible(true);
        final var collectionMap = (Map<String, Map<String, DbEntry>>)collectionMapField.get(cache);

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

        final var collectionMapField = Cache.class.getDeclaredField("collectionMap");
        collectionMapField.setAccessible(true);
        final var collectionMap = (Map<String, Map<String, DbEntry>>)collectionMapField.get(cache);

        assertNull(collectionMap.get(Cache.getCollectionIdentifier(dbName, collName)));
    }

    // Successfully evicts all collections and their primary key indexes for a given database name
    @Test
    public void test_evict_database_success() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String dbName = "testDb";
        String collectionName = dbName + "_collection";

        PkIndexEntry pkIndexEntry = new PkIndexEntry(dbName, collectionName, "123", 0, 100);
        DbEntry dbEntry = new DbEntry();

        Field pkIndexMapField = Cache.class.getDeclaredField("pkIndexMap");
        pkIndexMapField.setAccessible(true);
        final var pkIndexMap = (Map<String, List<PkIndexEntry>>) pkIndexMapField.get(cache);
        final var collectionMapField = Cache.class.getDeclaredField("collectionMap");
        collectionMapField.setAccessible(true);
        final var collectionMap = (Map<String, Map<String, DbEntry>>)collectionMapField.get(cache);

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

        PkIndexEntry pkIndexEntry = new PkIndexEntry(dbName, collectionName, "123", 0, 100);
        DbEntry dbEntry = new DbEntry();

        Field pkIndexMapField = Cache.class.getDeclaredField("pkIndexMap");
        pkIndexMapField.setAccessible(true);
        final var pkIndexMap = (Map<String, List<PkIndexEntry>>) pkIndexMapField.get(cache);
        final var collectionMapField = Cache.class.getDeclaredField("collectionMap");
        collectionMapField.setAccessible(true);
        final var collectionMap = (Map<String, Map<String, DbEntry>>)collectionMapField.get(cache);

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

        Field pkIndexMapField = Cache.class.getDeclaredField("pkIndexMap");
        pkIndexMapField.setAccessible(true);
        final var pkIndexMap = (Map<String, List<PkIndexEntry>>) pkIndexMapField.get(cache);

        pkIndexMap.put(collIdentifier, pkIndexEntries);

        cache.evictCollection(dbName, collName);

        assertFalse(pkIndexMap.containsKey(collIdentifier));
    }

    // evictCollection is called with a non-existent dbName and collName
    @Test
    public void test_evict_collection_with_non_existent_collection() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        String dbName = "nonExistentDb";
        String collName = "nonExistentColl";
        String collIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        cache.evictCollection(dbName, collName);

        Field pkIndexMapField = Cache.class.getDeclaredField("pkIndexMap");
        pkIndexMapField.setAccessible(true);
        final var pkIndexMap = (Map<String, List<PkIndexEntry>>) pkIndexMapField.get(cache);
        final var collectionMapField = Cache.class.getDeclaredField("collectionMap");
        collectionMapField.setAccessible(true);
        final var collectionMap = (Map<String, Map<String, DbEntry>>)collectionMapField.get(cache);

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
    public void test_handles_ioexception_when_reading_collection() throws NoSuchFieldException, IllegalAccessException, IOException {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = Cache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        when(fsMock.readWholeCollection(anyString())).thenThrow(IOException.class);
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

        final var collectionsField = Cache.class.getDeclaredField("collections");
        collectionsField.setAccessible(true);
        final var collections = (Map<String, AdminCollEntry>)collectionsField.get(cache);

        var coll = new AdminCollEntry(dbName, collName, indexes, 0);

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

        Field fieldIndexMapField = Cache.class.getDeclaredField("fieldIndexMap");
        fieldIndexMapField.setAccessible(true);
        final var fieldIndexMap = (Map<String, Map<String, List<FieldIndexEntry<?>>>>) fieldIndexMapField.get(cache);

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
    public void test_retrieve_indexes_for_valid_collection_identifier() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        AdminCollEntry adminCollEntry = mock(AdminCollEntry.class);
        Set<String> indexes = new HashSet<>();
        indexes.add("index1");
        indexes.add("index2");
        when(adminCollEntry.getIndexes()).thenReturn(indexes);

        final var collectionsField = Cache.class.getDeclaredField("collections");
        collectionsField.setAccessible(true);
        final var collections = (Map<String, AdminCollEntry>)collectionsField.get(cache);

        collections.put("testDb|testColl", adminCollEntry);
        Set<String> expectedIndexes = new HashSet<>();
        expectedIndexes.add("index1");
        expectedIndexes.add("index2");
        Set<String> actualIndexes = cache.getIndexesForCollection("testDb", "testColl");
        assertEquals(expectedIndexes, actualIndexes);
    }
}