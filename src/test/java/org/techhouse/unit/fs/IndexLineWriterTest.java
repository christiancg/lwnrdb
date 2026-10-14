package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.test.TestGlobals;

public class IndexLineWriterTest extends FileSystemTestBase {
    private static final String FIELD = "tag";

    private static FieldIndexEntry<Object> entry(Object value, String... ids) {
        return new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, value, new LinkedHashSet<>(List.of(ids)));
    }

    private static byte[] expectedBytes(List<FieldIndexEntry<Object>> entries) throws Exception {
        final var out = new ByteArrayOutputStream();
        for (final var entry : entries) {
            out.write((entry.toFileEntry() + Globals.NEWLINE).getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private byte[] written(String typeLabel, List<FieldIndexEntry<Object>> entries) throws Exception {
        fs.rewriteIndexType(TestGlobals.DB, TestGlobals.COLL, FIELD, typeLabel, entries);
        return Files.readAllBytes(new File(
                TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL + File.separator
                        + TestGlobals.COLL + "-" + FIELD + "-" + typeLabel + Globals.INDEX_FILE_EXTENSION)
                .toPath());
    }

    @Test
    public void streamedStringLinesMatchTheirFileEntries() throws Exception {
        final var entries = List.of(entry("plain", "a", "b"), entry("x" + Globals.ID_SEPARATOR + "y", "c"),
                entry("back\\slash", "d"), entry("line\nfeed", "e"), entry("cr\rhere", "f"), entry("\ud800", "g"),
                entry("é😀", "h"), entry("ids", "id" + Globals.ID_SEPARATOR + "sep", "ü"), entry("", "i"));

        assertArrayEquals(expectedBytes(entries), written("String", entries));
    }

    @Test
    public void streamedNumberLinesMatchTheirFileEntries() throws Exception {
        final var entries = List.of(entry(-3.0, "a"), entry(0.0, "b"), entry(2.5, "c"), entry(1e20, "d"),
                entry(123456789012.0, "e"));

        assertArrayEquals(expectedBytes(entries), written("Number", entries));
    }

    @Test
    public void streamedCustomLinesMatchTheirFileEntries() throws Exception {
        final var entries = List.of(entry(CustomTypeFactory.getCustomTypeInstance("#geo(1.5,-2.25)"), "a", "b"));

        assertArrayEquals(expectedBytes(entries), written("JsonGeo", entries));
    }
}
