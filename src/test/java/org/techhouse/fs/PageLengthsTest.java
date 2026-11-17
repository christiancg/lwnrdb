package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestUtils;

public class PageLengthsTest {
    private static final String DB = "lengthsDb";
    private static final String COLL = "lengthsColl";
    private static final byte[] LINE = "{\"_id\":\"x\"}\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    File tmp;

    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c", "d");
    }

    private File page(long number) {
        return rf.fx.page(number);
    }

    private static void appendRaw(File file) throws IOException {
        Files.write(file.toPath(), LINE, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    public void test_seeds_with_one_stat_then_never_stats_again() throws IOException {
        final var seeded = page(0).length();
        assertEquals(PageLengths.UNKNOWN, PageLengths.knownLength(page(0)));

        assertEquals(seeded, PageLengths.lengthOf(page(0)));
        appendRaw(page(0));

        assertEquals(seeded, PageLengths.lengthOf(page(0)), "a remembered length is answered without a stat");
        assertEquals(seeded, PageLengths.of(rf.fx.paths, DB, COLL).applyAsLong(0L));
        assertEquals(PageLengths.UNKNOWN, PageLengths.terminatedLength(page(0)),
                "a seeded length says nothing about the page's last byte");
    }

    @Test
    public void test_a_collection_handle_seeds_and_reuses_each_page() throws IOException {
        appendRaw(page(1));
        final var lengths = PageLengths.of(rf.fx.paths, DB, COLL);

        assertEquals(page(1).length(), lengths.applyAsLong(1L));
        assertEquals(page(1).length(), PageLengths.knownLength(page(1)));
        assertEquals(0L, lengths.applyAsLong(7L), "a missing page reads as empty");
        assertEquals(PageLengths.UNKNOWN, PageLengths.knownLength(page(7)), "and is not remembered");
    }

    @Test
    public void test_an_own_append_grows_the_length_and_remembers_the_line_end() throws IOException {
        rf.replace("a", "longer-a", 0L, null);

        assertEquals(page(0).length(), PageLengths.knownLength(page(0)));
        assertEquals(page(0).length(), PageLengths.terminatedLength(page(0)));
    }

    @Test
    public void test_a_vacuum_rename_cuts_to_the_rewritten_length() throws IOException {
        rf.replacer.delete(rf.fx.indexed("b"));
        final var before = PageLengths.lengthOf(page(0));

        rf.vacuum.vacuum(DB, COLL, 0, PageRows.of(rf.fx.store, null, DB, COLL, 0));

        assertTrue(page(0).length() < before);
        assertEquals(page(0).length(), PageLengths.knownLength(page(0)));
    }

    @Test
    public void test_an_emptied_page_is_cut_to_zero() throws IOException {
        for (final var id : List.of("a", "b", "c", "d")) {
            rf.replacer.delete(rf.fx.indexed(id));
        }

        rf.vacuum.vacuum(DB, COLL, 0, PageRows.of(rf.fx.store, null, DB, COLL, 0));

        assertEquals(0L, page(0).length());
        assertEquals(0L, PageLengths.knownLength(page(0)));
    }

    @Test
    public void test_a_take_back_truncate_cuts() throws IOException {
        final var length = PageLengths.lengthOf(page(0));
        appendRaw(page(0));
        PageLengths.grew(page(0), page(0).length(), true);

        assertTrue(FileLocks.truncateTo(page(0), length));

        assertEquals(length, page(0).length());
        assertEquals(length, PageLengths.knownLength(page(0)));
        assertEquals(PageLengths.UNKNOWN, PageLengths.terminatedLength(page(0)));
    }

    @Test
    public void test_a_write_group_take_back_forgets_a_page_it_did_not_need_to_cut() throws IOException {
        final var length = PageLengths.lengthOf(page(0));
        PageLengths.grew(page(0), length + 5, true);
        final var group = new WalWriteGroup(0L);
        group.rememberLength(page(0), length);
        final var log = new File(tmp, "group.wal");
        Files.write(log.toPath(), new byte[0]);

        assertTrue(group.takeBack(log, 0L, rf.fx.paths.pkIndexFile(DB, COLL)));

        assertEquals(PageLengths.UNKNOWN, PageLengths.knownLength(page(0)));
        assertEquals(length, PageLengths.lengthOf(page(0)));
    }

    @Test
    public void test_a_drop_forgets_every_page_under_the_folder() throws IOException {
        final var other = new ReplacerFixture(tmp, DB, "otherColl");
        other.fx.seed(0, "z");
        PageLengths.lengthOf(page(0));
        PageLengths.lengthOf(other.fx.page(0));

        assertTrue(FileTreeDeleter.delete(rf.fx.paths.collectionFolder(DB, COLL)));

        assertEquals(PageLengths.UNKNOWN, PageLengths.knownLength(page(0)));
        assertEquals(other.fx.page(0).length(), PageLengths.knownLength(other.fx.page(0)));
    }

    @Test
    public void test_a_quarantine_forgets_every_page_under_the_folder() {
        PageLengths.lengthOf(page(0));

        assertTrue(new FolderQuarantine(rf.fx.paths).moveDatabaseAside(DB));

        assertEquals(PageLengths.UNKNOWN, PageLengths.knownLength(page(0)));
    }

    @Test
    public void test_an_external_change_is_seen_after_forget() throws IOException {
        final var seeded = PageLengths.lengthOf(page(0));
        appendRaw(page(0));

        PageLengths.forget(page(0));

        assertEquals(seeded + LINE.length, PageLengths.lengthOf(page(0)));
    }

    @Test
    public void test_a_replay_forgets_the_collection() throws IOException {
        PageLengths.lengthOf(page(0));

        WalRedo.replay(rf.fx.paths, DB, COLL, WalCodec.decodeAll(new byte[0]));

        assertEquals(PageLengths.UNKNOWN, PageLengths.knownLength(page(0)));
    }

    @Test
    public void test_files_that_are_not_tracked_pages_are_statted_every_time() throws IOException {
        final var pkIndex = rf.fx.paths.pkIndexFile(DB, COLL);
        final var farPage = rf.fx.paths.collectionPage(DB, COLL, PageLengths.MAX_TRACKED_PAGE + 1L);
        appendRaw(farPage);
        final var before = pkIndex.length();

        assertEquals(before, PageLengths.lengthOf(pkIndex));
        PageLengths.grew(pkIndex, before + 100, true);
        PageLengths.grew(farPage, 1L, true);

        assertEquals(before, PageLengths.lengthOf(pkIndex));
        assertEquals(PageLengths.UNKNOWN, PageLengths.knownLength(pkIndex));
        assertEquals(LINE.length, PageLengths.lengthOf(farPage));
        assertEquals(PageLengths.UNKNOWN, PageLengths.knownLength(new File(tmp, "coll-.dat")));
        assertEquals(PageLengths.UNKNOWN, PageLengths.knownLength(new File(tmp, "coll-1x.dat")));
    }

    @Test
    public void test_a_new_collection_folder_forgets_what_was_remembered_under_it() throws IOException {
        PageLengths.grew(page(0), 12_345L, true);
        PkIndexStore.forgetState(rf.fx.paths.pkIndexFile(DB, COLL));

        FileTreeDeleter.forgetWrittenLengthsUnder(rf.fx.paths.collectionFolder(DB, COLL));

        assertEquals(PageLengths.UNKNOWN, PageLengths.knownLength(page(0)));
        assertEquals(List.of("a", "b", "c", "d"),
                rf.fx.indexedRows().stream().map(PkIndexEntry::getValue).sorted().toList());
    }

    @Test
    public void test_a_page_past_the_tracked_range_is_statted_and_never_remembered() throws IOException {
        final var far = PageLengths.MAX_TRACKED_PAGE + 1L;
        appendRaw(page(far));
        final var lengths = PageLengths.of(rf.fx.paths, DB, COLL);

        assertEquals(page(far).length(), lengths.applyAsLong(far));
        appendRaw(page(far));

        assertEquals(page(far).length(), lengths.applyAsLong(far), "an untracked page is statted every time");
    }
}
