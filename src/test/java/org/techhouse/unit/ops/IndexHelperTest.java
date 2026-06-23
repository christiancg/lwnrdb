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
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
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

    // Successfully updates indexes for both inserted and updated entries
    @Test
    public void test_updates_indexes_for_inserted_and_updated_entries() throws IOException, InterruptedException {
        String dbName = TestGlobals.DB;
        String collName = TestGlobals.COLL;
        String fieldName = "testField";

        JsonObject obj1 = new JsonObject();
        obj1.addProperty(Globals.PK_FIELD, "1");
        obj1.addProperty(fieldName, 42);

        Cache cache = IocContainer.get(Cache.class);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        final var adminCollPkIndexEntry = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100, 0);
        cache.putAdminCollectionEntry(adminCollEntry, adminCollPkIndexEntry);
        final var dbEntry1 = DbEntry.fromJsonObject(dbName, collName, obj1);
        cache.addEntryToCache(dbName, collName, dbEntry1);

        IndexHelper.createIndex(dbName, collName, fieldName);

        JsonObject obj2 = new JsonObject();
        obj2.addProperty(Globals.PK_FIELD, "2");
        obj2.addProperty(fieldName, 10);
        final var dbEntry2 = DbEntry.fromJsonObject(dbName, collName, obj2);
        cache.addEntryToCache(dbName, collName, dbEntry2);
        obj1.addProperty(fieldName, 1);
        cache.addEntryToCache(dbName, collName, dbEntry1);

        final var collEntry = cache.getAdminCollectionEntry(dbName, collName);
        collEntry.setIndexes(Set.of(fieldName));

        IndexHelper.bulkUpdateIndexes(dbName, collName, List.of(dbEntry2.get_id(), dbEntry1.get_id()));

        final var index = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Double.class);
        assertNotNull(index);
        assertEquals(2, index.size());
        final var entriesWith1AsValue = index.stream().filter(e -> e.getValue() == 1).toList();
        assertEquals(1, entriesWith1AsValue.size());
        final var entriesWith10AsValue = index.stream().filter(e -> e.getValue() == 10).toList();
        assertEquals(1, entriesWith10AsValue.size());
        final var entriesWith42AsValue = index.stream().filter(e -> e.getValue() == 42).toList();
        assertEquals(0, entriesWith42AsValue.size());
    }

    private DbEntry entryWith(String id, String field, JsonBaseElement value) {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(field, value);
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

    // updateIndexes indexes a String-valued field for a CREATED event
    @Test
    public void test_update_indexes_string_field() {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("s1", "tag", new JsonString("alpha"));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "tag");

        final var adminColl = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        adminColl.setIndexes(Set.of("tag"));

        DbEntry newEntry = entryWith("s2", "tag", new JsonString("beta"));
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, newEntry);
        assertDoesNotThrow(() -> IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, newEntry.get_id()));
    }

    // updateIndexes indexes a Boolean-valued field for a CREATED event
    @Test
    public void test_update_indexes_boolean_field() {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("b1", "active", new JsonBoolean(true));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "active");

        final var adminColl = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        adminColl.setIndexes(Set.of("active"));

        DbEntry newEntry = entryWith("b2", "active", new JsonBoolean(false));
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, newEntry);
        assertDoesNotThrow(() -> IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, newEntry.get_id()));
    }

    // updateIndexes indexes a custom type (JsonTime) field for a CREATED event
    @Test
    public void test_update_indexes_custom_type_field() throws Exception {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("ct1", "startTime", new JsonTime("#time(08:00:00)"));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "startTime");

        final var adminColl = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        adminColl.setIndexes(Set.of("startTime"));

        DbEntry newEntry = entryWith("ct2", "startTime", new JsonTime("#time(09:00:00)"));
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, newEntry);
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, newEntry.get_id());

        final var index = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "startTime",
                JsonTime.class);
        assertNotNull(index);
        assertFalse(index.isEmpty());
    }

    // updateIndexes with DELETED event removes an entry from the index
    @Test
    public void test_update_indexes_deleted_event_removes_entry() throws Exception {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("del1", "score", new JsonNumber(99));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "score");

        final var adminColl = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        adminColl.setIndexes(Set.of("score"));

        // Simulate a committed delete: the document is gone from the cache/PK index, so the
        // order-independent re-read sees it as absent and removes it from the index.
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "del1");
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "del1");

        final var index = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "score",
                Double.class);
        assertTrue(index == null || index.stream().noneMatch(e -> e.getIds().contains("del1")));
    }

    // getIndexEntriesForField returns null when the field has no index (caller falls back to scan)
    @Test
    public void test_get_index_entries_for_field_returns_null_when_no_index() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("n1", "tag", new JsonString("alpha"));
        setupCollection(cache, entry);
        // No index created on "tag"
        assertNull(IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "tag"));
    }

    // getIndexEntriesForField returns all entries (value -> ids) for an indexed field
    @Test
    public void test_get_index_entries_for_field_returns_entries_when_indexed() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("n1", "tag", new JsonString("alpha")),
                entryWith("n2", "tag", new JsonString("beta")), entryWith("n3", "tag", new JsonString("alpha")));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "tag");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("tag"));

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "tag");
        assertNotNull(entries);
        // Two distinct values: alpha (ids n1, n3) and beta (id n2)
        assertEquals(2, entries.size());
        final var allIds = entries.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("n1", "n2", "n3"), allIds);
    }

    private static JsonObject objectValue(int n) {
        final var val = new JsonObject();
        val.addProperty("n", n);
        return val;
    }

    private static JsonArray arrayValue(String... items) {
        final var arr = new JsonArray();
        for (var item : items) {
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
        setupCollection(cache, entryWith("o1", "data", objectValue(1)), entryWith("o2", "data", objectValue(1)),
                entryWith("o3", "data", objectValue(2)), entryWith("a1", "data", arrayValue("x", "y")),
                entryWith("s1", "data", new JsonString("scalar")));
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

    // updateIndexes adds an object value to the Object hash index for a CREATED event
    @Test
    public void test_update_indexes_object_value() throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("o1", "data", objectValue(1)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("data"));

        DbEntry newEntry = entryWith("o2", "data", objectValue(2));
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, newEntry);
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, newEntry.get_id());

        final var objIndex = readHashIndex(IndexKind.OBJECT);
        assertNotNull(objIndex);
        final var ids = objIndex.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.contains("o1"));
        assertTrue(ids.contains("o2"));
    }

    // updateIndexes moves an id from the Object index to the Array index on an object->array change
    @Test
    public void test_update_indexes_moves_id_object_to_array() throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("m1", "data", objectValue(1)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("data"));

        DbEntry changed = entryWith("m1", "data", arrayValue("x"));
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, changed);
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, changed.get_id());

        final var objIndex = readHashIndex(IndexKind.OBJECT);
        assertTrue(objIndex == null || objIndex.stream().noneMatch(e -> e.getIds().contains("m1")));
        final var arrIndex = readHashIndex(IndexKind.ARRAY);
        assertNotNull(arrIndex);
        assertTrue(arrIndex.stream().anyMatch(e -> e.getIds().contains("m1")));
    }

    // updateIndexes with a DELETED event removes the id from the Object hash index
    @Test
    public void test_update_indexes_deleted_removes_object_entry() throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("d1", "data", objectValue(7));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("data"));

        // Simulate a committed delete: the document is gone, so the re-read removes it from the index.
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "d1");
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "d1");

        final var objIndex = readHashIndex(IndexKind.OBJECT);
        assertTrue(objIndex == null || objIndex.stream().noneMatch(e -> e.getIds().contains("d1")));
    }

    // indexValueToElement converts each stored value kind back to its wire element
    @Test
    public void test_index_value_to_element_for_all_value_kinds() {
        // Integral numbers normalize so they compare/hash equal to a document-read integer
        final var numberElement = IndexHelper.indexValueToElement(42.0);
        assertTrue(numberElement.isJsonNumber());
        assertEquals(42, numberElement.asJsonNumber().asInteger());
        assertEquals(new JsonNumber(42), numberElement);

        // Non-integral numbers stay as doubles
        final var doubleElement = IndexHelper.indexValueToElement(5.5);
        assertTrue(doubleElement.isJsonNumber());
        assertEquals(5.5, doubleElement.asJsonNumber().getValue().doubleValue());

        final var stringElement = IndexHelper.indexValueToElement("hello");
        assertTrue(stringElement.isJsonString());
        assertEquals("hello", stringElement.asJsonString().getValue());

        final var booleanElement = IndexHelper.indexValueToElement(Boolean.TRUE);
        assertTrue(booleanElement.isJsonBoolean());
        assertTrue(booleanElement.asJsonBoolean().getValue());

        final var custom = new JsonTime("#time(10:00:00)");
        assertSame(custom, IndexHelper.indexValueToElement(custom));

        assertSame(JsonNull.INSTANCE, IndexHelper.indexValueToElement(null));
        assertSame(JsonNull.INSTANCE, IndexHelper.indexValueToElement(JsonNull.INSTANCE));
    }
}
