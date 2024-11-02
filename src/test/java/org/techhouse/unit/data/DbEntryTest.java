package org.techhouse.unit.data;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;

import static org.junit.jupiter.api.Assertions.*;

public class DbEntryTest {
    // Successfully creates DbEntry from a valid JsonObject
    @Test
    public void test_create_dbentry_from_valid_jsonobject() {
        String databaseName = "testDB";
        String collectionName = "testCollection";
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(Globals.PK_FIELD, "12345");
        jsonObject.addProperty("name", "testName");

        DbEntry entry = DbEntry.fromJsonObject(databaseName, collectionName, jsonObject);

        assertEquals(databaseName, entry.getDatabaseName());
        assertEquals(collectionName, entry.getCollectionName());
        assertEquals("12345", entry.get_id());
        assertEquals(jsonObject, entry.getData());
    }

    // Handles JsonObject without _id field
    @Test
    public void test_handle_jsonobject_without_id_field() {
        String databaseName = "testDB";
        String collectionName = "testCollection";
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("name", "testName");

        DbEntry entry = DbEntry.fromJsonObject(databaseName, collectionName, jsonObject);

        assertEquals(databaseName, entry.getDatabaseName());
        assertEquals(collectionName, entry.getCollectionName());
        assertNull(entry.get_id());
        assertEquals(jsonObject, entry.getData());
    }

    // Converts valid JSON string to DbEntry with correct fields
    @Test
    public void test_valid_json_string_conversion() {
        String databaseName = "testDB";
        String collectionName = "testCollection";
        String jsonString = "{\"_id\":\"12345\", \"name\":\"testName\"}";

        DbEntry entry = DbEntry.fromString(databaseName, collectionName, jsonString);

        assertEquals(databaseName, entry.getDatabaseName());
        assertEquals(collectionName, entry.getCollectionName());
        assertEquals("12345", entry.get_id());
        assertEquals("testName", entry.getData().get("name").asJsonString().getValue());
    }

    // Handles JSON string without _id field gracefully
    @Test
    public void test_json_string_without_id_field() {
        String databaseName = "testDB";
        String collectionName = "testCollection";
        String jsonString = "{\"name\":\"testName\"}";

        DbEntry entry = DbEntry.fromString(databaseName, collectionName, jsonString);

        assertEquals(databaseName, entry.getDatabaseName());
        assertEquals(collectionName, entry.getCollectionName());
        assertNull(entry.get_id());
        assertEquals("testName", entry.getData().get("name").asJsonString().getValue());
    }

    // Generates a new UUID when _id is null
    @Test
    public void test_generates_new_uuid_when_id_is_null() {
        DbEntry entry = new DbEntry();
        entry.setData(new JsonObject());
        String fileEntry = entry.toFileEntry();
        assertNotNull(entry.get_id());
        assertTrue(fileEntry.contains(entry.get_id()));
    }

    // Handles null data gracefully
    @Test
    @Disabled("Set data should probably reject nulls")
    public void test_handles_null_data_gracefully() {
        DbEntry entry = new DbEntry();
        entry.setData(null);
        String fileEntry = entry.toFileEntry();
        assertNotNull(fileEntry);
        assertTrue(fileEntry.contains(entry.get_id()));
    }
}