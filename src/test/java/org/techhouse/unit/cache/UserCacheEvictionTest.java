package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class UserCacheEvictionTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
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
        String collectionName = "myCollection";
        String collId = Cache.getCollectionIdentifier(dbName, collectionName);

        PkIndexEntry pkIndexEntry = new PkIndexEntry(dbName, collectionName, "123", 0, 100, 0);
        DbEntry dbEntry = new DbEntry();

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeColl);

        pkIndexMap.put(collId, List.of(pkIndexEntry));
        collectionMap.put(collId, Map.of("key", dbEntry));

        cache.evictDatabase(dbName);

        assertTrue(pkIndexMap.isEmpty());
        assertTrue(collectionMap.isEmpty());
    }

    // Empty db name is invalid (< 3 chars per constraints); verify it still evicts only its own entries.
    @Test
    public void test_evict_database_empty_string() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "";
        String collectionName = "someColl";
        String collId = Cache.getCollectionIdentifier(dbName, collectionName);

        PkIndexEntry pkIndexEntry = new PkIndexEntry(dbName, collectionName, "123", 0, 100, 0);
        DbEntry dbEntry = new DbEntry();

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeColl);

        pkIndexMap.put(collId, List.of(pkIndexEntry));
        collectionMap.put(collId, Map.of("key", dbEntry));

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

    @Test
    public void test_evict_collection_also_clears_fieldIndexMap() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        String collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        String indexIdentifier = Cache.getIndexIdentifier("myField", String.class);

        Map<String, List<FieldIndexEntry<?>>> innerMap = new ConcurrentHashMap<>();
        innerMap.put(indexIdentifier, List.of(new FieldIndexEntry<>(dbName, collName, "val", Set.of("id1"))));
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        TestUtils.getPrivateField(cache, "fieldIndexMap", type).put(collIdentifier, innerMap);

        cache.evictCollection(dbName, collName);

        assertFalse(TestUtils.getPrivateField(cache, "fieldIndexMap", type).containsKey(collIdentifier),
                "evictCollection must remove the collection's field index entries");
    }

    @Test
    public void test_evict_database_also_clears_fieldIndexMap() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String siblingDb = "testDbSibling";
        String collId1 = Cache.getCollectionIdentifier(dbName, "coll1");
        String collId2 = Cache.getCollectionIdentifier(dbName, "coll2");
        String siblingId = Cache.getCollectionIdentifier(siblingDb, "coll3");
        String indexId = Cache.getIndexIdentifier("f", String.class);

        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        Map<String, List<FieldIndexEntry<?>>> inner1 = new ConcurrentHashMap<>();
        inner1.put(indexId, List.of(new FieldIndexEntry<>(dbName, "coll1", "v", Set.of("id1"))));
        Map<String, List<FieldIndexEntry<?>>> inner2 = new ConcurrentHashMap<>();
        inner2.put(indexId, List.of(new FieldIndexEntry<>(dbName, "coll2", "v", Set.of("id2"))));
        Map<String, List<FieldIndexEntry<?>>> inner3 = new ConcurrentHashMap<>();
        inner3.put(indexId, List.of(new FieldIndexEntry<>(siblingDb, "coll3", "v", Set.of("id3"))));
        fieldIndexMap.put(collId1, inner1);
        fieldIndexMap.put(collId2, inner2);
        fieldIndexMap.put(siblingId, inner3);

        // Also populate collectionMap so evictDatabase can build its toRemove list.
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeColl);
        collectionMap.put(collId1, new ConcurrentHashMap<>());
        collectionMap.put(collId2, new ConcurrentHashMap<>());

        cache.evictDatabase(dbName);

        final var remaining = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        assertFalse(remaining.containsKey(collId1), "coll1 field index must be evicted");
        assertFalse(remaining.containsKey(collId2), "coll2 field index must be evicted");
        assertTrue(remaining.containsKey(siblingId), "sibling db field index must be untouched");
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

    // evictDatabase("foo") must not touch a sibling database whose name starts with "foo".
    @Test
    public void test_evictDatabase_does_not_evict_sibling_database()
            throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        final var collIdFoo = Cache.getCollectionIdentifier("foo", "coll1");
        final var collIdFoobar = Cache.getCollectionIdentifier("foobar", "coll2");

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var collectionMap = TestUtils.getPrivateField(cache, "collectionMap", typeColl);

        pkIndexMap.put(collIdFoo, List.of(new PkIndexEntry("foo", "coll1", "1", 0, 10, 0)));
        pkIndexMap.put(collIdFoobar, List.of(new PkIndexEntry("foobar", "coll2", "2", 0, 10, 0)));
        collectionMap.put(collIdFoo, new ConcurrentHashMap<>());
        collectionMap.put(collIdFoobar, new ConcurrentHashMap<>());

        cache.evictDatabase("foo");

        assertFalse(pkIndexMap.containsKey(collIdFoo), "foo|coll1 should have been evicted");
        assertFalse(collectionMap.containsKey(collIdFoo), "foo|coll1 should have been evicted");
        assertTrue(pkIndexMap.containsKey(collIdFoobar), "foobar|coll2 must not be evicted");
        assertTrue(collectionMap.containsKey(collIdFoobar), "foobar|coll2 must not be evicted");
    }
}
