package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;

public class DbEntryTest {
    @Test
    public void test_create_db_entry_from_valid_json_object() {
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

    @Test
    public void test_handle_json_object_without_id_field() {
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

    @Test
    public void test_generates_new_uuid_when_id_is_null() {
        DbEntry entry = new DbEntry();
        entry.setData(new JsonObject());
        String fileEntry = entry.toFileEntry();
        assertNotNull(entry.get_id());
        assertTrue(fileEntry.contains(entry.get_id()));
    }

    @Test
    public void test_byte_size_returns_utf8_byte_length() {
        DbEntry entry = new DbEntry();
        entry.set_id("id");
        JsonObject data = new JsonObject();
        data.addProperty("emoji", "héllo");
        entry.setData(data);
        int size = entry.byteSize();
        String expected = entry.toFileEntry() + Globals.NEWLINE;
        assertEquals(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, size);
        assertTrue(size > expected.length(), "Multi-byte chars should make byte count exceed char count");
    }

    @Test
    public void test_byte_size_is_zero_when_data_null() {
        DbEntry entry = new DbEntry();
        assertEquals(0, entry.byteSize());
    }

    private static DbEntry largeEntry() {
        final var data = new JsonObject();
        final var rows = new JsonArray();
        for (var i = 0; i < 12_000; i++) {
            final var row = new JsonObject();
            row.addProperty("i", i);
            row.addProperty("name", "row-" + i + "-日本\"q\"\n");
            row.addProperty("score", i * 0.25);
            row.addProperty("ok", i % 2 == 0);
            rows.add(row);
        }
        data.add("rows", rows);
        data.addProperty("blob", "z".repeat(200_000));
        final var entry = DbEntry.fromJsonObject("db", "coll", data);
        entry.set_id("large");
        return entry;
    }

    @Test
    public void aLargeDocumentEncodesToTheSameBytes() {
        final var entry = largeEntry();

        final var line = entry.encodeLine();

        assertTrue(line.length > 1_000_000, String.valueOf(line.length));
        assertArrayEquals((entry.toFileEntry() + Globals.NEWLINE).getBytes(StandardCharsets.UTF_8), line);
        assertEquals(line.length, entry.byteSize());
    }

    @Test
    public void reEncodingWithAKnownSizeKeepsTheBytes() {
        final var entry = largeEntry();
        final var first = entry.encodeLine();

        final var second = entry.encodeLine();

        assertNotSame(first, second);
        assertArrayEquals(first, second);
        assertEquals(second.length, entry.byteSize());
    }

    @Test
    public void aSmallDocumentStillEncodesExactly() {
        final var data = new JsonObject();
        data.addProperty("v", "é");
        final var entry = DbEntry.fromJsonObject("db", "coll", data);
        entry.set_id("small");

        assertArrayEquals(("{\"v\":\"é\",\"_id\":\"small\"}" + Globals.NEWLINE).getBytes(StandardCharsets.UTF_8),
                entry.encodeLine());
    }
}
