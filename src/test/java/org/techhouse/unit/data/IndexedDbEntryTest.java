package org.techhouse.unit.data;

import org.junit.jupiter.api.Test;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;

import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.test.TestUtils;

import static org.junit.jupiter.api.Assertions.*;

public class IndexedDbEntryTest {
    // Convert IndexedDbEntry to DbEntry correctly with matching fields
    @Test
    public void test_convert_to_db_entry() {
        // Arrange
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("key", "value");
        IndexedDbEntry indexedDbEntry = new IndexedDbEntry();
        indexedDbEntry.set_id("123");
        indexedDbEntry.setDatabaseName("testDB");
        indexedDbEntry.setCollectionName("testCollection");
        indexedDbEntry.setData(jsonObject);

        // Act
        DbEntry dbEntry = indexedDbEntry.toDbEntry();

        // Assert
        assertEquals("123", dbEntry.get_id());
        assertEquals("testDB", dbEntry.getDatabaseName());
        assertEquals("testCollection", dbEntry.getCollectionName());
        assertEquals(jsonObject, dbEntry.getData());
    }

    // Handle null values for databaseName, collectionName, or data gracefully
    @Test
    public void test_handle_null_values() {
        // Arrange
        IndexedDbEntry indexedDbEntry = new IndexedDbEntry();
        indexedDbEntry.set_id("123");
        indexedDbEntry.setDatabaseName(null);
        indexedDbEntry.setCollectionName(null);
        indexedDbEntry.setData(null);

        // Act
        DbEntry dbEntry = indexedDbEntry.toDbEntry();

        // Assert
        assertEquals("123", dbEntry.get_id());
        assertNull(dbEntry.getDatabaseName());
        assertNull(dbEntry.getCollectionName());
        assertNull(dbEntry.getData());
    }

    // Converts data to JSON string with existing _id
    @Test
    public void test_to_file_entry_with_existing_id() {
        // Arrange
        JsonObject data = new JsonObject();
        data.addProperty("name", "test");
        String existingId = "12345";
        IndexedDbEntry entry = new IndexedDbEntry();
        entry.set_id(existingId);
        entry.setData(data);

        // Act
        String jsonResult = entry.toFileEntry();

        // Assert
        assertTrue(jsonResult.contains("\"_id\":\"12345\""));
    }

    // Handles null data gracefully
    @Test
    public void test_to_file_entry_with_null_data() {
        // Arrange
        IndexedDbEntry entry = new IndexedDbEntry();
        entry.setData(null);

        // Act & Assert
        assertThrows(NullPointerException.class, entry::toFileEntry);
    }

    // Converts IndexedDbEntry to DbEntry with matching _id
    @Test
    public void test_convert_to_dbentry_with_matching_id() {
        IndexedDbEntry indexedDbEntry = new IndexedDbEntry();
        indexedDbEntry.set_id("12345");
        indexedDbEntry.setDatabaseName("testDatabase");
        indexedDbEntry.setCollectionName("testCollection");
        JsonObject data = new JsonObject();
        data.addProperty("key", "value");
        indexedDbEntry.setData(data);

        DbEntry dbEntry = indexedDbEntry.toDbEntry();

        assertEquals("12345", dbEntry.get_id());
        assertEquals("testDatabase", dbEntry.getDatabaseName());
        assertEquals("testCollection", dbEntry.getCollectionName());
        assertEquals(data, dbEntry.getData());
    }

    // Handles null _id in IndexedDbEntry
    @Test
    public void test_handle_null_id_in_indexeddbentry() {
        IndexedDbEntry indexedDbEntry = new IndexedDbEntry();
        indexedDbEntry.set_id(null);
        indexedDbEntry.setDatabaseName("testDatabase");
        indexedDbEntry.setCollectionName("testCollection");
        JsonObject data = new JsonObject();
        data.addProperty("key", "value");
        indexedDbEntry.setData(data);

        DbEntry dbEntry = indexedDbEntry.toDbEntry();

        assertNull(dbEntry.get_id());
        assertEquals("testDatabase", dbEntry.getDatabaseName());
        assertEquals("testCollection", dbEntry.getCollectionName());
        assertEquals(data, dbEntry.getData());
    }

    // Handles null _id in IndexedDbEntry
    @Test
    public void test_getters() {
        IndexedDbEntry indexedDbEntry = new IndexedDbEntry();
        indexedDbEntry.set_id("123");
        indexedDbEntry.setDatabaseName("testDatabase");
        indexedDbEntry.setCollectionName("testCollection");
        final var pkIndexEntry = new PkIndexEntry("testDatabase", "testCollection", "123", 0, 1);
        indexedDbEntry.setIndex(pkIndexEntry);
        JsonObject data = new JsonObject();
        data.addProperty("key", "value");
        indexedDbEntry.setData(data);

        assertEquals("123", indexedDbEntry.get_id());
        assertEquals("testDatabase", indexedDbEntry.getDatabaseName());
        assertEquals("testCollection", indexedDbEntry.getCollectionName());
        assertEquals(data, indexedDbEntry.getData());
        assertEquals(pkIndexEntry, indexedDbEntry.getIndex());
    }

    // Handles null _id in IndexedDbEntry
    @Test
    public void test_ejson_is_not_null() throws NoSuchFieldException, IllegalAccessException {
        IndexedDbEntry indexedDbEntry = new IndexedDbEntry();
        final var eJson = TestUtils.getPrivateField(indexedDbEntry, "eJson", EJson.class);
        assertNotNull(eJson);
    }
}