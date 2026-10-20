package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.techhouse.ex.PkRebuildRefusedException;

public class PkIndexRebuildTest {
    private static final String DB = "rebuildDb";
    private static final String COLL = "rebuildColl";
    private static final String GARBAGE = "garbage\nmore garbage\n";

    @TempDir
    File tmp;

    private StorageFixture f;

    @BeforeEach
    public void setUp() throws IOException {
        f = new StorageFixture(tmp, DB, COLL);
    }

    private PkRebuild rebuild() throws IOException {
        final var pages = new ArrayList<Long>();
        for (var page = 0L; f.page(page).exists(); page++) {
            pages.add(page);
        }
        final var rebuild = PkIndexRebuild.plan(f.paths, f.store, DB, COLL, pages);
        PkIndexRebuild.apply(f.store, DB, COLL, rebuild);
        return rebuild;
    }

    private void appendRaw(long page, String text) throws IOException {
        Files.writeString(f.page(page).toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private byte[] pkBytes() throws IOException {
        return Files.readAllBytes(f.paths.pkIndexFile(DB, COLL).toPath());
    }

    private void writeGarbagePk() throws IOException {
        Files.writeString(f.paths.pkIndexFile(DB, COLL).toPath(), GARBAGE, StandardCharsets.UTF_8);
    }

    private List<String> rows() throws IOException {
        return f.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList();
    }

    private void assertRefusedAndUntouched(String expectedReason) throws IOException {
        final var before = pkBytes();
        final var refused = assertThrows(PkRebuildRefusedException.class, this::rebuild);
        assertTrue(refused.getMessage().contains(expectedReason), refused.getMessage());
        assertArrayEquals(before, pkBytes(), "a refused rebuild must leave pk.idx byte-identical");
    }

    @Test
    public void test_rebuild_matches_every_record_position_length_and_page() throws IOException {
        f.seed(0, "a", "b", "c");
        f.seed(1, "d", "e", "f");
        final var written = rows();

        final var rebuild = rebuild();

        assertEquals(written, rows());
        assertEquals(6, rebuild.entries());
        assertEquals(0, rebuild.unversioned());
        assertTrue(rebuild.previousReadable());
        assertTrue(rebuild.appeared().isEmpty());
        assertTrue(rebuild.vanished().isEmpty());
    }

    @Test
    public void test_versions_come_from_a_readable_pk_index() throws IOException {
        f.seed(0, "a", "b", "c");
        f.store.rewriteRows(DB, COLL, current -> {
            current.forEach(row -> row.setPosition(row.getPosition() + 1));
            return current;
        });

        rebuild();

        assertTrue(f.everyRowReadsItsOwnRecord(), "the stale offsets were corrected");
        assertTrue(f.indexedRows().stream().allMatch(row -> row.getVersion() == StorageFixture.OLD_VERSION));
    }

    @Test
    public void test_an_id_missing_from_the_pk_index_is_adopted_at_version_0() throws IOException {
        f.seed(0, "a", "b", "c");
        f.store.rewriteRows(DB, COLL,
                current -> new ArrayList<>(current.stream().filter(row -> !row.getValue().equals("b")).toList()));

        final var rebuild = rebuild();

        assertEquals(List.of("b"), rebuild.appeared().stream().map(DbEntry::get_id).toList());
        assertEquals(1, rebuild.unversioned());
        assertEquals(0L, f.indexed("b").getVersion());
        assertTrue(f.everyRowReadsItsOwnRecord());
    }

    @Test
    public void test_an_entry_with_no_record_is_dropped() throws IOException {
        f.seed(0, "a", "b");
        f.store.indexNewPKValue(DB, COLL, "ghost", 0, 5, 0, StorageFixture.OLD_VERSION);

        final var rebuild = rebuild();

        assertEquals(List.of("ghost"), rebuild.vanished().stream().map(PkIndexEntry::getValue).toList());
        assertEquals(List.of("a", "b"), f.indexedRows().stream().map(PkIndexEntry::getValue).toList());
    }

    @Test
    public void test_an_unrecognised_pk_index_is_replaced_with_every_row_at_version_0() throws IOException {
        f.seed(0, "a", "b", "c");
        writeGarbagePk();

        final var rebuild = rebuild();

        assertFalse(rebuild.previousReadable());
        assertEquals(3, rebuild.entries());
        assertEquals(3, rebuild.unversioned());
        assertTrue(rebuild.appeared().isEmpty(), "with nothing to compare against there is no delta");
        assertTrue(f.everyRowReadsItsOwnRecord());
        assertTrue(f.indexedRows().stream().allMatch(row -> row.getVersion() == 0L));
    }

    @Test
    public void test_refuses_an_unparseable_line_and_leaves_pk_idx_byte_identical() throws IOException {
        f.seed(0, "a");
        appendRaw(0, "not a record\n");
        assertRefusedAndUntouched("has no readable _id");
    }

    @Test
    public void test_refuses_a_record_without_a_string_id() throws IOException {
        f.seed(0, "a");
        appendRaw(0, "{\"_id\":7,\"v\":\"x\"}\n");
        assertRefusedAndUntouched("has no readable _id");
    }

    @Test
    public void test_refuses_an_id_repeated_within_a_page() throws IOException {
        f.seed(0, "a", "b");
        appendRaw(0, StorageFixture.record("a", "copy"));
        assertRefusedAndUntouched("_id 'a' appears twice");
    }

    @Test
    public void test_refuses_an_id_repeated_across_pages() throws IOException {
        f.seed(0, "a");
        f.seed(1, "b");
        appendRaw(1, StorageFixture.record("a", "copy"));
        assertRefusedAndUntouched("_id 'a' is on pages 0 and 1");
    }

    @Test
    public void test_refuses_an_unterminated_tail() throws IOException {
        f.seed(0, "a");
        appendRaw(0, "{\"_id\":\"torn\"");
        assertRefusedAndUntouched("does not end on a whole record");
    }

    @Test
    public void test_a_collection_with_no_pages_rebuilds_an_empty_pk_index() throws IOException {
        final var rebuild = rebuild();

        assertEquals(0, rebuild.entries());
        assertTrue(f.paths.pkIndexFile(DB, COLL).exists());
        assertTrue(f.indexedRows().isEmpty());
    }

    @Test
    public void test_replace_all_writes_over_a_file_nothing_parsed_in() throws IOException {
        writeGarbagePk();
        final var entry = new PkIndexEntry(DB, COLL, "only", 0, 10, 0, StorageFixture.OLD_VERSION);

        f.store.replaceAll(DB, COLL, List.of(entry));

        assertEquals(List.of(entry.toFileEntry()), rows());
    }

    private WriteIntentJournal.Intent intentFor(WriteIntent item) throws IOException {
        return f.journal.begin(DB, COLL, List.of(item));
    }

    private void blank(PkIndexEntry row) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(f.page(row.getPage()).toPath(),
                StandardOpenOption.WRITE)) {
            PageHoles.blank(channel, row.getPosition(), row.getLength());
        }
    }

