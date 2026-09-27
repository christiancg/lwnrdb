package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.exceptions.NonFiniteNumberException;

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
    public void test_unpaired_high_surrogate_round_trips() {
        final var value = "a\ud800b";
        assertEquals(value, FieldIndexEntry.unescapeIndexToken(FieldIndexEntry.escapeIndexToken(value)));
        assertEquals(value, roundTrip(value, Set.of("id1")).getValue());
    }

    @Test
    public void test_unpaired_low_surrogate_round_trips() {
        final var value = "ab\udc00";
        assertEquals(value, FieldIndexEntry.unescapeIndexToken(FieldIndexEntry.escapeIndexToken(value)));
        assertEquals(value, roundTrip(value, Set.of("id1")).getValue());
    }

    @Test
    public void test_lone_surrogate_only_value_round_trips() {
        assertEquals("\ud800", FieldIndexEntry.unescapeIndexToken(FieldIndexEntry.escapeIndexToken("\ud800")));
        assertEquals("\udc00", FieldIndexEntry.unescapeIndexToken(FieldIndexEntry.escapeIndexToken("\udc00")));
    }

    @Test
    public void test_paired_surrogate_is_written_unescaped() {
        final var emoji = "a😀b";
        assertEquals(emoji, FieldIndexEntry.escapeIndexToken(emoji));
        assertEquals(emoji, roundTrip(emoji, Set.of("id1")).getValue());
    }

    @Test
    public void test_escaped_surrogate_survives_utf8_encoding() {
        final var escaped = FieldIndexEntry.escapeIndexToken("a\ud800b");
        final var encoded = new String(escaped.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertEquals(escaped, encoded);
    }

    @Test
    public void test_two_distinct_lone_surrogates_produce_distinct_file_keys() {
        assertNotEquals(FieldIndexEntry.fileKeyOf("a\ud800b"), FieldIndexEntry.fileKeyOf("a\udc00b"));
        assertNotEquals(FieldIndexEntry.fileKeyOf("a\ud800b"), FieldIndexEntry.fileKeyOf("a?b"));
    }

    @Test
    public void test_literal_backslash_u_is_unambiguous() {
        final var value = "a\\u0041b";
        assertEquals(value, FieldIndexEntry.unescapeIndexToken(FieldIndexEntry.escapeIndexToken(value)));
        assertNotEquals(FieldIndexEntry.escapeIndexToken(value), FieldIndexEntry.escapeIndexToken("a\ud800b"));
    }

    @Test
    public void test_truncated_unicode_escape_is_kept_verbatim() {
        assertEquals("a\\u00", FieldIndexEntry.unescapeIndexToken("a\\u00"));
        assertEquals("a\\uZZZZb", FieldIndexEntry.unescapeIndexToken("a\\uZZZZb"));
    }

    @Test
    public void test_legacy_question_mark_key_still_parses() {
        final var parsed = FieldIndexEntry.fromIndexFileEntry("db", "coll", "a?b" + SEPARATOR + "id1", String.class);
        assertEquals("a?b", parsed.getValue());
        assertEquals(Set.of("id1"), parsed.getIds());
    }

    @Test
    public void test_non_finite_numeric_key_is_refused() {
        assertThrows(NonFiniteNumberException.class,
                () -> FieldIndexEntry.fromIndexFileEntry("db", "coll", "Infinity" + SEPARATOR + "id1", Number.class));
        assertThrows(NonFiniteNumberException.class,
                () -> FieldIndexEntry.fromIndexFileEntry("db", "coll", "-Infinity" + SEPARATOR + "id1", Number.class));
    }

    @Test
    public void test_finite_numeric_key_still_parses() {
        final var parsed = FieldIndexEntry.fromIndexFileEntry("db", "coll", "5" + SEPARATOR + "id1", Number.class);
        assertEquals(5d, parsed.getValue());
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
