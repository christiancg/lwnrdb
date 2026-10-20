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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;

public class OrphanedPageRecordsTest {
    private static final String DB = "orphanDb";
    private static final String COLL = "orphanColl";

    @TempDir
    File tmp;

    private StorageFixture f;

    @BeforeEach
    public void setUp() throws IOException {
        f = new StorageFixture(tmp, DB, COLL);
    }

    private static String record(String id) {
        return StorageFixture.record(id, id);
    }

    private void appendRaw(long page, String text) throws IOException {
        Files.writeString(f.page(page).toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private List<DbEntry> adoptAll(long... pages) throws IOException {
        final var pageNumbers = new ArrayList<Long>();
        for (final var page : pages) {
            pageNumbers.add(page);
        }
        return OrphanedPageRecords.adoptAll(f.paths, f.store, DB, COLL, pageNumbers);
    }

    private List<String> indexedIds() throws IOException {
        return f.indexedRows().stream().map(PkIndexEntry::getValue).sorted().toList();
    }

    private void truncateFirstPage(long length) throws IOException {
        try (var file = new RandomAccessFile(f.page(0), Globals.RW_PERMISSIONS)) {
            file.setLength(length);
        }
    }

    private Map<Long, Long> pageLengths(long... pages) {
        final var lengths = new HashMap<Long, Long>();
        for (final var page : pages) {
            if (f.page(page).exists()) {
                lengths.put(page, f.page(page).length());
            }
        }
        return lengths;
    }

    private static List<String> retiredIds(List<PkIndexEntry> retired) {
        return retired.stream().map(PkIndexEntry::getValue).sorted().toList();
    }

    @Test
    public void test_scan_reports_an_unterminated_tail_without_refusing_adoption() throws IOException {
        f.seed(0, "a");
        final var indexedEnd = f.page(0).length();
        appendRaw(0, record("b") + "{\"_id\":\"torn\"");

        final var scan = OrphanedPageRecords.scanRecords(f.page(0), DB, COLL, 0, indexedEnd);

        assertTrue(scan.readable());
        assertTrue(scan.unterminatedTail());
        assertEquals(List.of("b"), adoptAll(0).stream().map(DbEntry::get_id).toList());
    }

    @Test
    public void test_a_complete_record_past_the_last_indexed_end_is_adopted_at_its_exact_offset() throws IOException {
        f.seed(0, "a");
        f.seed(0, "b");
        appendRaw(0, record("orphan"));

        final var adopted = adoptAll(0);

        assertEquals(List.of("orphan"), adopted.stream().map(DbEntry::get_id).toList());
        final var entry = f.indexed("orphan");
        assertEquals(record("orphan"), f.readAt(entry));
        assertEquals(0L, entry.getVersion(), "an adopted record has no version of its own to claim");
        assertEquals(0L, adopted.getFirst().getPage());
    }

    @Test
    public void test_every_record_of_an_interrupted_bulk_insert_is_adopted_across_pages() throws IOException {
        f.seed(0, "a");
        f.seed(1, "b");
        appendRaw(0, record("x") + record("y"));
        appendRaw(1, record("z"));

        adoptAll(0, 1);

        assertEquals(List.of("a", "b", "x", "y", "z"), indexedIds());
        assertEquals(record("y"), f.readAt(f.indexed("y")));
        assertEquals(record("z"), f.readAt(f.indexed("z")));
    }

    @Test
    public void test_the_only_copy_left_by_an_interrupted_relocation_is_adopted() throws IOException {
        f.seed(0, "stays");
        appendRaw(1, record("moved"));

        adoptAll(0, 1);

        assertEquals(record("moved"), f.readAt(f.indexed("moved")));
        assertEquals(1L, f.indexed("moved").getPage());
    }

    @Test
    public void test_a_tail_of_ids_the_pk_index_already_holds_is_cut() throws Exception {
        f.seed(0, "a");
        f.seed(1, "moved");
        final var indexedEnd = f.page(0).length();
        appendRaw(0, record("moved"));
        final var pkBefore = Files.readAllBytes(f.paths.pkIndexFile(DB, COLL).toPath());

        try (var _ = RecordingForcer.install(true)) {
            assertTrue(adoptAll(0, 1).isEmpty());
        }

        assertEquals(indexedEnd, f.page(0).length());
        assertArrayEquals(pkBefore, Files.readAllBytes(f.paths.pkIndexFile(DB, COLL).toPath()));
    }

    @Test
    public void test_an_update_copy_whose_intent_a_power_loss_lost_is_cut_and_the_old_value_kept() throws Exception {
        f.seed(0, "a", "b");
        final var indexedEnd = f.page(0).length();
        appendRaw(0, StorageFixture.record("b", "never-acknowledged"));

        try (var _ = RecordingForcer.install(true)) {
            assertTrue(adoptAll(0).isEmpty());
        }

        assertEquals(indexedEnd, f.page(0).length());
        assertEquals(StorageFixture.record("b", "old-b"), f.readAt(f.indexed("b")));
        assertEquals(List.of("a", "b"), indexedIds());
    }

    @Test
    public void test_without_synced_writes_a_tail_of_held_ids_is_left_untouched() throws IOException {
        f.seed(0, "a", "b");
        appendRaw(0, StorageFixture.record("b", "maybe-acknowledged"));
        final var pageBefore = Files.readAllBytes(f.page(0).toPath());

        assertTrue(adoptAll(0).isEmpty());

        assertArrayEquals(pageBefore, Files.readAllBytes(f.page(0).toPath()),
                "unsynced, the pk row of an acknowledged update may be what was lost, so its copy is kept");
    }

    @Test
    public void test_the_cut_alone_trims_held_copies_and_leaves_unindexed_records() throws Exception {
        f.seed(0, "a", "b");
        f.seed(1, "c");
        final var firstEnd = f.page(0).length();
        appendRaw(0, StorageFixture.record("b", "stray"));
        appendRaw(1, record("unindexed"));
        final var secondBefore = Files.readAllBytes(f.page(1).toPath());

        try (var _ = RecordingForcer.install(true)) {
            OrphanedPageRecords.cutUncommittedCopies(f.paths, f.store, DB, COLL, List.of(0L, 1L));
        }

        assertEquals(firstEnd, f.page(0).length());
        assertArrayEquals(secondBefore, Files.readAllBytes(f.page(1).toPath()));
        assertEquals(List.of("a", "b", "c"), indexedIds());
    }

    @Test
    public void test_a_tail_mixing_held_and_unindexed_ids_is_left_untouched() throws IOException {
        f.seed(0, "a", "b");
        appendRaw(0, StorageFixture.record("b", "copy") + record("fresh"));
        final var pageBefore = Files.readAllBytes(f.page(0).toPath());

        assertTrue(adoptAll(0).isEmpty());

        assertArrayEquals(pageBefore, Files.readAllBytes(f.page(0).toPath()));
        assertEquals(List.of("a", "b"), indexedIds());
    }

    @Test
    public void test_a_page_whose_tail_does_not_parse_is_left_untouched() throws IOException {
        f.seed(0, "a");
        appendRaw(0, record("orphan") + "not a record\n");

        assertTrue(adoptAll(0).isEmpty());

        assertEquals(List.of("a"), indexedIds());
    }

    @Test
    public void test_a_tail_record_with_a_non_string_id_is_left_untouched() throws IOException {
        f.seed(0, "a");
        appendRaw(0, "{\"_id\":5}\n");

        assertTrue(adoptAll(0).isEmpty());
    }

    @Test
    public void test_a_tail_record_without_an_id_is_left_untouched() throws IOException {
        f.seed(0, "a");
        appendRaw(0, "{\"v\":1}\n");

        assertTrue(adoptAll(0).isEmpty());
    }

    @Test
    public void test_a_page_whose_tail_repeats_an_id_is_left_untouched() throws IOException {
        f.seed(0, "a");
        appendRaw(0, record("dup") + record("dup"));

        assertTrue(adoptAll(0).isEmpty());
    }

    @Test
    public void test_pages_sharing_an_unindexed_id_are_both_left_untouched() throws IOException {
        appendRaw(0, record("dup"));
        appendRaw(1, record("dup"));
        appendRaw(2, record("alone"));

        adoptAll(0, 1, 2);

        assertEquals(List.of("alone"), indexedIds());
    }

    @Test
    public void test_a_page_holding_only_an_orphan_is_adopted_from_offset_zero() throws IOException {
        appendRaw(0, record("only"));

        adoptAll(0);

        assertEquals(0L, f.indexed("only").getPosition());
    }

    @Test
    public void test_blank_lines_in_the_tail_are_skipped_without_shifting_offsets() throws IOException {
        f.seed(0, "a");
        appendRaw(0, "\n" + record("orphan"));

        adoptAll(0);

        assertEquals(record("orphan"), f.readAt(f.indexed("orphan")));
    }

    @Test
    public void test_a_crlf_terminated_record_is_adopted_with_its_whole_terminator() throws IOException {
        f.seed(0, "a");
        final var crlf = "{\"_id\":\"win\"}\r\n";
        appendRaw(0, crlf);

        adoptAll(0);

        assertEquals(crlf, f.readAt(f.indexed("win")));
    }

    @Test
    public void test_nothing_is_written_when_every_page_ends_at_its_indexed_end() throws IOException {
        f.seed(0, "a");
        final var pkFile = f.paths.pkIndexFile(DB, COLL);
        final var before = Files.readAllBytes(pkFile.toPath());

        assertTrue(adoptAll(0).isEmpty());

        assertArrayEquals(before, Files.readAllBytes(pkFile.toPath()));
    }

    @Test
    public void test_a_missing_page_file_is_skipped() throws IOException {
        f.seed(0, "a");

        assertTrue(adoptAll(0, 7).isEmpty());
    }

    @Test
    public void test_a_collection_whose_pk_index_is_unrecognised_is_skipped() throws IOException {
        appendRaw(0, record("orphan"));
        final var pkFile = f.paths.pkIndexFile(DB, COLL);
        Files.writeString(pkFile.toPath(), "garbage\nmore garbage\n", StandardCharsets.UTF_8);

        assertTrue(adoptAll(0).isEmpty());

        assertEquals("garbage\nmore garbage\n", Files.readString(pkFile.toPath(), StandardCharsets.UTF_8),
                "a PK index nothing could parse must never be extended as if it were empty");
    }

    @Test
    public void test_an_entry_whose_record_is_gone_is_retired_and_its_neighbour_kept() throws IOException {
        f.seed(0, "w");
        final var neighbourEnd = f.page(0).length();
        f.seed(0, "x");
        truncateFirstPage(neighbourEnd);
        final var pageBytes = Files.readAllBytes(f.page(0).toPath());

        final var retired = OrphanedPageRecords.retireDangling(f.store, DB, COLL, pageLengths(0));

        assertEquals(List.of("x"), retiredIds(retired));
        assertEquals(List.of("w"), indexedIds());
        assertArrayEquals(pageBytes, Files.readAllBytes(f.page(0).toPath()));
    }

    @Test
    public void test_a_partly_written_record_is_retired_and_its_tail_then_truncated() throws IOException {
        f.seed(0, "w");
        final var neighbourEnd = f.page(0).length();
        f.seed(0, "x");
        truncateFirstPage(neighbourEnd + 5);

        final var retired = OrphanedPageRecords.retireDangling(f.store, DB, COLL, pageLengths(0));
        TornPageTail.healAll(f.paths, DB, COLL, List.of(0L), f::indexedRows);

        assertEquals(List.of("x"), retiredIds(retired));
        assertEquals(neighbourEnd, f.page(0).length());
    }

    @Test
    public void test_entries_on_a_missing_page_are_retired() throws IOException {
        f.seed(0, "w");
        f.seed(1, "y");
        Files.delete(f.page(1).toPath());

        final var retired = OrphanedPageRecords.retireDangling(f.store, DB, COLL, pageLengths(0, 1));

        assertEquals(List.of("y"), retiredIds(retired));
        assertEquals(List.of("w"), indexedIds());
    }

    @Test
    public void test_an_index_whose_records_are_all_present_is_left_alone() throws IOException {
        f.seed(0, "w");
        f.seed(0, "x");
        final var pkBytes = Files.readAllBytes(f.paths.pkIndexFile(DB, COLL).toPath());

        assertTrue(OrphanedPageRecords.retireDangling(f.store, DB, COLL, pageLengths(0)).isEmpty());

        assertArrayEquals(pkBytes, Files.readAllBytes(f.paths.pkIndexFile(DB, COLL).toPath()));
    }

    @Test
    public void test_an_unrecognised_pk_index_retires_nothing_and_is_left_unchanged() throws IOException {
        f.seed(0, "w");
        final var pkFile = f.paths.pkIndexFile(DB, COLL);
        Files.writeString(pkFile.toPath(), "garbage\nmore garbage\n", StandardCharsets.UTF_8);

        assertTrue(OrphanedPageRecords.retireDangling(f.store, DB, COLL, Map.of()).isEmpty());

        assertEquals("garbage\nmore garbage\n", Files.readString(pkFile.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_a_record_that_lost_only_its_line_end_is_restored_not_retired() throws IOException {
        f.seed(0, "w");
        f.seed(0, "x");
        final var fullLength = f.page(0).length();
        truncateFirstPage(fullLength - 1);

        TornPageTail.healAll(f.paths, DB, COLL, List.of(0L), f::indexedRows);
        final var retired = OrphanedPageRecords.retireDangling(f.store, DB, COLL, pageLengths(0));

        assertTrue(retired.isEmpty());
        assertEquals(fullLength, f.page(0).length());
        assertEquals(List.of("w", "x"), indexedIds());
    }
}
