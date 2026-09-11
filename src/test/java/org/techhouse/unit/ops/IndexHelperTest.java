package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexHelperTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Creating new index for field with primitive values (number, string, boolean)
    @Test
    public void test_create_index_with_primitive_values() throws Exception {
        // Arrange
        String dbName = TestGlobals.DB;
        String collName = TestGlobals.COLL;
        String fieldName = "testField";

        JsonObject obj1 = new JsonObject();
        obj1.addProperty(Globals.PK_FIELD, "1");
        obj1.addProperty(fieldName, 42);

        JsonObject obj2 = new JsonObject();
        obj2.addProperty(Globals.PK_FIELD, "2");
        obj2.addProperty(fieldName, "test");

        JsonObject obj3 = new JsonObject();
        obj3.addProperty(Globals.PK_FIELD, "3");
        obj3.addProperty(fieldName, true);

        Cache cache = IocContainer.get(Cache.class);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        final var adminCollPkIndexEntry = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100, 0);
        cache.putAdminCollectionEntry(adminCollEntry, adminCollPkIndexEntry);
        cache.addEntryToCache(dbName, collName, DbEntry.fromJsonObject(dbName, collName, obj1));
        cache.addEntryToCache(dbName, collName, DbEntry.fromJsonObject(dbName, collName, obj2));
        cache.addEntryToCache(dbName, collName, DbEntry.fromJsonObject(dbName, collName, obj3));

        // Act
        IndexHelper.createIndex(dbName, collName, fieldName);
        final var index = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Double.class);

        // Assert
        assertNotNull(index);
        assertEquals(1, index.size());
        final var first = index.stream().findFirst();
        assertTrue(first.isPresent());
        assertTrue(first.get().getIds().contains("1"));
    }

    // Handling null values in indexed fields
    @Test
    public void test_create_index_with_null_values() throws Exception {
        // Arrange
        String dbName = TestGlobals.DB;
        String collName = TestGlobals.COLL;
        String fieldName = "testField";

        JsonObject obj1 = new JsonObject();
        obj1.addProperty(Globals.PK_FIELD, "1");
        obj1.addProperty(fieldName, 42);

        JsonObject obj2 = new JsonObject();
        obj2.addProperty(Globals.PK_FIELD, "2");
        obj2.add(fieldName, JsonNull.INSTANCE);

        Cache cache = IocContainer.get(Cache.class);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        final var adminCollPkIndexEntry = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100, 0);
        cache.putAdminCollectionEntry(adminCollEntry, adminCollPkIndexEntry);
        cache.addEntryToCache(dbName, collName, DbEntry.fromJsonObject(dbName, collName, obj1));
        cache.addEntryToCache(dbName, collName, DbEntry.fromJsonObject(dbName, collName, obj2));

        // Act
        IndexHelper.createIndex(dbName, collName, fieldName);
        final var index = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Double.class);

        // Assert
        assertNotNull(index);
        assertEquals(1, index.size());
        final var first = index.stream().findFirst();
        assertTrue(first.isPresent());
        assertTrue(first.get().getIds().contains("1"));
    }

    // Successfully delete index file for valid database, collection and field name
    @Test
    public void test_drop_index_success() throws IOException {
        String dbName = TestGlobals.DB;
        String collName = TestGlobals.COLL;
        String fieldName = "testField";
        IndexHelper.createIndex(dbName, collName, fieldName);
        IndexHelper.dropIndex(dbName, collName, fieldName);
        Cache cache = IocContainer.get(Cache.class);
        final var index = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Double.class);
        assertNull(index);
    }

    // Return false when collection folder does not exist
    @Test
    public void test_drop_index_nonexistent_collection() {
        String dbName = TestGlobals.DB;
        String collName = "nonExistentColl";
        String fieldName = "testField";
        final var result = IndexHelper.dropIndex(dbName, collName, fieldName);
        assertFalse(result);
    }

    // Return false when collection folder does not exist
    @Test
    public void test_drop_index_existent_collection_but_no_index() {
        String dbName = TestGlobals.DB;
        String collName = TestGlobals.COLL;
        String fieldName = "testField";
        final var result = IndexHelper.dropIndex(dbName, collName, fieldName);
        assertTrue(result);
    }

    private DbEntry entryWith(String id, JsonBaseElement value) {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add("data", value);
        DbEntry e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        e.set_id(id);
        return e;
    }

    private void setupCollection(Cache cache, DbEntry... entries) {
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        final var pk = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "x", 0, 100, 0);
        cache.putAdminCollectionEntry(adminCollEntry, pk);
        for (var entry : entries) {
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        }
    }

    private static JsonObject objectValue(int n) {
        final var val = new JsonObject();
        val.addProperty("n", n);
        return val;
    }

    private static JsonArray arrayValue() {
        final var arr = new JsonArray();
        for (final var item : new String[]{"x", "y"}) {
            arr.add(item);
        }
        return arr;
    }

    // Reads the persisted hash index straight from disk. The hash index cache follows the same
    // eventually-consistent path as the scalar index cache, so verification reads the file state.
    private static List<FieldIndexEntry<String>> readHashIndex(IndexKind kind) throws IOException {
        return IocContainer.get(FileSystem.class).readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, "data",
                kind);
    }

    // createIndex builds separate Object and Array hash index files for object/array valued fields
    @Test
    public void test_create_index_with_object_and_array_values() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("o1", objectValue(1)), entryWith("o2", objectValue(1)),
                entryWith("o3", objectValue(2)), entryWith("a1", arrayValue()),
                entryWith("s1", new JsonString("scalar")));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");

        final var objIndex = readHashIndex(IndexKind.OBJECT);
        assertNotNull(objIndex);
        // Two distinct objects: {n:1} (ids o1, o2) and {n:2} (id o3)
        assertEquals(2, objIndex.size());
        final var objIds = objIndex.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("o1", "o2", "o3"), objIds);

        final var arrIndex = readHashIndex(IndexKind.ARRAY);
        assertNotNull(arrIndex);
        assertEquals(1, arrIndex.size());
        assertTrue(arrIndex.getFirst().getIds().contains("a1"));

        // The scalar value still lands in its own String index
        final var stringIndex = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "data",
                String.class);
        assertNotNull(stringIndex);
    }
}
