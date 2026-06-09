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
        final var result = entry.toFileEntry();
        assertTrue(result.equals("testValue" + Globals.ID_SEPARATOR + "id1" + Globals.ID_SEPARATOR + "id2") ||
                   result.equals("testValue" + Globals.ID_SEPARATOR + "id2" + Globals.ID_SEPARATOR + "id1"));
    }

    // @NonNull on the parameter means passing null throws NullPointerException
    @Test
    public void test_compare_to_with_null_value() {
        FieldIndexEntry<String> entry = new FieldIndexEntry<>("testDB", "testCollection", null, new HashSet<>());
        assertThrows(NullPointerException.class, () -> entry.compareTo(null));
    }

    // Correctly parses a line with Double value and returns FieldIndexEntry
    @Test
    public void test_parse_double_value() {
        String databaseName = "testDB";
        String collectionName = "testCollection";
        String line = "123.45" + Globals.ID_SEPARATOR + "id1" + Globals.ID_SEPARATOR + "id2";
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

    @Test
    public void test_to_file_entry_with_pipe_in_value() {
        FieldIndexEntry<String> entry = new FieldIndexEntry<>("testDB", "testCollection", "val|ue", Set.of("id1"));
        assertEquals("val|ue" + Globals.ID_SEPARATOR + "id1", entry.toFileEntry());
    }

    @Test
    public void test_parse_string_value_with_pipe() {
        String line = "val|ue" + Globals.ID_SEPARATOR + "id1" + Globals.ID_SEPARATOR + "id2";
        FieldIndexEntry<String> entry = FieldIndexEntry.fromIndexFileEntry("testDB", "testCollection", line, String.class);
        assertEquals("val|ue", entry.getValue());
        assertTrue(entry.getIds().contains("id1"));
        assertTrue(entry.getIds().contains("id2"));
    }

    @Test
    public void test_parse_string_value_with_multiple_pipes() {
        String line = "a|b|c" + Globals.ID_SEPARATOR + "id1";
        FieldIndexEntry<String> entry = FieldIndexEntry.fromIndexFileEntry("testDB", "testCollection", line, String.class);
        assertEquals("a|b|c", entry.getValue());
        assertTrue(entry.getIds().contains("id1"));
    }

    @Test
    public void test_roundtrip_with_pipe_in_value() {
        FieldIndexEntry<String> original = new FieldIndexEntry<>("testDB", "testCollection", "foo|bar", Set.of("id1", "id2"));
        String line = original.toFileEntry();
        FieldIndexEntry<String> parsed = FieldIndexEntry.fromIndexFileEntry("testDB", "testCollection", line, String.class);
        assertEquals("foo|bar", parsed.getValue());
        assertEquals(original.getIds(), parsed.getIds());
    }

    @Test
    public void test_id_with_pipe_is_stored_and_parsed_correctly() {
        FieldIndexEntry<String> original = new FieldIndexEntry<>("db", "coll", "someValue", Set.of("id|with|pipes"));
        FieldIndexEntry<String> parsed = FieldIndexEntry.fromIndexFileEntry("db", "coll", original.toFileEntry(), String.class);
        assertEquals("someValue", parsed.getValue());
        assertEquals(1, parsed.getIds().size());
        assertTrue(parsed.getIds().contains("id|with|pipes"));
    }

    @Test
    public void test_id_with_semicolon_is_stored_and_parsed_correctly() {
        FieldIndexEntry<String> original = new FieldIndexEntry<>("db", "coll", "someValue", Set.of("id;with;semicolons"));
        FieldIndexEntry<String> parsed = FieldIndexEntry.fromIndexFileEntry("db", "coll", original.toFileEntry(), String.class);
        assertEquals("someValue", parsed.getValue());
        assertEquals(1, parsed.getIds().size());
        assertTrue(parsed.getIds().contains("id;with;semicolons"));
    }

    @Test
    public void test_multiple_ids_with_special_chars_are_stored_and_parsed_correctly() {
        Set<String> ids = Set.of("id|pipe", "id;semi", "id|and;both");
        FieldIndexEntry<String> original = new FieldIndexEntry<>("db", "coll", "someValue", ids);
        FieldIndexEntry<String> parsed = FieldIndexEntry.fromIndexFileEntry("db", "coll", original.toFileEntry(), String.class);
        assertEquals("someValue", parsed.getValue());
        assertEquals(ids, parsed.getIds());
    }

    @Test
    public void test_value_and_id_both_contain_pipe() {
        FieldIndexEntry<String> original = new FieldIndexEntry<>("db", "coll", "val|ue", Set.of("id|one", "id|two"));
        FieldIndexEntry<String> parsed = FieldIndexEntry.fromIndexFileEntry("db", "coll", original.toFileEntry(), String.class);
        assertEquals("val|ue", parsed.getValue());
        assertEquals(Set.of("id|one", "id|two"), parsed.getIds());
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

    // compareTo with Boolean value (L53)
    @Test
    public void test_compare_to_boolean_values() {
        FieldIndexEntry<Boolean> entry = new FieldIndexEntry<>("db", "col", false, Set.of("id1"));
        assertTrue(entry.compareTo(true) < 0);
        assertEquals(0, entry.compareTo(false));
    }

    // compareTo with null value returns 0 (L55)
    @Test
    @SuppressWarnings("unchecked")
    public void test_compare_to_null_value_returns_zero() {
        FieldIndexEntry entry = new FieldIndexEntry<>("db", "col", null, Set.of("id1"));
        assertEquals(0, entry.compareTo("anything"));
    }
}