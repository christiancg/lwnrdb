package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.CustomTypeFactory;

public class IndexLineWriterTest {
    private static final String DB = "writerDb";
    private static final String COLL = "writerColl";

    @BeforeEach
    public void registerTheExtendedTypes() {
        new EJson();
    }

    private static FieldIndexEntry<Object> entry(Object value, String... ids) {
        return new FieldIndexEntry<>(DB, COLL, value, new LinkedHashSet<>(List.of(ids)));
    }

    private static byte[] expectedBytes(List<FieldIndexEntry<Object>> entries) throws IOException {
        final var out = new ByteArrayOutputStream();
        for (final var entry : entries) {
            out.write((entry.toFileEntry() + Globals.NEWLINE).getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static byte[] written(List<FieldIndexEntry<Object>> entries) throws IOException {
        final var out = new ByteArrayOutputStream();
        final var lines = new IndexLineWriter(out);
        for (final var entry : entries) {
            lines.write(entry);
        }
        return out.toByteArray();
    }

    @Test
    public void streamedStringLinesMatchTheirFileEntries() throws IOException {
        final var entries = List.of(entry("plain", "a", "b"), entry("x" + Globals.ID_SEPARATOR + "y", "c"),
                entry("back\\slash", "d"), entry("line\nfeed", "e"), entry("cr\rhere", "f"), entry("\ud800", "g"),
                entry("é😀", "h"), entry("ids", "id" + Globals.ID_SEPARATOR + "sep", "ü"), entry("", "i"));

        assertArrayEquals(expectedBytes(entries), written(entries));
    }

    @Test
    public void streamedNumberLinesMatchTheirFileEntries() throws IOException {
        final var entries = List.of(entry(-3.0, "a"), entry(0.0, "b"), entry(2.5, "c"), entry(1e20, "d"),
                entry(123456789012.0, "e"));

        assertArrayEquals(expectedBytes(entries), written(entries));
    }

    @Test
    public void streamedCustomLinesMatchTheirFileEntries() throws IOException {
        final var entries = List.of(entry(CustomTypeFactory.getCustomTypeInstance("#geo(1.5,-2.25)"), "a", "b"));

        assertArrayEquals(expectedBytes(entries), written(entries));
    }

    @Test
    public void test_plain_ids_and_keys_take_the_fast_path_and_match_their_file_entries() throws IOException {
        final var entries = List.of(entry("cat-00012", "id-1", "id_2", "ID3"), entry("Mixed Case ~!@#$%^&*()", "x"),
                entry(42.0, "n-1", "n-2"));

        assertArrayEquals(expectedBytes(entries), written(entries));
    }

    @Test
    public void test_an_escapable_character_after_a_plain_prefix_rewinds_and_matches() throws IOException {
        final var tokens = List.of("abc\\d", "abc\nd", "abc\rd", "abc" + Globals.ID_SEPARATOR + "d", "abc\\", "\n",
                "\r", Globals.ID_SEPARATOR, "\\");
        for (final var token : tokens) {
            final var entries = List.of(entry(token, "plain"), entry("plain", "id-0", token, "id-2"));

            assertArrayEquals(expectedBytes(entries), written(entries), token);
        }
    }

    @Test
    public void test_a_non_ascii_or_lone_surrogate_after_a_plain_prefix_matches() throws IOException {
        final var tokens = List.of("abcé", "abc\ud800", "abc\udc00x", "abc😀", "abc\u007f", "abc\u0080", "abcK");
        for (final var token : tokens) {
            final var entries = List.of(entry(token, "plain"), entry("plain", "id-0", token, "id-2"));

            assertArrayEquals(expectedBytes(entries), written(entries), token);
        }
    }

    @Test
    public void test_a_token_longer_than_the_initial_line_buffer_matches() throws IOException {
        final var ids = new String[1000];
        for (var i = 0; i < ids.length; i++) {
            ids[i] = "document-" + i;
        }
        final var entries = List.of(entry("k".repeat(300), ids), entry("k".repeat(299) + "\n", "a"),
                entry("plain", "i".repeat(400) + Globals.ID_SEPARATOR));

        assertArrayEquals(expectedBytes(entries), written(entries));
    }

    @Test
    public void test_the_line_buffer_is_reused_across_entries_without_leaking_a_rewound_token() throws IOException {
        final var entries = List.of(entry("long-plain-prefix\\", "first-id-with-a-long-name\n"), entry("a", "b"),
                entry("long-plain-prefix", "z".repeat(300)), entry("c", "d\r"), entry("e", "f"));

        assertArrayEquals(expectedBytes(entries), written(entries));
    }
}
