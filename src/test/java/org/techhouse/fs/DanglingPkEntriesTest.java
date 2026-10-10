package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;

public class DanglingPkEntriesTest {
    private static final String DB = "danglingDb";
    private static final String COLL = "danglingColl";

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

    private static String record(String id) {
        return "{\"_id\":\"" + id + "\",\"v\":\"" + id + "\"}\n";
    }

    private File page(long page) {
        return paths.collectionPage(DB, COLL, page);
    }

    private void appendIndexed(long page, String id) throws IOException {
        final var position = page(page).exists() ? page(page).length() : 0L;
        final var text = record(id);
        Files.writeString(page(page).toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        store.indexNewPKValue(DB, COLL, id, position, text.getBytes(StandardCharsets.UTF_8).length, page, 1L);
    }

    private void truncateFirstPage(long length) throws IOException {
        try (var file = new RandomAccessFile(page(0), Globals.RW_PERMISSIONS)) {
            file.setLength(length);
        }
    }

    private Map<Long, Long> pageLengths(long... pages) {
        final var lengths = new HashMap<Long, Long>();
        for (final var page : pages) {
            if (page(page).exists()) {
                lengths.put(page, page(page).length());
            }
        }
        return lengths;
    }

    private List<String> indexedIds() throws IOException {
        return store.readWholePkIndexFile(DB, COLL).stream().map(PkIndexEntry::getValue).sorted().toList();
    }

    private List<String> retiredIds(List<PkIndexEntry> retired) {
        return retired.stream().map(PkIndexEntry::getValue).sorted().toList();
    }

    @Test
    public void test_an_entry_whose_record_is_gone_is_retired_and_its_neighbour_kept() throws IOException {
        appendIndexed(0, "w");
        final var neighbourEnd = page(0).length();
        appendIndexed(0, "x");
        truncateFirstPage(neighbourEnd);
        final var pageBytes = Files.readAllBytes(page(0).toPath());

        final var retired = DanglingPkEntries.retireAll(store, DB, COLL, pageLengths(0));

        assertEquals(List.of("x"), retiredIds(retired));
        assertEquals(List.of("w"), indexedIds());
        assertArrayEquals(pageBytes, Files.readAllBytes(page(0).toPath()));
    }

    @Test
    public void test_a_partly_written_record_is_retired_and_its_tail_then_truncated() throws IOException {
        appendIndexed(0, "w");
        final var neighbourEnd = page(0).length();
        appendIndexed(0, "x");
        truncateFirstPage(neighbourEnd + 5);

        final var retired = DanglingPkEntries.retireAll(store, DB, COLL, pageLengths(0));
        TornPageTail.healAll(paths, DB, COLL, List.of(0L), () -> store.readWholePkIndexFile(DB, COLL));

        assertEquals(List.of("x"), retiredIds(retired));
        assertEquals(neighbourEnd, page(0).length());
    }

    @Test
    public void test_entries_on_a_missing_page_are_retired() throws IOException {
        appendIndexed(0, "w");
        appendIndexed(1, "y");
        Files.delete(page(1).toPath());

        final var retired = DanglingPkEntries.retireAll(store, DB, COLL, pageLengths(0, 1));

        assertEquals(List.of("y"), retiredIds(retired));
        assertEquals(List.of("w"), indexedIds());
    }

    @Test
    public void test_an_index_whose_records_are_all_present_is_left_alone() throws IOException {
        appendIndexed(0, "w");
        appendIndexed(0, "x");
        final var pkBytes = Files.readAllBytes(paths.pkIndexFile(DB, COLL).toPath());

        assertTrue(DanglingPkEntries.retireAll(store, DB, COLL, pageLengths(0)).isEmpty());

        assertArrayEquals(pkBytes, Files.readAllBytes(paths.pkIndexFile(DB, COLL).toPath()));
    }

    @Test
    public void test_an_unrecognised_pk_index_retires_nothing_and_is_left_unchanged() throws IOException {
        appendIndexed(0, "w");
        final var pkFile = paths.pkIndexFile(DB, COLL);
        Files.writeString(pkFile.toPath(), "garbage\nmore garbage\n", StandardCharsets.UTF_8);

        assertTrue(DanglingPkEntries.retireAll(store, DB, COLL, Map.of()).isEmpty());

        assertEquals("garbage\nmore garbage\n", Files.readString(pkFile.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_a_record_that_lost_only_its_line_end_is_restored_not_retired() throws IOException {
        appendIndexed(0, "w");
        appendIndexed(0, "x");
        final var fullLength = page(0).length();
        truncateFirstPage(fullLength - 1);

        TornPageTail.healAll(paths, DB, COLL, List.of(0L), () -> store.readWholePkIndexFile(DB, COLL));
        final var retired = DanglingPkEntries.retireAll(store, DB, COLL, pageLengths(0));

        assertTrue(retired.isEmpty());
        assertEquals(fullLength, page(0).length());
        assertEquals(List.of("w", "x"), indexedIds());
    }
}
