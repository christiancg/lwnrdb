package org.techhouse.unit.data;

import org.junit.jupiter.api.Test;
import org.techhouse.data.PkIndexEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PkIndexEntryTest {
    // Convert PkIndexEntry to a file entry string using toFileEntry
    @Test
    public void test_to_file_entry_conversion() {
        PkIndexEntry entry = new PkIndexEntry("testDB", "testCollection", "testValue", 123L, 456L);
        String expected = "testValue<|>123<|>456";
        assertEquals(expected, entry.toFileEntry());
    }

    // Handle empty or null strings in fromIndexFileEntry
    @Test
    public void test_from_index_file_entry_with_empty_string() {
        String line = "";
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", line));
    }

    @Test
    public void test_compareTo_equal_values() {
        PkIndexEntry entry = new PkIndexEntry("db", "collection", "value", 1L, 1L);
        int result = entry.compareTo("value");
        assertEquals(0, result);
    }

    @Test
    public void test_compareTo_null_value() {
        PkIndexEntry entry = new PkIndexEntry("db", "collection", "value", 1L, 1L);
        assertThrows(NullPointerException.class, () -> entry.compareTo(null));
    }
}