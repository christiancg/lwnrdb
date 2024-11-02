package org.techhouse.unit.data;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class FieldIndexEntryTest {
    // Correctly serializes FieldIndexEntry to file entry format
    @Test
    public void test_to_file_entry_serialization() {
        Set<String> ids = new HashSet<>(Arrays.asList("id1", "id2"));
        FieldIndexEntry<String> entry = new FieldIndexEntry<>("testDB", "testCollection", "testValue", ids);
        String expected = "testValue" + Globals.INDEX_ENTRY_SEPARATOR + "id2;id1";
        assertEquals(expected, entry.toFileEntry());
    }

    // Handles null values gracefully in compareTo method
    @Test
    public void test_compare_to_with_null_value() {
        FieldIndexEntry<String> entry = new FieldIndexEntry<>("testDB", "testCollection", null, new HashSet<>());
        assertEquals(0, entry.compareTo(null));
    }

    // Correctly parses a line with Double value and returns FieldIndexEntry
    @Test
    public void test_parse_double_value() {
        String databaseName = "testDB";
        String collectionName = "testCollection";
        String line = "123.45<|>id1;id2";
        FieldIndexEntry<Double> entry = FieldIndexEntry.fromIndexFileEntry(databaseName, collectionName, line, Double.class);
        assertEquals("testDB", entry.getDatabaseName());
        assertEquals("testCollection", entry.getCollectionName());
        assertEquals(123.45, entry.getValue());
        assertTrue(entry.getIds().contains("id1"));
        assertTrue(entry.getIds().contains("id2"));
    }

    // Handles empty line input gracefully
    @Test
    @Disabled("We should probably check this to avoid issues while creating FileIndexEntries")
    public void test_empty_line_input() {
        String databaseName = "testDB";
        String collectionName = "testCollection";
        String line = "";
        FieldIndexEntry<String> entry = FieldIndexEntry.fromIndexFileEntry(databaseName, collectionName, line, String.class);
        assertEquals("testDB", entry.getDatabaseName());
        assertEquals("testCollection", entry.getCollectionName());
        assertNull(entry.getValue());
        assertTrue(entry.getIds().isEmpty());
    }

    // Compares two Double values correctly
    @Test
    public void test_compare_double_values() {
        FieldIndexEntry<Double> entry1 = new FieldIndexEntry<>("db", "collection", 5.5, Set.of("id1"));
        FieldIndexEntry<Double> entry2 = new FieldIndexEntry<>("db", "collection", 7.5, Set.of("id2"));
        assertTrue(entry1.compareTo(entry2.getValue()) < 0);
        assertTrue(entry2.compareTo(entry1.getValue()) > 0);
        assertEquals(0, entry1.compareTo(entry1.getValue()));
    }

    // Throws IllegalStateException for non-JsonCustom objects in default case
    @Test
    public void test_illegal_state_exception_for_non_jsoncustom() {
        FieldIndexEntry<Object> entry = new FieldIndexEntry<>("db", "collection", new Object(), Set.of("id1"));
        assertThrows(IllegalStateException.class, () -> entry.compareTo(new Object()));
    }

    // Test getters and setters
    @Test
    public void test_getters_and_setters() {
        final var fieldIndexEntry = new FieldIndexEntry<>("db", "collection", 5.5, Set.of("id1"));
        assertEquals("db", fieldIndexEntry.getDatabaseName());
        assertEquals("collection", fieldIndexEntry.getCollectionName());
        assertEquals(5.5, fieldIndexEntry.getValue());
        assertTrue(fieldIndexEntry.getIds().contains("id1"));
        fieldIndexEntry.setCollectionName("collection2");
        fieldIndexEntry.setDatabaseName("db2");
        fieldIndexEntry.setValue(6.0);
        fieldIndexEntry.setIds(Set.of("id2"));
        assertEquals("collection2", fieldIndexEntry.getCollectionName());
        assertEquals(6.0, fieldIndexEntry.getValue());
        assertEquals("db2", fieldIndexEntry.getDatabaseName());
        assertEquals(Set.of("id2"), fieldIndexEntry.getIds());
    }
}