package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.RequiresDirectorySync;
import org.techhouse.test.RequiresFileKeys;
import org.techhouse.test.TestUtils;

@RequiresDirectorySync
@RequiresFileKeys
public class WalRedoTest {
    private static final String DB = WalRedoFixture.DB;
    private static final String COLL = WalRedoFixture.COLL;

    @TempDir
    File tmp;

    private PowerLossFixture power;
    private WalRedoFixture redoFixture;
    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        power = PowerLossFixture.install();
        redoFixture = new WalRedoFixture(tmp);
        rf = redoFixture.rf;
    }

    @AfterEach
    public void tearDown() throws Exception {
        power.close();
    }

    private void insertAfterTheCheckpoint() throws IOException {
        redoFixture.insert("d");
    }

    private List<PkIndexEntry> redo() throws IOException {
        return redoFixture.redo();
    }

    private File log() {
        return redoFixture.log();
    }

    private Map<String, String> scanned() throws IOException {
        return redoFixture.scanned();
    }

    private void assertHolds(Map<String, String> expected) throws IOException {
        redoFixture.assertHolds(expected);
    }

    private static Map<String, String> original() {
        return WalRedoFixture.original();
    }

    private static Map<String, String> originalWith(String id, String value) {
        return WalRedoFixture.originalWith(id, value);
    }

    @Test
    public void test_an_acknowledged_insert_survives_losing_its_page_and_pk_bytes() throws Exception {
        insertAfterTheCheckpoint();
        power.powerLoss();
        assertEquals(original(), scanned(), "the power loss took the unforced insert");

        redo();

        assertHolds(originalWith("d", "old-d"));
    }

    @Test
    public void test_an_acknowledged_update_survives_its_blank_landing_without_its_copy() throws Exception {
        final var page = rf.fx.page(0);
        final var lengthBefore = page.length();
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        power.powerLoss(Map.of(page, lengthBefore));
        assertEquals(Map.of("a", "old-a", "c", "old-c"), scanned(), "only the blank landed");

        redo();

        assertHolds(originalWith("b", "new-b"));
    }

    @Test
    public void test_an_acknowledged_delete_survives_losing_its_pk_row() throws Exception {
        rf.replacer.delete(rf.fx.indexed("b"));
        power.powerLoss();

        final var completed = redo();

        assertHolds(Map.of("a", "old-a", "c", "old-c"));
        assertEquals(List.of("b"), completed.stream().map(PkIndexEntry::getValue).toList());
        assertEquals(DB, completed.getFirst().getDatabaseName());
        assertEquals(0L, completed.getFirst().getPage());
    }

    @Test
    public void test_a_bulk_insert_and_a_bulk_update_are_redone_whole() throws Exception {
        rf.appender.bulkInsert(DB, COLL, new ArrayList<>(List.of(rf.entry("x", "x", 0), rf.entry("y", "y", 1))),
                DbEntry::getPage, entry -> ReplacerFixture.line(entry.get_id(), entry.get_id()));
        rf.bulk(List.of(rf.indexed("a", "bulk-a"), rf.indexed("c", "bulk-c")));
        power.powerLoss();

        redo();

        assertHolds(Map.of("a", "bulk-a", "b", "old-b", "c", "bulk-c", "x", "x", "y", "y"));
    }

    @Test
    public void test_an_update_relocated_to_another_page() throws Exception {
        rf.replace("b", "moved-b", 1, null);
        power.powerLoss();

        redo();

        assertHolds(originalWith("b", "moved-b"));
        assertEquals(1L, rf.fx.indexed("b").getPage());
    }

    @Test
    public void test_a_page_created_after_the_checkpoint_and_never_logged_is_emptied() throws Exception {
        final var stray = rf.fx.page(5);
        Files.writeString(stray.toPath(), StorageFixture.record("z", "never-logged"), StandardCharsets.UTF_8);
        power.force(null, stray.toPath());
        power.force(null, stray.getParentFile().toPath());
        power.powerLoss();

        redo();

        assertEquals(0L, stray.length());
        assertHolds(original());
    }

    @Test
    public void test_an_unlogged_write_whose_bytes_landed_is_cut() throws Exception {
        final var page = rf.fx.page(0);
        final var logged = page.length();
        Files.writeString(page.toPath(), StorageFixture.record("z", "never-logged"), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        power.force(null, page.toPath());
        power.powerLoss();

        redo();

        assertEquals(logged, page.length());
        assertHolds(original());
    }

    @Test
    public void test_replay_reports_every_id_written_since_the_checkpoint() throws Exception {
        insertAfterTheCheckpoint();
        rf.replace("b", "moved-b", 1, null);
        rf.replacer.delete(rf.fx.indexed("c"));
        power.powerLoss();

        final var written = redo();

        assertEquals(List.of("d", "b", "c"), written.stream().map(PkIndexEntry::getValue).toList());
        assertEquals(List.of(0L, 1L, 0L), written.stream().map(PkIndexEntry::getPage).toList());
    }

    @Test
    public void test_a_replayed_log_keeps_its_records() throws Exception {
        insertAfterTheCheckpoint();
        power.powerLoss();

        redo();

        assertEquals(1, WalCodec.decodeAll(Files.readAllBytes(log().toPath())).records().size(),
                "the records cover the replayed ids until a checkpoint finds their index work done");
    }

    @Test
    public void test_a_torn_last_record_is_dropped() throws Exception {
        insertAfterTheCheckpoint();
        power.powerLoss();
        final var bytes = Files.readAllBytes(log().toPath());
        Files.write(log().toPath(), Arrays.copyOf(bytes, (int) OutsideWrites.logEnd(log()) - 3));

        redo();

        assertHolds(original());
        assertEquals(List.of(), WalCodec.decodeAll(Files.readAllBytes(log().toPath())).records());
    }

    @Test
    public void test_redo_twice_ends_the_same() throws Exception {
        insertAfterTheCheckpoint();
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        power.powerLoss();
        final var log = Files.readAllBytes(log().toPath());
        redo();
        final var page = Files.readAllBytes(rf.fx.page(0).toPath());
        final var pk = Files.readAllBytes(rf.fx.paths.pkIndexFile(DB, COLL).toPath());
        Files.write(log().toPath(), log);

        redo();

        assertArrayEquals(page, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertArrayEquals(pk, Files.readAllBytes(rf.fx.paths.pkIndexFile(DB, COLL).toPath()));
    }

    @Test
    public void test_an_interrupted_checkpoint_replays_safely() throws Exception {
        insertAfterTheCheckpoint();
        final var group = new ForceGroup();
        group.add(rf.fx.page(0).toPath());
        group.add(rf.fx.paths.pkIndexFile(DB, COLL).toPath());
        group.force();
        power.powerLoss();

        redo();

        assertHolds(originalWith("d", "old-d"));
    }

    @Test
    public void test_a_log_in_a_quarantined_folder_is_not_replayed() throws Exception {
        insertAfterTheCheckpoint();
        power.powerLoss();
        final var folder = rf.fx.paths.collectionFolder(DB, COLL);
        final var aside = new File(folder.getParentFile(), COLL + Globals.QUARANTINE_INFIX + "1");
        Files.move(folder.toPath(), aside.toPath());

        assertEquals(List.of(), redo());

        assertFalse(Files.readString(new File(aside, rf.fx.page(0).getName()).toPath(), StandardCharsets.UTF_8)
                .contains("old-d"), "a quarantined collection is evidence, never replayed into");
    }

    @Test
    public void test_a_lost_group_keeps_every_acknowledged_version() throws Exception {
        final var wal = new WriteAheadLog(rf.fx.paths);
        final var page = rf.fx.page(0);
        final var pk = rf.fx.paths.pkIndexFile(DB, COLL);
        wal.openGroup(DB, COLL);
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        rf.replacer.delete(rf.fx.indexed("c"));
        power.powerLoss(Map.of(page, page.length(), pk, pk.length()));

        redo();

        assertHolds(original());
    }

    @Test
    public void test_a_group_whose_log_prefix_landed_keeps_only_that_prefix() throws Exception {
        final var wal = new WriteAheadLog(rf.fx.paths);
        final var page = rf.fx.page(0);
        final var pk = rf.fx.paths.pkIndexFile(DB, COLL);
        wal.openGroup(DB, COLL);
        rf.replace("a", "new-a", 0, PagePlacement.APPEND);
        final var firstRecordEnd = OutsideWrites.logEnd(log());
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        power.powerLoss(Map.of(log(), firstRecordEnd, page, page.length(), pk, pk.length()));

        redo();

        assertHolds(originalWith("a", "new-a"));
    }

    @Test
    public void test_ids_a_checkpoint_logged_as_pending_are_reported_for_repair() throws Exception {
        final var original = TestUtils.getPrivateStaticField(WriteAheadLog.class, "guard", CheckpointGuard.class);
        WriteAheadLog.guardWith(new CheckpointGuard() {
            @Override
            public boolean holdsIndexWork(String dbName, String collName) {
                return true;
            }

            @Override
            public Collection<String> pendingIds(String dbName, String collName) {
                return List.of("b", "gone");
            }
        });
        try {
            insertAfterTheCheckpoint();
            new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);
        } finally {
            WriteAheadLog.guardWith(original);
        }
        power.powerLoss();

        final var reported = redo().stream().map(PkIndexEntry::getValue).toList();

        assertEquals(List.of("b", "gone"), reported, "the checkpoint dropped d's record because d was not pending");
    }

    @Test
    public void test_a_lost_group_cuts_pk_idx_back_to_its_logged_base() throws Exception {
        final var wal = new WriteAheadLog(rf.fx.paths);
        final var page = rf.fx.page(0);
        final var pk = rf.fx.paths.pkIndexFile(DB, COLL);
        final var base = pk.length();
        wal.openGroup(DB, COLL);
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        power.powerLoss(Map.of(page, page.length(), pk, pk.length()));

        redo();

        assertEquals(base, pk.length(), "no durable record touched pk.idx, so the base is what bounds it");
        assertHolds(original());
    }

    @Test
    public void test_a_longer_rebuilt_pk_idx_is_kept_when_no_group_ran() throws Exception {
        final var rebuilt = writeALongerPkIndex();
        power.powerLoss();

        redo();

        assertEquals(rebuilt, Files.readString(rf.fx.paths.pkIndexFile(DB, COLL).toPath(), StandardCharsets.UTF_8),
                "without a group base only a durable record that touched pk.idx can cut it");
    }

    @Test
    public void test_a_rebuild_after_a_group_interval_is_kept() throws Exception {
        final var wal = new WriteAheadLog(rf.fx.paths);
        wal.openGroup(DB, COLL);
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        wal.closeGroup(DB, COLL);
        wal.checkpoint(DB, COLL);
        final var rebuilt = writeALongerPkIndex();
        power.powerLoss();

        redo();

        assertEquals(rebuilt, Files.readString(rf.fx.paths.pkIndexFile(DB, COLL).toPath(), StandardCharsets.UTF_8),
                "the rebuild's leading checkpoint dropped the base, so the longer file survives");
    }

    private String writeALongerPkIndex() throws IOException {
        final var pk = rf.fx.paths.pkIndexFile(DB, COLL);
        final var rebuilt = Files.readString(pk.toPath(), StandardCharsets.UTF_8) + "a\u001f0\u001f0\u001f0\u001f1\n";
        Files.writeString(pk.toPath(), rebuilt, StandardCharsets.UTF_8);
        power.force(null, pk.toPath());
        return rebuilt;
    }

    private File appendIndexLineOfD() throws IOException {
        final var folder = rf.fx.paths.collectionFolder(DB, COLL);
        final var idxlog = new File(folder, COLL + "-v-String.idxlog");
        FieldIndexLog.append(new File(folder, COLL + "-v-String.idx"), idxlog,
                List.of(new IndexLogChange("old-d", List.of("d"), List.of())));
        return idxlog;
    }

    @Test
    public void test_an_index_line_lost_with_its_record_is_named_for_repair() throws Exception {
        insertAfterTheCheckpoint();
        final var idxlog = appendIndexLineOfD();
        power.powerLoss();
        assertFalse(idxlog.exists() && Files.readString(idxlog.toPath(), StandardCharsets.UTF_8).contains("d"),
                "the power loss took the unforced index line");

        final var written = redo();

        assertEquals(List.of("d"), written.stream().map(PkIndexEntry::getValue).toList(),
                "the replay names the id, so startup re-queues its index work");
    }

    @Test
    public void test_a_checkpoint_makes_the_index_lines_it_drops_the_records_of_durable() throws Exception {
        insertAfterTheCheckpoint();
        final var idxlog = appendIndexLineOfD();
        new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);
        power.powerLoss();

        assertEquals(List.of(), redo());

        assertTrue(Files.readString(idxlog.toPath(), StandardCharsets.UTF_8).contains("d"));
    }
}
