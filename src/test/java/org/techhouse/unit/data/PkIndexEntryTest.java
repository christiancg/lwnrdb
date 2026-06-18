package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.data.PkIndexEntry;

public class PkIndexEntryTest {
    // Convert PkIndexEntry to a file entry string using toFileEntry
    @Test
    public void test_to_file_entry_conversion() {
        PkIndexEntry entry = new PkIndexEntry("testDB", "testCollection", "testValue", 123L, 456L, 0);
        String expected = "testValue|123|456|0";
        assertEquals(expected, entry.toFileEntry());
    }

    // Handle empty or null strings in fromIndexFileEntry
    @Test
    public void test_from_index_file_entry_with_empty_string() {
        String line = "";
        assertThrows(Exception.class, () -> PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", line));
    }

    @Test
    public void test_to_file_entry_with_pipe_in_value() {
        PkIndexEntry entry = new PkIndexEntry("testDB", "testCollection", "my|custom|id", 100L, 50L, 2L);
        assertEquals("my|custom|id|100|50|2", entry.toFileEntry());
    }

    @Test
    public void test_from_index_file_entry_with_pipe_in_value() {
        PkIndexEntry entry = PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", "my|custom|id|100|50|2");
        assertEquals("my|custom|id", entry.getValue());
        assertEquals(100L, entry.getPosition());
        assertEquals(50L, entry.getLength());
        assertEquals(2L, entry.getPage());
    }

    @Test
    public void test_round_trip_with_pipe_in_value() {
        PkIndexEntry original = new PkIndexEntry("testDB", "testCollection", "foo|bar|baz", 200L, 75L, 1L);
        PkIndexEntry parsed = PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", original.toFileEntry());
        assertEquals(original.getValue(), parsed.getValue());
        assertEquals(original.getPosition(), parsed.getPosition());
        assertEquals(original.getLength(), parsed.getLength());
        assertEquals(original.getPage(), parsed.getPage());
    }

    @Test
    public void test_compareTo_equal_values() {
        PkIndexEntry entry = new PkIndexEntry("db", "collection", "value", 1L, 1L, 0);
        int result = entry.compareTo("value");
        assertEquals(0, result);
    }

    @Test
    public void test_compareTo_null_value() {
        PkIndexEntry entry = new PkIndexEntry("db", "collection", "value", 1L, 1L, 0);
        assertThrows(NullPointerException.class, () -> entry.compareTo(null));
    }

    @Test
    public void test_getters_and_setters() {
        PkIndexEntry entry = new PkIndexEntry("db", "coll", "val", 10L, 20L, 1L);
        assertEquals("db", entry.getDatabaseName());
        assertEquals("coll", entry.getCollectionName());
        assertEquals("val", entry.getValue());
        assertEquals(10L, entry.getPosition());
        assertEquals(20L, entry.getLength());
        assertEquals(1L, entry.getPage());

        entry.setDatabaseName("db2");
        entry.setCollectionName("coll2");
        entry.setValue("val2");
        entry.setPosition(11L);
        entry.setLength(21L);
        entry.setPage(2L);

        assertEquals("db2", entry.getDatabaseName());
        assertEquals("coll2", entry.getCollectionName());
        assertEquals("val2", entry.getValue());
        assertEquals(11L, entry.getPosition());
        assertEquals(21L, entry.getLength());
        assertEquals(2L, entry.getPage());
    }

    @Test
    public void test_equals_same_instance() {
        PkIndexEntry entry = new PkIndexEntry("db", "coll", "val", 1L, 1L, 0L);
        assertEquals(entry, entry);
    }

    @Test
    public void test_equals_symmetric() {
        PkIndexEntry entry1 = new PkIndexEntry("db", "coll", "val", 1L, 2L, 0L);
        PkIndexEntry entry2 = new PkIndexEntry("db", "coll", "val", 1L, 2L, 0L);
        assertEquals(entry1, entry2);
        assertEquals(entry2, entry1);
    }

    @Test
    public void test_equals_null_returns_false() {
        PkIndexEntry entry = new PkIndexEntry("db", "coll", "val", 1L, 1L, 0L);
        assertNotEquals(null, entry);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        PkIndexEntry entry = new PkIndexEntry("db", "coll", "val", 1L, 1L, 0L);
        assertNotEquals("notAnEntry", entry);
    }

    @Test
    public void test_equals_different_value_returns_false() {
        PkIndexEntry entry1 = new PkIndexEntry("db", "coll", "val1", 1L, 1L, 0L);
        PkIndexEntry entry2 = new PkIndexEntry("db", "coll", "val2", 1L, 1L, 0L);
        assertNotEquals(entry1, entry2);
    }

    @Test
    public void test_equals_different_position_returns_false() {
        PkIndexEntry entry1 = new PkIndexEntry("db", "coll", "val", 1L, 1L, 0L);
        PkIndexEntry entry2 = new PkIndexEntry("db", "coll", "val", 2L, 1L, 0L);
        assertNotEquals(entry1, entry2);
    }

    @Test
    public void test_hashCode_same_values_equal() {
        PkIndexEntry entry1 = new PkIndexEntry("db", "coll", "val", 1L, 2L, 0L);
        PkIndexEntry entry2 = new PkIndexEntry("db", "coll", "val", 1L, 2L, 0L);
        assertEquals(entry1.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_hashCode_different_value_differs() {
        PkIndexEntry entry1 = new PkIndexEntry("db", "coll", "val1", 1L, 2L, 0L);
        PkIndexEntry entry2 = new PkIndexEntry("db", "coll", "val2", 1L, 2L, 0L);
        assertNotEquals(entry1.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        PkIndexEntry entry = new PkIndexEntry("db", "coll", "val", 1L, 2L, 0L);
        assertNotNull(entry.toString());
    }
}
