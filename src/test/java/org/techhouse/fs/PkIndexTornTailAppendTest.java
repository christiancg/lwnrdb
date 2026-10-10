package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;

public class PkIndexTornTailAppendTest {
    private static final String DB = "tornTailDb";
    private static final String COLL = "tornTailColl";

    @TempDir
    File tmp;

    private FilePaths paths;
    private PkIndexStore store;

    @BeforeEach
    public void setUp() {
        paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        assertTrue(paths.collectionFolder(DB, COLL).mkdirs());
        store = new PkIndexStore(paths);
    }

    private static PkIndexEntry entry(String id, long position) {
        return new PkIndexEntry(DB, COLL, id, position, 10L, 0L, 1_700_000_000_000L);
    }

    private File pkIndexFile() {
        return paths.pkIndexFile(DB, COLL);
    }

    private void writeUnterminatedButParseableLine() throws IOException {
        final var torn = entry("torn", 0L).toFileEntry();
        Files.writeString(pkIndexFile().toPath(), torn.substring(0, torn.length() - 4), StandardCharsets.UTF_8);
    }

    private List<String> ids() throws IOException {
        return store.readWholePkIndexFile(DB, COLL).stream().map(PkIndexEntry::getValue).sorted().toList();
    }

    @Test
    public void test_an_append_after_an_unterminated_parseable_line_starts_a_new_line() throws IOException {
        writeUnterminatedButParseableLine();
        assertEquals(List.of("torn"), ids(), "the torn line must still parse, or this test covers nothing");

        store.indexNewPKValue(DB, COLL, "acked", 10L, 10, 0L, 2L);

        assertEquals(List.of("acked", "torn"), ids(),
                "an acknowledged entry must not be glued onto a torn line the next heal would drop");
    }

    @Test
    public void test_a_bulk_append_after_an_unterminated_line_keeps_every_entry() throws IOException {
        writeUnterminatedButParseableLine();

        store.bulkIndexNewPKValues(DB, COLL, List.of(entry("a", 10L), entry("b", 20L)));

        assertEquals(List.of("a", "b", "torn"), ids());
    }

    @Test
    public void test_an_append_to_an_empty_file_adds_no_leading_blank_line() throws IOException {
        Files.writeString(pkIndexFile().toPath(), "", StandardCharsets.UTF_8);

        store.indexNewPKValue(DB, COLL, "first", 0L, 10, 0L, 1L);

        final var content = Files.readString(pkIndexFile().toPath(), StandardCharsets.UTF_8);
        assertFalse(content.startsWith("\n") || content.startsWith("\r"), "an empty file needs no separator");
    }

    @Test
    public void test_an_append_after_a_terminated_line_adds_no_blank_line() throws IOException {
        store.indexNewPKValue(DB, COLL, "first", 0L, 10, 0L, 1L);
        store.indexNewPKValue(DB, COLL, "second", 10L, 10, 0L, 2L);

        final var lines = Files.readAllLines(pkIndexFile().toPath(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "a properly terminated file must not gain an empty line");
    }

    @Test
    public void test_a_failed_append_also_truncates_the_separator_it_wrote() throws IOException {
        writeUnterminatedButParseableLine();
        final var before = Files.readAllBytes(pkIndexFile().toPath());

        assertThrows(IllegalStateException.class,
                () -> store.bulkIndexNewPKValues(DB, COLL, List.of(entry("ok", 10L), new UnserializableEntry())));

        assertEquals(before.length, pkIndexFile().length(),
                "the rollback must remove the separator too, or a failed append changes the file");
    }

    private static final class UnserializableEntry extends PkIndexEntry {
        private UnserializableEntry() {
            super(DB, COLL, "unserializable", 0L, 0L, 0L, 0L);
        }

        @Override
        public String toFileEntry() {
            throw new IllegalStateException("this row cannot be serialized");
        }
    }
}
