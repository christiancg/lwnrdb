package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestGlobals;

public class PkIndexLoaderTest extends FileSystemTestBase {
    @Test
    public void lf_crlf_and_lone_cr_rows_and_empty_lines_all_load() throws Exception {
        write(row("c", 3) + "\r\n" + row("a", 1) + "\n\n" + row("b", 2) + "\r" + "\r\n" + row("d", 4) + "\n");

        final var read = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(List.of("a", "b", "c", "d"), ids(read));
        assertEquals(List.of(1L, 2L, 3L, 4L), read.stream().map(PkIndexEntry::getVersion).toList());
    }

    @Test
    public void an_intact_file_is_never_rewritten() throws Exception {
        final var content = row("b", 2) + "\n" + row("a", 1) + "\n";
        write(content);

        fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(content, Files.readString(pkIndexFile().toPath(), StandardCharsets.UTF_8),
                "a file with nothing to heal must be left byte for byte");
    }

    @Test
    public void a_duplicated_id_keeps_its_last_row_and_the_log_is_left_as_written() throws Exception {
        final var content = row("b", 1) + "\n" + row("a", 5) + "\n" + row("b", 2) + "\n" + row("b", 3) + "\n";
        write(content);

        final var read = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(List.of("a", "b"), ids(read));
        assertEquals(3L, read.get(1).getVersion(), "the last occurrence in file order must win");
        assertEquals(content, Files.readString(pkIndexFile().toPath(), StandardCharsets.UTF_8),
                "a superseded row is a log entry, not damage, so it is not healed away");
    }

    @Test
    public void a_healed_file_keeps_an_escaped_id_escaped() throws Exception {
        final var escapedId = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "x" + Globals.ID_SEPARATOR + "y", 0L,
                10L, 0L, 7L).toFileEntry();
        write(escapedId + "\nnot a row\n");

        final var read = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(List.of("x" + Globals.ID_SEPARATOR + "y"), ids(read));
        assertEquals(List.of(escapedId), Files.readAllLines(pkIndexFile().toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void a_file_where_nothing_parses_is_refused_and_left_untouched() throws Exception {
        final var content = "nothing\nparses\n";
        write(content);

        assertThrows(java.io.IOException.class, () -> fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(content, Files.readString(pkIndexFile().toPath(), StandardCharsets.UTF_8));
    }

    private static String row(String id, long version) {
        return new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, id, 0L, 10L, 0L, version).toFileEntry();
    }

    private static List<String> ids(List<PkIndexEntry> rows) {
        return rows.stream().map(PkIndexEntry::getValue).toList();
    }

    private static void write(String content) throws Exception {
        Files.writeString(pkIndexFile().toPath(), content, StandardCharsets.UTF_8);
    }

    private static File pkIndexFile() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL
                + File.separator + TestGlobals.COLL + "-pk.idx");
    }
}
