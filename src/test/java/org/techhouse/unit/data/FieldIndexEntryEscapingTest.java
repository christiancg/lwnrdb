package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;

public class FieldIndexEntryEscapingTest {
    private static final String SEPARATOR = Globals.ID_SEPARATOR;

    private static FieldIndexEntry<String> roundTrip(String value, Set<String> ids) {
        final var original = new FieldIndexEntry<>("db", "coll", value, ids);
        final var line = original.toFileEntry();
        assertFalse(line.contains("\n"), "index line must not contain a line feed");
        assertFalse(line.contains("\r"), "index line must not contain a carriage return");
        return FieldIndexEntry.fromIndexFileEntry("db", "coll", line, String.class);
    }

    @Test
    public void test_index_key_with_newline_round_trips() {
        final var parsed = roundTrip("x\ny", Set.of("id1"));
        assertEquals("x\ny", parsed.getValue());
        assertEquals(Set.of("id1"), parsed.getIds());
    }

    @Test
    public void test_index_key_with_carriage_return_round_trips() {
        final var parsed = roundTrip("x\ry", Set.of("id1"));
        assertEquals("x\ry", parsed.getValue());
    }

    @Test
    public void test_index_key_with_id_separator_round_trips() {
        final var parsed = roundTrip("a" + SEPARATOR + "b", Set.of("id1"));
        assertEquals("a" + SEPARATOR + "b", parsed.getValue());
        assertEquals(Set.of("id1"), parsed.getIds());
    }

    @Test
    public void test_index_key_with_backslash_round_trips() {
        final var parsed = roundTrip("C:\\path", Set.of("id1"));
        assertEquals("C:\\path", parsed.getValue());
    }

    @Test
    public void test_id_with_newline_round_trips() {
        final var parsed = roundTrip("value", Set.of("we\nird"));
        assertEquals(Set.of("we\nird"), parsed.getIds());
    }

    @Test
    public void test_id_with_separator_does_not_split_into_two_ids() {
        final var parsed = roundTrip("value", Set.of("a" + SEPARATOR + "b"));
        assertEquals(Set.of("a" + SEPARATOR + "b"), parsed.getIds());
    }

    @Test
    public void test_escaping_is_injective_for_adjacent_values() {
        assertNotEquals(FieldIndexEntry.escapeIndexToken("a\\nb"), FieldIndexEntry.escapeIndexToken("a\nb"));
        assertEquals("a\\nb", FieldIndexEntry.unescapeIndexToken(FieldIndexEntry.escapeIndexToken("a\\nb")));
        assertEquals("a\nb", FieldIndexEntry.unescapeIndexToken(FieldIndexEntry.escapeIndexToken("a\nb")));
    }

    @Test
    public void test_plain_value_is_written_unescaped() {
        final var entry = new FieldIndexEntry<>("db", "coll", "plain", Set.of("id1"));
        assertEquals("plain" + SEPARATOR + "id1", entry.toFileEntry());
    }

    @Test
    public void test_legacy_unescaped_backslash_is_preserved_on_read() {
        assertEquals("C:\\path", FieldIndexEntry.unescapeIndexToken("C:\\path"));
    }

    @Test
    public void test_multiple_ids_round_trip() {
        final var parsed = roundTrip("value", Set.of("id1", "id2", "id3"));
        assertEquals(Set.of("id1", "id2", "id3"), parsed.getIds());
    }

    @Test
    public void test_pk_index_id_with_newline_round_trips() {
        final var original = new PkIndexEntry("db", "coll", "we\nird", 10L, 20L, 0L, 5L);
        final var line = original.toFileEntry();
        assertFalse(line.contains("\n"), "pk index line must not contain a line feed");
        final var parsed = PkIndexEntry.fromIndexFileEntry("db", "coll", line);
        assertEquals("we\nird", parsed.getValue());
        assertEquals(10L, parsed.getPosition());
        assertEquals(20L, parsed.getLength());
        assertEquals(5L, parsed.getVersion());
    }
}
