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
}
