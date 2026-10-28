package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

public class PkIndexHighestVersionTest {
    private static final String DB = "versionDb";
    private static final String COLL = "versionColl";

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

    private static String line(String id, long version) {
        return new PkIndexEntry(DB, COLL, id, 0L, 10L, 0L, version).toFileEntry();
    }

    private void write(String text) throws IOException {
        Files.writeString(paths.pkIndexFile(DB, COLL).toPath(), text, StandardCharsets.UTF_8);
    }

    @Test
    public void test_a_missing_file_has_no_version() throws IOException {
        assertEquals(0L, store.highestVersion(DB, COLL));
    }

    @Test
    public void test_the_highest_row_version_wins_whatever_its_place() throws IOException {
        write(line("a", 5) + "\n" + line("b", 90) + "\r\n" + line("c", 7) + "\n");

        assertEquals(90L, store.highestVersion(DB, COLL));
    }

    @Test
    public void test_a_torn_last_line_is_not_read() throws IOException {
        write(line("a", 5) + "\n" + line("b", 900));

        assertEquals(5L, store.highestVersion(DB, COLL));
    }

    @Test
    public void test_malformed_lines_and_delete_rows_are_skipped() throws IOException {
        write(line("a", 5) + "\nnot a row\n" + PkIndexEntry.toDeleteRow("b") + "\n");

        assertEquals(5L, store.highestVersion(DB, COLL));
    }

    @Test
    public void test_a_file_of_delete_rows_is_recognised_and_has_no_version() throws IOException {
        write(PkIndexEntry.toDeleteRow("a") + "\n" + PkIndexEntry.toDeleteRow("b") + "\n");

        assertEquals(0L, store.highestVersion(DB, COLL));
    }

    @Test
    public void test_a_file_with_no_recognised_line_throws() throws IOException {
        write("garbage\nmore garbage\n");

        assertThrows(IOException.class, () -> store.highestVersion(DB, COLL));
    }

    @Test
    public void test_lines_straddling_the_read_chunk_and_a_line_longer_than_it_are_read() throws IOException {
        final var text = new StringBuilder();
        var version = 0L;
        while (text.length() < 3 * (1 << 20)) {
            text.append(line("id" + version, ++version)).append('\n');
        }
        text.repeat('x', 3 << 20).append('\n').append(line("last", version + 1)).append('\n');
        write(text.toString());

        assertEquals(version + 1, store.highestVersion(DB, COLL));
    }

    @Test
    public void test_a_session_reads_the_rows_once_until_invalidated() throws IOException {
        write(line("a", 5) + "\n");
        final var session = new PkRowsSession(store, DB, COLL);

        final var first = session.rows();
        assertSame(first, session.rows());
        write(line("a", 5) + "\n" + line("b", 6) + "\n");
        assertSame(first, session.rows());
        session.invalidate();

        assertEquals(List.of("a", "b"), session.rows().stream().map(PkIndexEntry::getValue).toList());
    }

    @Test
    public void test_a_session_over_an_unrecognised_file_answers_empty_and_its_rows_throw() throws IOException {
        write("garbage\n");
        final var session = new PkRowsSession(store, DB, COLL);

        assertTrue(session.recognised().isEmpty());
        assertThrows(IOException.class, session::rows);
    }
}
