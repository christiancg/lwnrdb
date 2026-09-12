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

public class IndexHelperUpdateTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

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

    @Test
    public void test_update_indexes_field_value_became_null_removes_scalar_entry()
            throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("b1", "active", new JsonBoolean(true));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "active");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("active"));

        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entryWith("b1", "active", JsonNull.INSTANCE));
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "b1");

        final var boolIndex = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "active",
                Boolean.class);
        assertTrue(boolIndex == null || boolIndex.stream().noneMatch(e -> e.getIds().contains("b1")));
    }

    private static JsonObject objectValue(int n) {
        final var val = new JsonObject();
        val.addProperty("n", n);
        return val;
    }

    private static JsonArray arrayValue() {
        final var arr = new JsonArray();
        for (final var item : new String[]{"x"}) {
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

    @Test
    public void test_update_indexes_moves_id_object_to_array() throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("m1", "data", objectValue(1)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("data"));

        DbEntry changed = entryWith("m1", "data", arrayValue());
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, changed);
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, changed.get_id());

        final var objIndex = readHashIndex(IndexKind.OBJECT);
        assertTrue(objIndex == null || objIndex.stream().noneMatch(e -> e.getIds().contains("m1")));
        final var arrIndex = readHashIndex(IndexKind.ARRAY);
        assertNotNull(arrIndex);
        assertTrue(arrIndex.stream().anyMatch(e -> e.getIds().contains("m1")));
    }

    @Test
    public void test_update_indexes_deleted_removes_object_entry() throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("d1", "data", objectValue(7));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("data"));

        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "d1");
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "d1");

        final var objIndex = readHashIndex(IndexKind.OBJECT);
        assertTrue(objIndex == null || objIndex.stream().noneMatch(e -> e.getIds().contains("d1")));
    }

    @Test
    public void test_update_indexes_object_value_joins_existing_hash_entry() throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("o1", "data", objectValue(1)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("data"));

        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entryWith("o2", "data", objectValue(1)));
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "o2");

        final var objIndex = readHashIndex(IndexKind.OBJECT);
        assertNotNull(objIndex);
        assertEquals(1, objIndex.size());
        assertEquals(Set.of("o1", "o2"), objIndex.getFirst().getIds());
    }

    @Test
    public void test_update_indexes_custom_value_changed_to_plain_string_removes_custom_entry()
            throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("ct1", "startTime", new JsonTime("#time(08:00:00)"));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "startTime");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("startTime"));

        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL,
                entryWith("ct1", "startTime", new JsonString("not-a-time-anymore")));
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "ct1");

        final var timeIndex = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "startTime",
                JsonTime.class);
        assertTrue(timeIndex == null || timeIndex.stream().noneMatch(e -> e.getIds().contains("ct1")));

        final var stringIndex = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "startTime",
                String.class);
        assertNotNull(stringIndex);
        assertTrue(stringIndex.stream().anyMatch(e -> e.getIds().contains("ct1")));
    }

    @Test
    public void test_update_indexes_new_doc_with_matching_existing_custom_value_joins_entry()
            throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("ct1", "startTime", new JsonTime("#time(08:00:00)"));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "startTime");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("startTime"));

        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL,
                entryWith("ct2", "startTime", new JsonTime("#time(08:00:00)")));
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "ct2");

        final var timeIndex = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "startTime",
                JsonTime.class);
        assertNotNull(timeIndex);
        assertEquals(1, timeIndex.size());
        assertEquals(Set.of("ct1", "ct2"), timeIndex.getFirst().getIds());
    }

    @Test
    public void test_update_indexes_boolean_to_number_type_change_removes_boolean_entry()
            throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("b1", "active", new JsonBoolean(true));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "active");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("active"));

        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entryWith("b1", "active", new JsonNumber(1)));
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "b1");

        final var boolIndex = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "active",
                Boolean.class);
        assertTrue(boolIndex == null || boolIndex.stream().noneMatch(e -> e.getIds().contains("b1")));

        final var numberIndex = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "active",
                Double.class);
        assertNotNull(numberIndex);
        assertTrue(numberIndex.stream().anyMatch(e -> e.getIds().contains("b1")));
    }

    @Test
    public void test_update_indexes_first_custom_value_for_field_creates_entry()
            throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("m1", "mixedField", new JsonNumber(5));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "mixedField");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("mixedField"));

        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL,
                entryWith("m2", "mixedField", new JsonTime("#time(08:00:00)")));
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "m2");

        final var timeIndex = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "mixedField",
                JsonTime.class);
        assertNotNull(timeIndex);
        assertTrue(timeIndex.stream().anyMatch(e -> e.getIds().contains("m2")));
    }

    @Test
    public void test_update_indexes_first_boolean_value_for_field_creates_entry()
            throws IOException, InterruptedException {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("m1", "mixedField2", new JsonTime("#time(08:00:00)"));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "mixedField2");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("mixedField2"));

        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entryWith("m2", "mixedField2", new JsonBoolean(true)));
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "m2");

        final var boolIndex = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "mixedField2",
                Boolean.class);
        assertNotNull(boolIndex);
        assertTrue(boolIndex.stream().anyMatch(e -> e.getIds().contains("m2")));
    }
}