    @Test
    public void test_a_leftover_delete_intent_is_completed_before_the_rebuild() throws IOException {
        f.seed(0, "a", "b", "c");
        intentFor(WriteIntent.Delete.of(f.indexed("b")));
        writeGarbagePk();

        assertEquals(1, f.recovery.recoverPagesOf(DB, COLL));
        rebuild();

        assertEquals(0, f.openIntents());
        assertEquals(List.of("a", "c"), f.indexedRows().stream().map(PkIndexEntry::getValue).toList());
        assertTrue(f.everyRowReadsItsOwnRecord());
    }

    @Test
    public void test_a_leftover_replace_intent_with_its_old_copy_intact_is_undone() throws IOException {
        f.seed(0, "a", "b");
        f.seed(1, "x");
        final var b = f.indexed("b");
        intentFor(WriteIntent.Replace.of(b, b.getPosition(), 1, f.page(1).length()));
        f.appendUpdatedCopy("b", 1);
        writeGarbagePk();

        f.recovery.recoverPagesOf(DB, COLL);
        rebuild();

        assertEquals(0, f.openIntents());
        assertEquals(0L, f.indexed("b").getPage(), "the uncommitted copy on page 1 was blanked");
        assertEquals(StorageFixture.record("b", "old-b"), f.readAt(f.indexed("b")));
    }

    @Test
    public void test_a_leftover_replace_intent_whose_old_copy_is_blank_keeps_the_new_copy() throws IOException {
        f.seed(0, "a", "b");
        f.seed(1, "x");
        final var b = f.indexed("b");
        intentFor(WriteIntent.Replace.of(b, b.getPosition(), 1, f.page(1).length()));
        f.appendUpdatedCopy("b", 1);
        blank(b);
        writeGarbagePk();

        f.recovery.recoverPagesOf(DB, COLL);
        rebuild();

        assertEquals(1L, f.indexed("b").getPage());
        assertEquals(StorageFixture.record("b", "new-b"), f.readAt(f.indexed("b")));
    }

    @Test
    public void test_only_an_intent_that_would_replay_is_reported_as_replayable() throws IOException {
        f.seed(0, "a");
        final var kept = intentFor(new WriteIntent.Vacuum(0));
        final var emptied = intentFor(new WriteIntent.Vacuum(0));
        f.journal.end(emptied);

        assertEquals(List.of(kept.label()), f.recovery.replayableIntentsOf(DB, COLL));
    }

    @Test
    public void test_a_plan_writes_nothing_until_it_is_applied() throws IOException {
        f.seed(0, "a", "b");
        f.store.rewriteRows(DB, COLL,
                current -> new ArrayList<>(current.stream().filter(row -> !row.getValue().equals("b")).toList()));
        final var before = pkBytes();

        final var plan = PkIndexRebuild.plan(f.paths, f.store, DB, COLL, List.of(0L));

        assertArrayEquals(before, pkBytes());
        assertEquals(2, plan.entries());
        PkIndexRebuild.apply(f.store, DB, COLL, plan);
        assertEquals(List.of("a", "b"), f.indexedRows().stream().map(PkIndexEntry::getValue).toList());
    }

    @Test
    public void test_a_torn_leftover_intent_is_discarded_without_touching_the_page() throws IOException {
        f.seed(0, "a");
        final var page = f.firstPageText();
        final var intent = intentFor(WriteIntent.Delete.of(f.indexed("a")));
        Files.writeString(intent.log().toPath(), "torn", StandardCharsets.UTF_8);

        assertEquals(0, f.recovery.recoverPagesOf(DB, COLL));

        assertEquals(0, f.openIntents());
        assertEquals(page, f.firstPageText());
    }
}
