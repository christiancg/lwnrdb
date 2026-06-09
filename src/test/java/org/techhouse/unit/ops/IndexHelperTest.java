package org.techhouse.unit.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.*;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class IndexHelperTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws InterruptedException, IOException, NoSuchFieldException, IllegalAccessException {
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
        final var adminCollPkIndexEntry = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100,0);
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
        final var adminCollPkIndexEntry = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100,0);
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
        final var adminCollPkIndexEntry = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100,0);
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

        IndexHelper.bulkUpdateIndexes(dbName, collName, List.of(dbEntry2), List.of(dbEntry1));

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
    public void test_update_indexes_string_field() throws Exception {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("s1", "tag", new JsonString("alpha"));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "tag");

        final var adminColl = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        adminColl.setIndexes(Set.of("tag"));

        DbEntry newEntry = entryWith("s2", "tag", new JsonString("beta"));
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, newEntry);
        assertDoesNotThrow(() -> IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, newEntry, EventType.CREATED));
    }

    // updateIndexes indexes a Boolean-valued field for a CREATED event
    @Test
    public void test_update_indexes_boolean_field() throws Exception {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("b1", "active", new JsonBoolean(true));
        setupCollection(cache, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "active");

        final var adminColl = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        adminColl.setIndexes(Set.of("active"));

        DbEntry newEntry = entryWith("b2", "active", new JsonBoolean(false));
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, newEntry);
        assertDoesNotThrow(() -> IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, newEntry, EventType.CREATED));
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
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, newEntry, EventType.CREATED);

        final var index = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "startTime", JsonTime.class);
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

        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, entry, EventType.DELETED);

        final var index = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "score", Double.class);
        assertTrue(index == null || index.stream().noneMatch(e -> e.getIds().contains("del1")));
    }
}