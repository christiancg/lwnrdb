package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;

public class OrphanedPageRecordsTest {
    private static final String DB = "orphanDb";
    private static final String COLL = "orphanColl";
    private static final long INDEXED_VERSION = 1_700_000_000_000L;

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

    private void appendRaw(long page, String text) throws IOException {
        Files.writeString(page(page).toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private void appendIndexed(long page, String id) throws IOException {
        final var position = page(page).exists() ? page(page).length() : 0L;
        final var text = record(id);
        appendRaw(page, text);
        store.indexNewPKValue(DB, COLL, id, position, text.getBytes(StandardCharsets.UTF_8).length, page,
                INDEXED_VERSION);
    }

    private List<DbEntry> adoptAll(long... pages) throws IOException {
        final var pageNumbers = new ArrayList<Long>();
        for (final var page : pages) {
            pageNumbers.add(page);
        }
        return OrphanedPageRecords.adoptAll(paths, store, DB, COLL, pageNumbers);
    }

    private PkIndexEntry indexed(String id) throws IOException {
        return store.readWholePkIndexFile(DB, COLL).stream().filter(entry -> entry.getValue().equals(id)).findFirst()
                .orElse(null);
    }

    private List<String> indexedIds() throws IOException {
        return store.readWholePkIndexFile(DB, COLL).stream().map(PkIndexEntry::getValue).sorted().toList();
    }

    private String readAt(PkIndexEntry entry) throws IOException {
        final var bytes = Files.readAllBytes(page(entry.getPage()).toPath());
        return new String(bytes, (int) entry.getPosition(), (int) entry.getLength(), StandardCharsets.UTF_8);
    }

    @Test
    public void test_a_complete_record_past_the_last_indexed_end_is_adopted_at_its_exact_offset() throws IOException {
        appendIndexed(0, "a");
        appendIndexed(0, "b");
        appendRaw(0, record("orphan"));

        final var adopted = adoptAll(0);

        assertEquals(List.of("orphan"), adopted.stream().map(DbEntry::get_id).toList());
        final var entry = indexed("orphan");
        assertEquals(record("orphan"), readAt(entry));
        assertEquals(0L, entry.getVersion(), "an adopted record has no version of its own to claim");
        assertEquals(0L, adopted.getFirst().getPage());
    }

    @Test
    public void test_every_record_of_an_interrupted_bulk_insert_is_adopted_across_pages() throws IOException {
        appendIndexed(0, "a");
        appendIndexed(1, "b");
        appendRaw(0, record("x") + record("y"));
        appendRaw(1, record("z"));

        adoptAll(0, 1);

        assertEquals(List.of("a", "b", "x", "y", "z"), indexedIds());
        assertEquals(record("y"), readAt(indexed("y")));
        assertEquals(record("z"), readAt(indexed("z")));
    }

    @Test
    public void test_the_only_copy_left_by_an_interrupted_relocation_is_adopted() throws IOException {
        appendIndexed(0, "stays");
        appendRaw(1, record("moved"));

        adoptAll(0, 1);

        assertEquals(record("moved"), readAt(indexed("moved")));
        assertEquals(1L, indexed("moved").getPage());
    }

    @Test
    public void test_a_page_whose_tail_names_an_indexed_id_is_left_untouched() throws IOException {
        appendIndexed(0, "a");
        appendIndexed(1, "moved");
        appendRaw(0, record("moved"));
        final var pkBefore = Files.readAllBytes(paths.pkIndexFile(DB, COLL).toPath());

        assertTrue(adoptAll(0, 1).isEmpty());

        assertArrayEquals(pkBefore, Files.readAllBytes(paths.pkIndexFile(DB, COLL).toPath()));
    }

    @Test
    public void test_a_page_whose_tail_does_not_parse_is_left_untouched() throws IOException {
        appendIndexed(0, "a");
        appendRaw(0, record("orphan") + "not a record\n");

        assertTrue(adoptAll(0).isEmpty());

        assertEquals(List.of("a"), indexedIds());
    }

    @Test
    public void test_a_tail_record_with_a_non_string_id_is_left_untouched() throws IOException {
        appendIndexed(0, "a");
        appendRaw(0, "{\"_id\":5}\n");

        assertTrue(adoptAll(0).isEmpty());
    }

    @Test
    public void test_a_tail_record_without_an_id_is_left_untouched() throws IOException {
        appendIndexed(0, "a");
        appendRaw(0, "{\"v\":1}\n");

        assertTrue(adoptAll(0).isEmpty());
    }

    @Test
    public void test_a_page_whose_tail_repeats_an_id_is_left_untouched() throws IOException {
        appendIndexed(0, "a");
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

        assertEquals(0L, indexed("only").getPosition());
    }

    @Test
    public void test_blank_lines_in_the_tail_are_skipped_without_shifting_offsets() throws IOException {
        appendIndexed(0, "a");
        appendRaw(0, "\n" + record("orphan"));

        adoptAll(0);

        assertEquals(record("orphan"), readAt(indexed("orphan")));
    }

    @Test
    public void test_a_crlf_terminated_record_is_adopted_with_its_whole_terminator() throws IOException {
        appendIndexed(0, "a");
        final var crlf = "{\"_id\":\"win\"}\r\n";
        appendRaw(0, crlf);

        adoptAll(0);

        assertEquals(crlf, readAt(indexed("win")));
    }

    @Test
    public void test_nothing_is_written_when_every_page_ends_at_its_indexed_end() throws IOException {
        appendIndexed(0, "a");
        final var pkFile = paths.pkIndexFile(DB, COLL);
        final var before = Files.readAllBytes(pkFile.toPath());

        assertTrue(adoptAll(0).isEmpty());

        assertArrayEquals(before, Files.readAllBytes(pkFile.toPath()));
    }

    @Test
    public void test_a_missing_page_file_is_skipped() throws IOException {
        appendIndexed(0, "a");

        assertTrue(adoptAll(0, 7).isEmpty());
    }

    @Test
    public void test_a_collection_whose_pk_index_is_unrecognised_is_skipped() throws IOException {
        appendRaw(0, record("orphan"));
        final var pkFile = paths.pkIndexFile(DB, COLL);
        Files.writeString(pkFile.toPath(), "garbage\nmore garbage\n", StandardCharsets.UTF_8);

        assertTrue(adoptAll(0).isEmpty());

        assertEquals("garbage\nmore garbage\n", Files.readString(pkFile.toPath(), StandardCharsets.UTF_8),
                "a PK index nothing could parse must never be extended as if it were empty");
    }
}
