package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestUtils;

public class WalRewriteBarrierTest {
    private static final String DB = "barrierDb";
    private static final String COLL = "barrierColl";

    @TempDir
    File tmp;

    private PowerLossFixture power;
    private ReplacerFixture rf;
    private WriteAheadLog wal;

    @BeforeEach
    public void setUp() throws Exception {
        power = PowerLossFixture.install();
        rf = new ReplacerFixture(tmp, DB, COLL);
        wal = new WriteAheadLog(rf.fx.paths);
        for (final var id : List.of("a", "b", "c")) {
            rf.appender.insert(rf.entry(id, "old-" + id, 0), ReplacerFixture.line(id, "old-" + id));
        }
        wal.checkpoint(DB, COLL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        power.close();
    }

    private void redo() throws IOException {
        PowerLossFixture.restart(rf.fx.paths, DB, COLL);
        WalRedo.redoAll(rf.fx.paths);
    }

    private WalCodec.Decoded decodedLog() throws IOException {
        return WalCodec.decodeAll(Files.readAllBytes(rf.fx.paths.writeAheadLog(DB, COLL).toPath()));
    }

    private void vacuum() throws IOException {
        rf.vacuum.vacuum(DB, COLL, 0, PageRows.of(rf.fx.store, null, DB, COLL, 0));
    }

    private void deleteTracked(String id) throws IOException {
        final var rows = rf.fx.indexedRows();
        rf.replacer.delete(rf.fx.indexed(id), rows, page -> {
            final List<PkIndexEntry> onPage = rows.stream().filter(row -> row.getPage() == page).toList();
            return new PageOccupancy(PageRows.liveBytes(onPage), onPage.size());
        });
    }

    private void assertHolds(Map<String, String> expected) throws IOException {
        final var scanned = new TreeMap<String, String>();
        try (var entries = new DocumentPageStore(rf.fx.paths).streamEntries(DB, COLL)) {
            entries.forEach(entry -> scanned.merge(entry.get_id(), entry.getData().get("v").asJsonString().getValue(),
                    (first, second) -> first + "," + second));
        }
        assertEquals(expected, scanned, "a page scan answers every acknowledged write once");
        final var indexed = new TreeMap<String, String>();
        for (final var row : rf.fx.indexedRows()) {
            indexed.put(row.getValue(), rf.fx.readAt(row));
        }
        final var records = new TreeMap<String, String>();
        expected.forEach((id, value) -> records.put(id, StorageFixture.record(id, value)));
        assertEquals(records, indexed, "the pk index points every id at its record");
    }

    private void appendUnloggedRecord(File page) throws IOException {
        Files.writeString(page.toPath(), StorageFixture.record("ghost", "never-logged"), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        power.force(null, page.toPath());
    }

    @Test
    public void test_changes_before_a_rewrite_begin_are_not_replayed_onto_the_page() throws Exception {
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        rf.replacer.delete(rf.fx.indexed("a"));
        vacuum();
        final var compacted = Files.readAllBytes(rf.fx.page(0).toPath());
        assertEquals(2,
                decodedLog().records().stream().filter(
                        record -> record.changes().stream().anyMatch(change -> change.target() != WalTarget.PK_WRITE))
                        .count(),
                "the writes before the vacuum are still in the log, addressing the old layout");
        power.powerLoss();

        redo();

        assertArrayEquals(compacted, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertHolds(Map.of("b", "new-b", "c", "old-c"));
    }

    @Test
    public void test_a_begin_without_end_never_cuts_below_length_before() throws Exception {
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        final var page = rf.fx.page(0);
        final var barrier = new WalRewriteBarrier(wal);
        barrier.settle(DB, COLL);
        final var lengthBefore = page.length();
        barrier.begin(DB, COLL, 0, lengthBefore);
        appendUnloggedRecord(page);
        power.powerLoss();

        redo();

        assertEquals(lengthBefore, page.length(), "the unlogged tail goes, nothing the page held at BEGIN does");
        assertHolds(Map.of("a", "old-a", "b", "new-b", "c", "old-c"));
    }

    @Test
    public void test_a_lost_group_append_after_a_vacuum_is_cut_to_length_after() throws Exception {
        rf.replacer.delete(rf.fx.indexed("a"));
        rf.replacer.delete(rf.fx.indexed("b"));
        vacuum();
        final var page = rf.fx.page(0);
        final var lengthAfter = page.length();
        assertTrue(lengthAfter < decodedLog().checkpoint().pageLengths().get(0L), "the checkpoint names a longer page");
        appendUnloggedRecord(page);
        power.powerLoss();

        redo();

        assertEquals(lengthAfter, page.length(), "the checkpoint's longer length no longer shields unlogged bytes");
        assertHolds(Map.of("c", "old-c"));
    }

    @Test
    public void test_appends_after_a_truncation_replay_from_offset_zero() throws Exception {
        TestUtils.setPrivateField(org.techhouse.config.Configuration.getInstance(), "maxPageSize", 10_000L);
        try {
            for (final var id : List.of("a", "b", "c")) {
                deleteTracked(id);
            }
            assertEquals(0L, rf.fx.page(0).length(), "the last delete emptied the page");
            rf.appender.insert(rf.entry("d", "old-d", 0), ReplacerFixture.line("d", "old-d"));
            assertEquals(0L, rf.fx.indexed("d").getPosition());
            power.powerLoss();

            redo();

            assertHolds(Map.of("d", "old-d"));
            assertEquals(ReplacerFixture.line("d", "old-d").length, rf.fx.page(0).length());
        } finally {
            TestUtils.setPrivateField(org.techhouse.config.Configuration.getInstance(), "maxPageSize", 2_097_152L);
        }
    }

    @Test
    public void test_a_vacuum_inside_a_write_group_settles_the_group_first() throws Exception {
        TestUtils.setPrivateField(org.techhouse.config.Configuration.getInstance(), "maxPageSize", 75L);
        try {
            wal.openGroup(DB, COLL);
            rf.replace("c", "new-c", 1, null);
            assertNotNull(wal.openGroupOf(DB, COLL));

            deleteTracked("a");

            assertEquals(StorageFixture.record("b", "old-b"), rf.fx.firstPageText(),
                    "the group's deferred blank of c landed before the page was read");
            assertTrue(wal.closeGroup(DB, COLL));
            power.powerLoss();

            redo();

            assertHolds(Map.of("b", "old-b", "c", "new-c"));
        } finally {
            TestUtils.setPrivateField(org.techhouse.config.Configuration.getInstance(), "maxPageSize", 2_097_152L);
        }
    }

    @Test
    public void test_a_failed_folder_sync_checkpoints_instead_of_ending_the_barrier() throws Exception {
        power.close();
        try (var recorder = RecordingForcer.install(true)) {
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            final var barrier = new WalRewriteBarrier(wal);
            barrier.settle(DB, COLL);
            final var begun = barrier.begin(DB, COLL, 0, rf.fx.page(0).length());
            assertTrue(decodedLog().rewrites().containsKey(0L));
            TestUtils.getPrivateField(recorder, "foldersBeforeFailing", AtomicInteger.class).set(0);

            assertFalse(barrier.landed(begun, rf.fx.paths.collectionFolder(DB, COLL).toPath()));

            assertEquals(Map.of(), decodedLog().rewrites(), "the checkpoint replaced the barrier");
            assertEquals(List.of(), decodedLog().records());
        }
        power = PowerLossFixture.install();
    }

    @Test
    public void test_require_applied_honours_a_rewrite_barrier() throws Exception {
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        rf.replacer.delete(rf.fx.indexed("a"));
        vacuum();
        final var compacted = Files.readAllBytes(rf.fx.page(0).toPath());
        wal.markUnapplied(DB, COLL);

        wal.requireApplied(DB, COLL);

        assertArrayEquals(compacted, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertHolds(Map.of("b", "new-b", "c", "old-c"));
    }
}
