package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
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

    @Test
    public void test_load_admin_data_populates_maps_correctly()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();

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

        Assertions.assertFalse(databases.isEmpty());
        Assertions.assertFalse(collections.isEmpty());
    }

    @Test
    public void test_load_admin_data_when_file_system_is_empty()
            throws IOException, IllegalAccessException, NoSuchFieldException {
        AdminCache cache = new AdminCache();

        cache.loadAdminData();

        final var typeDbs = new ReflectionUtils.TypeToken<Map<String, AdminDbEntry>>() {
        };
        final var databases = TestUtils.getPrivateField(cache, "databases", typeDbs);
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);
        Assertions.assertTrue(databases.isEmpty());
        Assertions.assertTrue(collections.isEmpty());
    }

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

    @Test
    public void test_retrieve_pk_index_entry_empty_dbname() {
        AdminCache cache = new AdminCache();

        PkIndexEntry result = cache.getPkIndexAdminDbEntry("");

        assertNull(result);
    }

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

    @Test
    public void test_empty_database_name() {
        AdminCache cache = new AdminCache();

        AdminDbEntry result = cache.getAdminDbEntry("");

        assertNull(result);
    }

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

    // A collection dropped mid-event is no longer cached; this must answer empty rather than throw, so
    // background maintenance becomes a clean no-op.
    @Test
    public void test_get_indexes_for_missing_collection_returns_empty() {
        AdminCache cache = new AdminCache();

        Set<String> result = cache.getIndexesForCollection("goneDb", "goneColl");

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertFalse(cache.hasIndex("goneDb", "goneColl", "anyField"));
    }

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
    public void test_get_collection_names_for_database() throws IOException, InterruptedException {
        TestUtils.createTestDatabaseAndCollection();
        AdminCache cache = IocContainer.get(AdminCache.class);
        List<String> names = cache.getCollectionNamesForDatabase(TestGlobals.DB);
        assertNotNull(names);
        assertTrue(names.contains(TestGlobals.COLL));
    }

    @Test
    public void test_get_collection_names_excludes_other_databases() throws IOException, InterruptedException {
        TestUtils.createTestDatabaseAndCollection();
        AdminCache cache = IocContainer.get(AdminCache.class);
        List<String> names = cache.getCollectionNamesForDatabase("someOtherDb");
        assertNotNull(names);
        assertTrue(names.isEmpty());
    }

    @Test
    public void test_load_admin_data_with_existing_users_populates_users_map() throws Exception {
        final var userEntry = new org.techhouse.data.admin.AdminUserEntry("cachetest_user", "hash", false,
                new java.util.HashSet<>(), new java.util.HashMap<>(), new java.util.HashMap<>());
        org.techhouse.ops.AdminOperationHelper.saveUserEntry(userEntry);

        AdminCache cache = IocContainer.get(AdminCache.class);
        TestUtils.setPrivateField(cache, "users", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "usersPkIndex", new ConcurrentHashMap<>());

        cache.loadAdminData();

        assertNotNull(cache.getAdminUserEntry("cachetest_user"));
    }

    @Test
    public void test_load_admin_data_with_existing_databases_and_collections() throws Exception {
        TestUtils.createTestDatabaseAndCollection();

        AdminCache cache = IocContainer.get(AdminCache.class);
        TestUtils.setPrivateField(cache, "databases", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "collections", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "databasesPkIndex", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "collectionsPkIndex", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(TestUtils.pageCacheOf(cache), "pages", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(TestUtils.pageCacheOf(cache), "pagesPkIndexes", new ConcurrentHashMap<>());

        cache.loadAdminData();

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
