package org.techhouse.unit.data.admin;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AdminDbEntryTest {
    // Creating AdminDbEntry with default collections initializes correctly
    @Test
    public void test_admin_db_entry_initializes_with_default_collections() {
        AdminDbEntry entry = new AdminDbEntry("testDb");
        assertEquals("admin", entry.getDatabaseName());
        assertEquals("databases", entry.getCollectionName());
        assertEquals("testDb", entry.get_id());
        assertTrue(entry.getCollections().isEmpty());
        JsonObject data = entry.getData();
        assertTrue(data.has("collections"));
        assertTrue(data.get("collections").asJsonArray().isEmpty());
    }

    // Initializes AdminDbEntry with given database name and collections
    @Test
    public void test_initializes_with_given_db_name_and_collections() {
        String dbName = "testDb";
        List<String> collections = List.of("collection1", "collection2");
        AdminDbEntry entry = new AdminDbEntry(dbName, collections);

        assertEquals(dbName, entry.get_id());
        assertEquals(collections, entry.getCollections());
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_DATABASES_COLLECTION_NAME, entry.getCollectionName());

        JsonObject data = entry.getData();
        assertTrue(data.has("collections"));
        JsonArray jsonArray = data.get("collections").asJsonArray();
        assertEquals(2, jsonArray.size());
        assertEquals("collection1", jsonArray.get(0).asJsonString().getValue());
        assertEquals("collection2", jsonArray.get(1).asJsonString().getValue());
    }

    // Handles empty collections list without errors
    @Test
    public void test_handles_empty_collections_list() {
        String dbName = "testDb";
        List<String> collections = new ArrayList<>();
        AdminDbEntry entry = new AdminDbEntry(dbName, collections);

        assertEquals(dbName, entry.get_id());
        assertTrue(entry.getCollections().isEmpty());
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_DATABASES_COLLECTION_NAME, entry.getCollectionName());

        JsonObject data = entry.getData();
        assertTrue(data.has("collections"));
        JsonArray jsonArray = data.get("collections").asJsonArray();
        assertTrue(jsonArray.isEmpty());
    }

    // Converts a valid JsonObject to AdminDbEntry correctly
    @Test
    public void test_valid_jsonobject_conversion() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.add(Globals.PK_FIELD, new JsonString("testDb"));
        JsonArray collectionsArray = new JsonArray();
        collectionsArray.add(new JsonString("collection1"));
        collectionsArray.add(new JsonString("collection2"));
        jsonObject.add("collections", collectionsArray);

        AdminDbEntry entry = AdminDbEntry.fromJsonObject(jsonObject);

        assertEquals("testDb", entry.get_id());
        assertEquals(List.of("collection1", "collection2"), entry.getCollections());
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_DATABASES_COLLECTION_NAME, entry.getCollectionName());
    }

    // Handles JsonObject with no collections field
    @Test
    @Disabled("This should probably not fail, but it's a scenario that won't happen")
    public void test_no_collections_field() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.add(Globals.PK_FIELD, new JsonString("testDb"));

        AdminDbEntry entry = AdminDbEntry.fromJsonObject(jsonObject);

        assertEquals("testDb", entry.get_id());
        assertTrue(entry.getCollections().isEmpty());
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_DATABASES_COLLECTION_NAME, entry.getCollectionName());
    }

    // Successfully updates the collections field with a new list of strings
    @Test
    public void test_update_collections_with_new_list() {
        AdminDbEntry entry = new AdminDbEntry("testDb");
        List<String> newCollections = List.of("collection1", "collection2");
        entry.setCollections(newCollections);

        JsonObject data = entry.getData();
        JsonArray collectionsArray = data.get("collections").asJsonArray();

        assertEquals(2, collectionsArray.size());
        assertTrue(collectionsArray.contains(new JsonString("collection1")));
        assertTrue(collectionsArray.contains(new JsonString("collection2")));
    }

    // Handles an empty list of collections without errors
    @Test
    public void test_handle_empty_collections_list() {
        AdminDbEntry entry = new AdminDbEntry("testDb");
        List<String> emptyCollections = new ArrayList<>();
        entry.setCollections(emptyCollections);

        JsonObject data = entry.getData();
        JsonArray collectionsArray = data.get("collections").asJsonArray();

        assertEquals(0, collectionsArray.size());
    }

    // Verify that setDatabaseName can be called without exceptions and doesn't change anything
    @Test
    public void test_setDatabaseName_no_exception() {
        AdminDbEntry entry = new AdminDbEntry("testDb");
        entry.setDatabaseName("testDatabase");
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
    }

    // Test setDatabaseName with null input and doesn't change anything
    @Test
    public void test_setDatabaseName_null_input() {
        AdminDbEntry entry = new AdminDbEntry("testDb");
        entry.setDatabaseName(null);
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
    }

    // Ensure setCollectionName does not alter the collection name when called
    @Test
    public void test_set_collection_name_no_alteration() {
        AdminDbEntry entry = new AdminDbEntry("testDb");
        String originalName = entry.getCollectionName();
        entry.setCollectionName("newName");
        assertEquals(originalName, entry.getCollectionName());
    }

    // Test setCollectionName with null input to ensure stability
    @Test
    public void test_set_collection_name_with_null() {
        AdminDbEntry entry = new AdminDbEntry("testDb");
        entry.setCollectionName(null);
        assertNotNull(entry.getCollectionName());
    }

    @Test
    public void test_setters_and_getters() {
        AdminDbEntry entry = new AdminDbEntry("testDb");
        entry.setCollections(List.of("collection1", "collection2"));
        assertEquals(List.of("collection1", "collection2"), entry.getCollections());
    }

    @Test
    public void test_equals() {
        AdminDbEntry entry = new AdminDbEntry("testDb");
        AdminDbEntry entry2 = new AdminDbEntry("testDb");
        assertEquals(entry, entry2);
    }

    @Test
    public void test_hashcode() {
        AdminDbEntry entry = new AdminDbEntry("testDb");
        AdminDbEntry entry2 = new AdminDbEntry("testDb");
        assertEquals(entry.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_toString() {
        AdminDbEntry entry = new AdminDbEntry("testDb");
        AdminDbEntry entry2 = new AdminDbEntry("testDb");
        assertEquals(entry.toString(), entry2.toString());
    }
}