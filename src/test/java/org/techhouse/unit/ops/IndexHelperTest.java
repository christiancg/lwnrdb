package org.techhouse.unit.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
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
        final var adminCollPkIndexEntry = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100);
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
        final var adminCollPkIndexEntry = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100);
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
        final var adminCollPkIndexEntry = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100);
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
}