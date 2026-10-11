package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.exceptions.MalformedJsonException;

public class FieldIndexEntryEscapingTest {
    private static final String SEPARATOR = Globals.ID_SEPARATOR;

    private static FieldIndexEntry<String> roundTrip(String value, Set<String> ids) {
        final var original = new FieldIndexEntry<>("db", "coll", value, ids);
        final var line = original.toFileEntry();
        assertFalse(line.contains("\n"), "index line must not contain a line feed");
        assertFalse(line.contains("\r"), "index line must not contain a carriage return");
        return FieldIndexEntry.fromIndexFileEntry("db", "coll", line, String.class);
    }

    static Stream<Arguments> roundTripCases() {
        return Stream.of(Arguments.of("x\ny", Set.of("id1")), Arguments.of("x\ry", Set.of("id1")),
                Arguments.of("a" + SEPARATOR + "b", Set.of("id1")), Arguments.of("C:\\path", Set.of("id1")),
                Arguments.of("value", Set.of("we\nird")), Arguments.of("value", Set.of("a" + SEPARATOR + "b")),
                Arguments.of("value", Set.of("id1", "id2", "id3")), Arguments.of("a\ud800b", Set.of("id1")),
                Arguments.of("ab\udc00", Set.of("id1")), Arguments.of("a😀b", Set.of("id1")));
    }

    @ParameterizedTest
    @MethodSource("roundTripCases")
    public void test_index_entry_round_trips(String value, Set<String> ids) {
        final var parsed = roundTrip(value, ids);
        assertEquals(value, parsed.getValue());
        assertEquals(ids, parsed.getIds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\ud800b", "ab\udc00", "\ud800", "\udc00"})
    public void test_lone_surrogate_token_round_trips(String value) {
        assertEquals(value, FieldIndexEntry.unescapeIndexToken(FieldIndexEntry.escapeIndexToken(value)));
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
    public void test_paired_surrogate_is_written_unescaped() {
        final var emoji = "a😀b";
        assertEquals(emoji, FieldIndexEntry.escapeIndexToken(emoji));
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
        assertThrows(MalformedJsonException.class,
                () -> FieldIndexEntry.fromIndexFileEntry("db", "coll", "Infinity" + SEPARATOR + "id1", Number.class));
        assertThrows(MalformedJsonException.class,
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
