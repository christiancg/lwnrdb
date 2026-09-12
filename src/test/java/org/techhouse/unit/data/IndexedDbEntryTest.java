package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.JsonDocumentEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.test.TestUtils;

public class IndexedDbEntryTest {
    @Test
    public void test_convert_to_db_entry() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("key", "value");
        IndexedDbEntry indexedDbEntry = new IndexedDbEntry();
        indexedDbEntry.set_id("123");
        indexedDbEntry.setDatabaseName("testDB");
        indexedDbEntry.setCollectionName("testCollection");
        indexedDbEntry.setData(jsonObject);

        DbEntry dbEntry = indexedDbEntry.toDbEntry();

        assertEquals("123", dbEntry.get_id());
        assertEquals("testDB", dbEntry.getDatabaseName());
        assertEquals("testCollection", dbEntry.getCollectionName());
        assertEquals(jsonObject, dbEntry.getData());
    }

    @Test
    public void test_handle_null_values() {
        IndexedDbEntry indexedDbEntry = new IndexedDbEntry();
        indexedDbEntry.set_id("123");
        indexedDbEntry.setDatabaseName(null);
        indexedDbEntry.setCollectionName(null);
        indexedDbEntry.setData(null);

        DbEntry dbEntry = indexedDbEntry.toDbEntry();

        assertEquals("123", dbEntry.get_id());
        assertNull(dbEntry.getDatabaseName());
        assertNull(dbEntry.getCollectionName());
        assertNull(dbEntry.getData());
    }

    @Test
    public void test_to_file_entry_with_existing_id() {
        JsonObject data = new JsonObject();
        data.addProperty("name", "test");
        String existingId = "12345";
        IndexedDbEntry entry = new IndexedDbEntry();
        entry.set_id(existingId);
        entry.setData(data);

        String jsonResult = entry.toFileEntry();

        assertTrue(jsonResult.contains("\"_id\":\"12345\""));
    }

    @Test
    public void test_to_file_entry_with_null_data() {
        IndexedDbEntry entry = new IndexedDbEntry();
        entry.setData(null);

        assertThrows(NullPointerException.class, entry::toFileEntry);
    }

    @Test
    public void test_convert_to_db_entry_with_matching_id() {
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

    @Test
    public void test_handle_null_id_in_indexed_db_entry() {
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

    @Test
    public void test_getters() {
        IndexedDbEntry indexedDbEntry = new IndexedDbEntry();
        indexedDbEntry.set_id("123");
        indexedDbEntry.setDatabaseName("testDatabase");
        indexedDbEntry.setCollectionName("testCollection");
        final var pkIndexEntry = new PkIndexEntry("testDatabase", "testCollection", "123", 0, 1, 0);
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

    @Test
    public void test_e_json_is_not_null() throws NoSuchFieldException, IllegalAccessException {
        final var eJson = TestUtils.getPrivateStaticField(JsonDocumentEntry.class, "eJson", EJson.class);
        assertNotNull(eJson);
    }

    @Test
    public void test_equals_same_instance() {
        IndexedDbEntry entry = new IndexedDbEntry();
        entry.set_id("id1");
        assertEquals(entry, entry);
    }

    @Test
    public void test_equals_symmetric() {
        IndexedDbEntry entry1 = new IndexedDbEntry();
        entry1.set_id("id1");
        entry1.setDatabaseName("db");
        entry1.setCollectionName("coll");

        IndexedDbEntry entry2 = new IndexedDbEntry();
        entry2.set_id("id1");
        entry2.setDatabaseName("db");
        entry2.setCollectionName("coll");

        assertEquals(entry1, entry2);
        assertEquals(entry2, entry1);
    }

    @Test
    public void test_equals_null_returns_false() {
        IndexedDbEntry entry = new IndexedDbEntry();
        entry.set_id("id1");
        assertNotEquals(null, entry);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        IndexedDbEntry entry = new IndexedDbEntry();
        entry.set_id("id1");
        assertNotEquals("notAnEntry", entry);
    }

    @Test
    public void test_equals_different_id_returns_false() {
        IndexedDbEntry entry1 = new IndexedDbEntry();
        entry1.set_id("id1");
        IndexedDbEntry entry2 = new IndexedDbEntry();
        entry2.set_id("id2");
        assertNotEquals(entry1, entry2);
    }

    @Test
    public void test_hashCode_same_values_equal() {
        IndexedDbEntry entry1 = new IndexedDbEntry();
        entry1.set_id("id1");
        entry1.setDatabaseName("db");

        IndexedDbEntry entry2 = new IndexedDbEntry();
        entry2.set_id("id1");
        entry2.setDatabaseName("db");

        assertEquals(entry1.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_hashCode_different_id_differs() {
        IndexedDbEntry entry1 = new IndexedDbEntry();
        entry1.set_id("id1");
        IndexedDbEntry entry2 = new IndexedDbEntry();
        entry2.set_id("id2");
        assertNotEquals(entry1.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        IndexedDbEntry entry = new IndexedDbEntry();
        entry.set_id("id1");
        assertNotNull(entry.toString());
    }

    @Test
    public void test_previousByteSize_getter_setter() {
        IndexedDbEntry entry = new IndexedDbEntry();
        entry.setPreviousByteSize(512L);
        assertEquals(512L, entry.getPreviousByteSize());
    }
}
