package org.techhouse.unit.data;

import org.junit.jupiter.api.Test;
import org.techhouse.data.PkIndexEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PkIndexEntryTest {
    // Convert PkIndexEntry to a file entry string using toFileEntry
    @Test
    public void test_to_file_entry_conversion() {
        PkIndexEntry entry = new PkIndexEntry("testDB", "testCollection", "testValue", 123L, 456L,0);
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
    public void test_roundtrip_with_pipe_in_value() {
        PkIndexEntry original = new PkIndexEntry("testDB", "testCollection", "foo|bar|baz", 200L, 75L, 1L);
        PkIndexEntry parsed = PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", original.toFileEntry());
        assertEquals(original.getValue(), parsed.getValue());
        assertEquals(original.getPosition(), parsed.getPosition());
        assertEquals(original.getLength(), parsed.getLength());
        assertEquals(original.getPage(), parsed.getPage());
    }

    @Test
    public void test_compareTo_equal_values() {
        PkIndexEntry entry = new PkIndexEntry("db", "collection", "value", 1L, 1L,0);
        int result = entry.compareTo("value");
        assertEquals(0, result);
    }

    @Test
    public void test_compareTo_null_value() {
        PkIndexEntry entry = new PkIndexEntry("db", "collection", "value", 1L, 1L,0);
        assertThrows(NullPointerException.class, () -> entry.compareTo(null));
    }
}