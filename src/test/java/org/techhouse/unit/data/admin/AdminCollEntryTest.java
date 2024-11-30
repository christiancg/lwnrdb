package org.techhouse.unit.data.admin;

import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class AdminCollEntryTest {
    private static final String INDEXES_FIELD_NAME = "indexes";
    private static final String ENTRY_COUNT_FIELD_NAME = "entryCount";

    // Creating AdminCollEntry with constructor sets properties
    @Test
    public void test_default_constructor_sets_admin_names() {
        AdminCollEntry entry = new AdminCollEntry("db", "coll");
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
        assertEquals(Set.of(), entry.getIndexes());
        assertEquals(0, entry.getEntryCount());
        assertTrue(entry.get_id().contains("db"));
        assertTrue(entry.get_id().contains("coll"));
    }

    // Constructs AdminCollEntry with given dbName, collName, indexes, and entryCount
    @Test
    public void test_constructs_admin_coll_entry_with_given_parameters() {
        String dbName = "testDb";
        String collName = "testCollection";
        Set<String> indexes = new HashSet<>();
        indexes.add("index1");
        indexes.add("index2");
        int entryCount = 5;

        AdminCollEntry entry = new AdminCollEntry(dbName, collName, indexes, entryCount);

        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
        assertEquals(Cache.getCollectionIdentifier(dbName, collName), entry.get_id());
        assertEquals(indexes, entry.getIndexes());
        assertEquals(entryCount, entry.getEntryCount());
    }

    // Handles empty indexes set without errors
    @Test
    public void test_handles_empty_indexes_set() {
        String dbName = "testDb";
        String collName = "testCollection";
        Set<String> indexes = new HashSet<>();
        int entryCount = 5;

        AdminCollEntry entry = new AdminCollEntry(dbName, collName, indexes, entryCount);

        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
        assertEquals(Cache.getCollectionIdentifier(dbName, collName), entry.get_id());
        assertTrue(entry.getIndexes().isEmpty());
        assertEquals(entryCount, entry.getEntryCount());
    }

    // Converts a valid JsonObject to AdminCollEntry successfully
    @Test
    public void test_from_json_object_valid_input() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(Globals.PK_FIELD, "test_id");
        JsonArray indexesArray = new JsonArray();
        indexesArray.add("index1");
        indexesArray.add("index2");
        jsonObject.add(INDEXES_FIELD_NAME, indexesArray);
        jsonObject.addProperty(ENTRY_COUNT_FIELD_NAME, 5);

        AdminCollEntry entry = AdminCollEntry.fromJsonObject(jsonObject);

        assertEquals("test_id", entry.get_id());
        assertEquals(Set.of("index1", "index2"), entry.getIndexes());
        assertEquals(5, entry.getEntryCount());
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
    }

    // Correctly updates the indexes field with a new set of strings
    @Test
    public void test_update_indexes_with_new_set() {
        AdminCollEntry entry = new AdminCollEntry("testDb", "testCollection");
        Set<String> newIndexes = new HashSet<>(Arrays.asList("index1", "index2"));
        entry.setIndexes(newIndexes);

        JsonObject data = entry.getData();
        JsonArray indexesArray = data.get("indexes").asJsonArray();

        assertEquals(2, indexesArray.size());
        assertTrue(indexesArray.contains(new JsonString("index1")));
        assertTrue(indexesArray.contains(new JsonString("index2")));
    }

    // Updates entryCount with given value
    @Test
    public void test_set_entry_count_with_positive_value() {
        AdminCollEntry entry = new AdminCollEntry("testDb", "testCollection");
        entry.setEntryCount(10);
        assertEquals(10, entry.getEntryCount());
        JsonObject data = entry.getData();
        assertTrue(data.has("entryCount"));
        assertEquals(10, data.get("entryCount").asJsonNumber().asInteger());
    }

    // Handles negative entryCount values
    @Test
    public void test_set_entry_count_with_negative_value() {
        AdminCollEntry entry = new AdminCollEntry("testDb", "testCollection");
        entry.setEntryCount(-5);
        assertEquals(-5, entry.getEntryCount());
        JsonObject data = entry.getData();
        assertTrue(data.has("entryCount"));
        assertEquals(-5, data.get("entryCount").asJsonNumber().asInteger());
    }

    // setDatabaseName should not alter the database name
    @Test
    public void test_set_database_name_no_alteration() {
        AdminCollEntry entry = new AdminCollEntry("db", "coll");
        String changedName = "changedDatabase";
        entry.setDatabaseName(changedName);
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
    }

    // setDatabaseName should handle null values gracefully
    @Test
    public void test_set_database_name_handle_null() {
        AdminCollEntry entry = new AdminCollEntry("db", "coll");
        entry.setDatabaseName(null);
        assertNotNull(entry.getDatabaseName());
    }

    // Verify that setCollectionName does not alter any class state when invoked
    @Test
    public void test_no_state_change() {
        AdminCollEntry entry = new AdminCollEntry("db", "coll");
        String initialState = entry.toString();
        entry.setCollectionName("newName");
        String finalState = entry.toString();
        assertEquals(initialState, finalState);
    }

    // Test setCollectionName with null input to ensure no unexpected behavior
    @Test
    public void test_null_input() {
        AdminCollEntry entry = new AdminCollEntry("db", "coll");
        entry.setCollectionName(null);
        // No exception should be thrown, and no state change should occur
        assertNotNull(entry);
    }
}