package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;

public class PkIndexEntryTest {
    @Test
    public void test_to_file_entry_conversion() {
        PkIndexEntry entry = new PkIndexEntry("testDB", "testCollection", "testValue", 123L, 456L, 0);
        String expected = line("testValue", "123", "456", "0", "0");
        assertEquals(expected, entry.toFileEntry());
    }

    @Test
    public void test_to_file_entry_includes_version() {
        PkIndexEntry entry = new PkIndexEntry("testDB", "testCollection", "testValue", 123L, 456L, 0L, 1720000000000L);
        assertEquals(line("testValue", "123", "456", "0", "1720000000000"), entry.toFileEntry());
    }

    @Test
    public void test_version_round_trip() {
        PkIndexEntry original = new PkIndexEntry("db", "coll", "id-1", 10L, 20L, 3L, 1720000000123L);
        PkIndexEntry parsed = PkIndexEntry.fromIndexFileEntry("db", "coll", original.toFileEntry());
        assertEquals("id-1", parsed.getValue());
        assertEquals(10L, parsed.getPosition());
        assertEquals(20L, parsed.getLength());
        assertEquals(3L, parsed.getPage());
        assertEquals(1720000000123L, parsed.getVersion());
    }

    @Test
    public void test_from_index_file_entry_with_empty_string() {
        String line = "";
        assertThrows(Exception.class, () -> PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", line));
    }

    @Test
    public void test_to_file_entry_delimits_with_the_unit_separator() {
        PkIndexEntry entry = new PkIndexEntry("testDB", "testCollection", "testValue", 123L, 456L, 0);
        assertEquals(5, entry.toFileEntry().split(Globals.ID_SEPARATOR, -1).length);
    }

    @Test
    public void test_a_pipe_in_the_value_needs_no_escaping() {
        PkIndexEntry entry = new PkIndexEntry("testDB", "testCollection", "my|custom|id", 100L, 50L, 2L);
        assertEquals(line("my|custom|id", "100", "50", "2", "0"), entry.toFileEntry());
        PkIndexEntry parsed = PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", entry.toFileEntry());
        assertEquals("my|custom|id", parsed.getValue());
        assertEquals(100L, parsed.getPosition());
        assertEquals(50L, parsed.getLength());
        assertEquals(2L, parsed.getPage());
    }

    @Test
    public void test_an_admin_page_row_id_round_trips() {
        PkIndexEntry original = new PkIndexEntry("admin", "pages", "mydb|mycoll|3", 200L, 75L, 1L);
        PkIndexEntry parsed = PkIndexEntry.fromIndexFileEntry("admin", "pages", original.toFileEntry());
        assertEquals("mydb|mycoll|3", parsed.getValue());
        assertEquals(original.getPosition(), parsed.getPosition());
        assertEquals(original.getLength(), parsed.getLength());
        assertEquals(original.getPage(), parsed.getPage());
    }

    @Test
    public void test_a_unit_separator_in_the_value_round_trips() {
        PkIndexEntry original = new PkIndexEntry("testDB", "testCollection", "a" + Globals.ID_SEPARATOR + "b", 200L,
                75L, 1L, 9L);
        String fileEntry = original.toFileEntry();
        assertEquals(5, fileEntry.split(Globals.ID_SEPARATOR, -1).length);
        PkIndexEntry parsed = PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", fileEntry);
        assertEquals("a" + Globals.ID_SEPARATOR + "b", parsed.getValue());
        assertEquals(200L, parsed.getPosition());
        assertEquals(75L, parsed.getLength());
        assertEquals(1L, parsed.getPage());
        assertEquals(9L, parsed.getVersion());
    }

    @Test
    public void test_a_backslash_and_newline_in_the_value_round_trip() {
        PkIndexEntry original = new PkIndexEntry("testDB", "testCollection", "a\\b\nc\rd", 1L, 2L, 3L, 4L);
        PkIndexEntry parsed = PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", original.toFileEntry());
        assertEquals("a\\b\nc\rd", parsed.getValue());
    }

    @Test
    public void test_an_empty_value_round_trips() {
        PkIndexEntry original = new PkIndexEntry("testDB", "testCollection", "", 1L, 2L, 3L, 4L);
        PkIndexEntry parsed = PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", original.toFileEntry());
        assertEquals("", parsed.getValue());
    }

    @Test
    public void test_a_line_in_the_old_pipe_grammar_is_rejected() {
        assertThrows(Exception.class, () -> PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", "a|0|20|0|0"));
    }

    @Test
    public void test_a_line_missing_a_field_is_rejected() {
        String line = line("a", "0", "20", "0");
        assertThrows(Exception.class, () -> PkIndexEntry.fromIndexFileEntry("testDB", "testCollection", line));
    }

    private static String line(String... fields) {
        return String.join(Globals.ID_SEPARATOR, fields);
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
