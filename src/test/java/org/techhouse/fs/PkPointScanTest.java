package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;

public class PkPointScanTest {
    private static final String DB = "pointDb";
    private static final String COLL = "pointColl";

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

    private static String row(String id, long position, long version) {
        return new PkIndexEntry(DB, COLL, id, position, 10L, 0L, version).toFileEntry();
    }

    private void write(String text) throws IOException {
        Files.writeString(paths.pkIndexFile(DB, COLL).toPath(), text, StandardCharsets.UTF_8);
    }

    private void append(String text) throws IOException {
        Files.writeString(paths.pkIndexFile(DB, COLL).toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private Map<String, PkIndexEntry> scan(String... ids) throws IOException {
        return store.rowsFor(DB, COLL, List.of(ids));
    }

    @Test
    public void test_the_last_row_of_an_id_wins() throws IOException {
        write(row("a", 0, 1) + "\n" + row("b", 10, 2) + "\n" + row("a", 20, 3) + "\n");

        final var found = scan("a");

        assertEquals(20L, found.get("a").getPosition());
        assertEquals(3L, found.get("a").getVersion());
        assertEquals(1, found.size());
    }

    @Test
    public void test_a_delete_row_removes_and_a_later_row_restores() throws IOException {
        write(row("a", 0, 1) + "\n" + PkIndexEntry.toDeleteRow("a") + "\n" + row("b", 10, 2) + "\n"
                + PkIndexEntry.toDeleteRow("b") + "\n" + row("b", 30, 4) + "\n");

        final var found = scan("a", "b");

        assertNull(found.get("a"));
        assertEquals(30L, found.get("b").getPosition());
    }

    @Test
    public void test_a_malformed_match_keeps_the_earlier_row() throws IOException {
        write(row("a", 0, 1) + "\n" + "a\u001fnot-a-number\u001f1\u001f0\u001f2\n" + row("b", 10, 1) + "\n");

        assertEquals(0L, scan("a").get("a").getPosition());
    }

    @Test
    public void test_an_unterminated_last_line_is_ignored() throws IOException {
        write(row("a", 0, 1) + "\r\n" + row("a", 50, 2));

        assertEquals(0L, scan("a").get("a").getPosition());
    }

    @Test
    public void test_a_file_no_line_of_which_is_recognised_throws() throws IOException {
        write("garbage\nmore garbage\n");

        assertThrows(IOException.class, () -> scan("a"));
    }

    @Test
    public void test_a_recognised_line_anywhere_makes_the_file_readable() throws IOException {
        write("garbage\n" + row("b", 10, 1) + "\n");

        assertTrue(scan("a").isEmpty());
    }

    @Test
    public void test_a_missing_file_answers_nothing() throws IOException {
        assertTrue(scan("a").isEmpty());
        assertTrue(store.rowsFor(DB, COLL, List.of()).isEmpty());
    }

    @Test
    public void test_escaped_ids_match_exactly() throws IOException {
        final var separated = "x\u001fy";
        final var slashed = "x\\y";
        final var broken = "x\ny";
        write(row(separated, 0, 1) + "\n" + row(slashed, 10, 1) + "\n" + row(broken, 20, 1) + "\n" + row("x", 30, 1)
                + "\n");

        final var found = scan(separated, slashed, broken, "y");

        assertEquals(0L, found.get(separated).getPosition());
        assertEquals(10L, found.get(slashed).getPosition());
        assertEquals(20L, found.get(broken).getPosition());
        assertNull(found.get("y"));
        assertEquals(3, found.size());
    }

    @Test
    public void test_many_ids_are_found_in_one_pass_across_buffer_refills() throws IOException {
        final var text = new StringBuilder();
        final var wanted = new ArrayList<String>();
        for (var i = 0; i < 40_000; i++) {
            final var id = "document-identifier-" + i;
            text.append(row(id, i * 100L, i)).append('\n');
            if (i % 997 == 0) {
                wanted.add(id);
            }
        }
        write(text.toString());
        assertTrue(paths.pkIndexFile(DB, COLL).length() > PkPointScan.MAX_CHUNK);

        final var found = store.rowsFor(DB, COLL, wanted);

        assertEquals(wanted.size(), found.size());
        for (final var id : wanted) {
            assertEquals(Long.parseLong(id.substring(id.lastIndexOf('-') + 1)) * 100L, found.get(id).getPosition());
        }
    }

    @Test
    public void test_random_logs_answer_like_the_whole_fold() throws IOException {
        final var random = new Random(17);
        for (var round = 0; round < 200; round++) {
            write(row("seed", 0, 1) + "\n");
            final var pool = new ArrayList<String>();
            for (var i = 0; i < 12; i++) {
                pool.add("id" + i);
            }
            for (var op = 0; op < 1 + random.nextInt(30); op++) {
                final var id = pool.get(random.nextInt(pool.size()));
                switch (random.nextInt(5)) {
                    case 0 -> append(PkIndexEntry.toDeleteRow(id) + "\n");
                    case 1 -> append(id + "\u001fbroken\n");
                    default -> append(row(id, random.nextInt(1000), random.nextInt(50)) + "\n");
                }
            }
            if (random.nextBoolean()) {
                append(row(pool.getFirst(), 999, 99));
            }
            final var scanned = store.rowsFor(DB, COLL, pool);
            final var folded = new HashMap<String, String>();
            for (final var live : store.readWholePkIndexFile(DB, COLL)) {
                if (pool.contains(live.getValue())) {
                    folded.put(live.getValue(), live.toFileEntry());
                }
            }
            final var answered = new HashMap<String, String>();
            scanned.forEach((id, found) -> answered.put(id, found.toFileEntry()));
            assertEquals(folded, answered, "round " + round);
        }
    }
}
