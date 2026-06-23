package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AdminCache;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class AdminCacheTest {

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Loading admin data populates the databases and collections maps correctly
    @Test
    public void test_load_admin_data_populates_maps_correctly()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        AdminCache cache = new AdminCache();

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
        AdminCache cache = new AdminCache();

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

    // Retrieve PkIndexEntry for existing database name
    @Test
    public void test_retrieve_pk_index_entry_existing_dbname() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
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
        AdminCache cache = new AdminCache();

        PkIndexEntry result = cache.getPkIndexAdminDbEntry("");

        assertNull(result);
    }

    // Adding a valid PkIndexEntry to databasesPkIndex
    @Test
    public void test_add_valid_pk_index_entry() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
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
        AdminCache cache = new AdminCache();
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
        AdminCache cache = new AdminCache();

        AdminDbEntry result = cache.getAdminDbEntry("");

        assertNull(result);
    }

    // Retrieve existing PkIndexEntry for a valid collection identifier
    @Test
    public void test_retrieve_existing_pk_index_entry() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
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
        AdminCache cache = new AdminCache();
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
        AdminCache cache = new AdminCache();
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
        AdminCache cache = new AdminCache();

        AdminCollEntry result1 = cache.getAdminCollectionEntry(null, "testCollection");
        AdminCollEntry result2 = cache.getAdminCollectionEntry("testDB", null);
        AdminCollEntry result3 = cache.getAdminCollectionEntry(null, null);

        assertNull(result1);
        assertNull(result2);
        assertNull(result3);
    }

    // A collection dropped while a background index event is still in flight is no longer in the
    // cache; getIndexesForCollection must return an empty set (not throw) so background maintenance
    // becomes a clean no-op.
    @Test
    public void test_get_indexes_for_missing_collection_returns_empty() {
        AdminCache cache = new AdminCache();

        Set<String> result = cache.getIndexesForCollection("goneDb", "goneColl");

        assertNotNull(result);
        assertTrue(result.isEmpty());
        // hasIndex builds on the same method and must report false rather than throwing.
        assertFalse(cache.hasIndex("goneDb", "goneColl", "anyField"));
    }

    // When the collection exists, its registered indexes are returned as-is.
    @Test
    public void test_get_indexes_for_existing_collection_returns_indexes()
            throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        String dbName = "testDB";
        String collName = "testCollection";
        AdminCollEntry entry = new AdminCollEntry(dbName, collName);
        entry.setIndexes(Set.of("status", "score"));

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        TestUtils.getPrivateField(cache, "collections", typeColl).put(Cache.getCollectionIdentifier(dbName, collName),
                entry);

        assertEquals(Set.of("status", "score"), cache.getIndexesForCollection(dbName, collName));
        assertTrue(cache.hasIndex(dbName, collName, "status"));
        assertFalse(cache.hasIndex(dbName, collName, "missing"));
    }

    // Successfully adds AdminDbEntry and PkIndexEntry to respective maps
    @Test
    public void test_successfully_adds_entries_to_maps() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
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
        AdminCache cache = new AdminCache();
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
        AdminCache cache = new AdminCache();
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
        AdminCache cache = new AdminCache();
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
        AdminCache cache = new AdminCache();
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

    // Returns true if the specified field index exists in the collection
    @Test
    public void test_field_index_exists() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
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

    @Test
    public void test_retrieve_indexes_for_valid_collection_identifier()
            throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
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
        AdminCache cache = new AdminCache();
        long target = cache.selectPageForInsert("myDb", "myColl", 100);
        assertEquals(0L, target);
    }

    @Test
    public void test_select_page_for_insert_first_fit_picks_first_page_with_room()
            throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        final var p0 = new AdminPageEntry("myDb", "myColl", 0L);
        p0.setPageSize(Long.parseLong(System.getProperty("maxPageBytesOverride", "2097150"))); // near-full
        p0.setEntryCount(10);
        final var p1 = new AdminPageEntry("myDb", "myColl", 1L);
        p1.setPageSize(200L);
        p1.setEntryCount(1);
        final var p2 = new AdminPageEntry("myDb", "myColl", 2L);
        p2.setPageSize(Long.parseLong(System.getProperty("maxPageBytesOverride", "2097100"))); // also near-full
        p2.setEntryCount(5);

        final var list = new ArrayList<AdminPageEntry>();
        list.add(p0);
        list.add(p1);
        list.add(p2);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<AdminPageEntry>>>() {
        };
        final var pagesMap = TestUtils.getPrivateField(cache, "pages", type);
        pagesMap.put(Cache.getCollectionIdentifier("myDb", "myColl"), list);

        long target = cache.selectPageForInsert("myDb", "myColl", 100);
        assertEquals(1L, target, "Should pick first page with room");
    }

    @Test
    public void test_select_page_for_insert_allocates_new_page_when_none_fit()
            throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        final var p0 = new AdminPageEntry("myDb", "myColl", 0L);
        p0.setPageSize(2_097_150L); // 2MB-2 bytes, very near max default of 2MB
        p0.setEntryCount(10);
        final var p1 = new AdminPageEntry("myDb", "myColl", 1L);
        p1.setPageSize(2_097_150L);
        p1.setEntryCount(10);

        final var list = new ArrayList<AdminPageEntry>();
        list.add(p0);
        list.add(p1);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<AdminPageEntry>>>() {
        };
        final var pagesMap = TestUtils.getPrivateField(cache, "pages", type);
        pagesMap.put(Cache.getCollectionIdentifier("myDb", "myColl"), list);

        long target = cache.selectPageForInsert("myDb", "myColl", 100_000); // 100KB, doesn't fit anywhere
        assertEquals(2L, target, "Should allocate new page when no existing page has room");
    }

    @Test
    public void test_select_page_for_insert_with_pending_bytes() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        final var p0 = new AdminPageEntry("myDb", "myColl", 0L);
        p0.setPageSize(1_000_000L);
        p0.setEntryCount(10);

        final var list = new ArrayList<AdminPageEntry>();
        list.add(p0);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<AdminPageEntry>>>() {
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
        AdminCache cache = new AdminCache();
        cache.addAdminPageEntries("myDb", "myColl", new AdminPageEntry("myDb", "myColl", 0L));
        cache.getAdminPagePkIndexes("myDb", "myColl")
                .add(new PkIndexEntry("admin", "pages_myColl", "myDb|myColl|0", 0L, 10L, 0L));

        assertNotNull(cache.getAdminPageEntries("myDb", "myColl"));
        cache.removeAdminPageEntries("myDb", "myColl");
        assertNull(cache.getAdminPageEntries("myDb", "myColl"));
    }

    // getCollectionNamesForDatabase returns only collections belonging to the given database
    @Test
    public void test_get_collection_names_for_database() throws IOException, InterruptedException {
        TestUtils.createTestDatabaseAndCollection();
        AdminCache cache = IocContainer.get(AdminCache.class);
        List<String> names = cache.getCollectionNamesForDatabase(TestGlobals.DB);
        assertNotNull(names);
        assertTrue(names.contains(TestGlobals.COLL));
    }

    // getCollectionNamesForDatabase does not return collections from other databases
    @Test
    public void test_get_collection_names_excludes_other_databases() throws IOException, InterruptedException {
        TestUtils.createTestDatabaseAndCollection();
        AdminCache cache = IocContainer.get(AdminCache.class);
        List<String> names = cache.getCollectionNamesForDatabase("someOtherDb");
        assertNotNull(names);
        assertTrue(names.isEmpty());
    }

    // getAdminPageEntry returns the correct page entry
    @Test
    public void test_get_admin_page_entry_returns_correct_entry() {
        AdminCache cache = new AdminCache();
        cache.updatePageSizeInMemory("db", "coll", 0L, 100L);
        var entry = cache.getAdminPageEntry("db", "coll", 0L);
        assertNotNull(entry);
        assertEquals(0L, entry.getPage());
    }

    // getAdminPageEntry returns null for a page number that does not exist
    @Test
    public void test_get_admin_page_entry_returns_null_for_missing_page() {
        AdminCache cache = new AdminCache();
        cache.updatePageSizeInMemory("db", "coll", 0L, 100L);
        var entry = cache.getAdminPageEntry("db", "coll", 99L);
        assertNull(entry);
    }

    // getAdminPageEntry returns null when no pages exist for the collection
    @Test
    public void test_get_admin_page_entry_returns_null_when_no_pages() {
        AdminCache cache = new AdminCache();
        var entry = cache.getAdminPageEntry("db", "coll", 0L);
        assertNull(entry);
    }

    @Test
    public void test_load_admin_data_with_existing_users_populates_users_map() throws Exception {
        // Persist a user to disk
        final var userEntry = new org.techhouse.data.admin.AdminUserEntry("cachetest_user", "hash", false,
                new java.util.HashSet<>(), new java.util.HashMap<>(), new java.util.HashMap<>());
        org.techhouse.ops.AdminOperationHelper.saveUserEntry(userEntry);

        // Clear in-memory user maps so loadAdminData must reload from disk
        AdminCache cache = IocContainer.get(AdminCache.class);
        TestUtils.setPrivateField(cache, "users", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "usersPkIndex", new ConcurrentHashMap<>());

        // Reload — should find the persisted user
        cache.loadAdminData();

        assertNotNull(cache.getAdminUserEntry("cachetest_user"));
    }

    // loadAdminData with pre-existing databases and collections populates all maps
    @Test
    public void test_load_admin_data_with_existing_databases_and_collections() throws Exception {
        // Save a database and collection to disk
        TestUtils.createTestDatabaseAndCollection();

        // Clear the in-memory cache so loadAdminData must reload from disk
        AdminCache cache = IocContainer.get(AdminCache.class);
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
    public void test_collection_usage_pk_index_round_trip() {
        AdminCache cache = new AdminCache();
        final var pk = new PkIndexEntry(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME, "usage-id", 0, 10,
                0);
        cache.putPkIndexCollectionUsage(pk);
        assertEquals(pk, cache.getPkIndexCollectionUsage("usage-id"));
        assertTrue(cache.getCollectionUsagePkIndexes().containsKey("usage-id"));
        cache.removePkIndexCollectionUsage("usage-id");
        assertNull(cache.getPkIndexCollectionUsage("usage-id"));
    }
}
