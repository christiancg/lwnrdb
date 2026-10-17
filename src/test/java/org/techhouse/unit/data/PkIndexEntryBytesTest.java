package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;

public class PkIndexEntryBytesTest {
    @Test
    public void a_row_is_parsed_in_place_inside_a_larger_buffer() {
        final var row = line("inner", "10", "20", "3", "40");
        final var buffer = ("garbage\n" + row + "\nmore").getBytes(StandardCharsets.UTF_8);
        final var start = "garbage\n".length();

        final var parsed = PkIndexEntry.fromIndexFileBytes("db", "coll", buffer, start, start + row.length());

        assertEquals("inner", parsed.getValue());
        assertEquals(10L, parsed.getPosition());
        assertEquals(20L, parsed.getLength());
        assertEquals(3L, parsed.getPage());
        assertEquals(40L, parsed.getVersion());
        assertEquals("db", parsed.getDatabaseName());
        assertEquals("coll", parsed.getCollectionName());
    }

    @Test
    public void a_multibyte_id_is_decoded() {
        final var parsed = parse(line("ключ-日本-😀", "1", "2", "3", "4"));

        assertEquals("ключ-日本-😀", parsed.getValue());
        assertEquals(4L, parsed.getVersion());
    }

    @Test
    public void numbers_outside_the_plain_digit_path_parse_as_long_parse_long_does() {
        final var parsed = parse(line("id", "+5", "-7", String.valueOf(Long.MAX_VALUE), "٣"));

        assertEquals(5L, parsed.getPosition());
        assertEquals(-7L, parsed.getLength());
        assertEquals(Long.MAX_VALUE, parsed.getPage());
        assertEquals(3L, parsed.getVersion(), "Long.parseLong accepts any Unicode digit, and so must the loader");
    }

    @Test
    public void the_largest_plain_digit_run_parses_exactly() {
        final var parsed = parse(line("id", "999999999999999999", "0", "0", "117000000000000000"));

        assertEquals(999_999_999_999_999_999L, parsed.getPosition());
        assertEquals(117_000_000_000_000_000L, parsed.getVersion());
    }

    @Test
    public void a_malformed_number_is_refused_with_the_long_parse_long_message() {
        final var thrown = assertThrows(NumberFormatException.class, () -> parse(line("id", "1x", "2", "3", "4")));

        assertEquals(assertThrows(NumberFormatException.class, () -> Long.parseLong("1x")).getMessage(),
                thrown.getMessage());
    }

    @Test
    public void an_overflowing_number_is_refused() {
        assertThrows(NumberFormatException.class, () -> parse(line("id", "99999999999999999999", "2", "3", "4")));
    }

    @Test
    public void an_empty_number_is_refused() {
        assertThrows(NumberFormatException.class, () -> parse(line("id", "", "2", "3", "4")));
    }

    @Test
    public void a_row_with_too_many_fields_is_refused() {
        final var thrown = assertThrows(IllegalArgumentException.class,
                () -> parse(line("id", "1", "2", "3", "4", "5")));

        assertTrue(thrown.getMessage().contains("5 separated fields"), thrown.getMessage());
    }

    @Test
    public void a_row_with_too_few_fields_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> parse(line("id", "1", "2", "3")));
        assertThrows(IllegalArgumentException.class, () -> parse("id"));
    }

    @Test
    public void a_row_ending_in_a_separator_is_refused() {
        assertThrows(NumberFormatException.class, () -> parse(line("id", "1", "2", "3", "")));
    }

    private static PkIndexEntry parse(String row) {
        final var bytes = row.getBytes(StandardCharsets.UTF_8);
        return PkIndexEntry.fromIndexFileBytes("db", "coll", bytes, 0, bytes.length);
    }

    private static String line(String... fields) {
        return String.join(Globals.ID_SEPARATOR, fields);
    }
}
