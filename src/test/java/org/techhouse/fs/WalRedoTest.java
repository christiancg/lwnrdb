package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestUtils;

public class WalRedoTest {
    private static final String DB = "redoDb";
    private static final String COLL = "redoColl";

    @TempDir
    File tmp;

    private PowerLossFixture power;
    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        power = PowerLossFixture.install();
        rf = new ReplacerFixture(tmp, DB, COLL);
        for (final var id : List.of("a", "b", "c")) {
            insert(id);
        }
        new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        power.close();
    }

    private void insert(String id) throws IOException {
        rf.appender.insert(rf.entry(id, "old-" + id, 0), ReplacerFixture.line(id, "old-" + id));
    }

    private List<PkIndexEntry> redo() throws IOException {
        PowerLossFixture.restart(rf.fx.paths, DB, COLL);
        return WalRedo.redoAll(rf.fx.paths);
    }

    private File log() {
        return rf.fx.paths.writeAheadLog(DB, COLL);
    }

    private Map<String, String> scanned() throws IOException {
        final var values = new TreeMap<String, String>();
        try (var entries = new DocumentPageStore(rf.fx.paths).streamEntries(DB, COLL)) {
            entries.forEach(entry -> values.merge(entry.get_id(), entry.getData().get("v").asJsonString().getValue(),
                    (first, second) -> first + "," + second));
        }
        return values;
    }

    private void assertHolds(Map<String, String> expected) throws IOException {
        assertEquals(expected, scanned(), "a page scan answers every acknowledged write once");
        final var indexed = new TreeMap<String, String>();
        for (final var row : rf.fx.indexedRows()) {
            indexed.put(row.getValue(), rf.fx.readAt(row));
        }
        final var records = new TreeMap<String, String>();
        expected.forEach((id, value) -> records.put(id, StorageFixture.record(id, value)));
        assertEquals(records, indexed, "the pk index points every id at its record");
    }

    private static Map<String, String> original() {
        return Map.of("a", "old-a", "b", "old-b", "c", "old-c");
    }

    private Map<String, String> originalWith(String id, String value) {
        final var expected = new TreeMap<>(original());
        expected.put(id, value);
        return expected;
    }

    @Test
    public void test_an_acknowledged_insert_survives_losing_its_page_and_pk_bytes() throws Exception {
        insert("d");
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
    public void test_without_synced_writes_unlogged_bytes_are_kept() throws Exception {
        final var page = rf.fx.page(0);
        Files.writeString(page.toPath(), StorageFixture.record("z", "maybe-acknowledged"), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        final var landed = page.length();
        TestUtils.setPrivateField(Configuration.getInstance(), "syncWrites", "false");

        redo();

        assertEquals(landed, page.length(), "unsynced, those bytes may be an acknowledged write whose record was lost");
    }

    @Test
    public void test_a_torn_last_record_is_dropped() throws Exception {
        insert("d");
        power.powerLoss();
        final var bytes = Files.readAllBytes(log().toPath());
        Files.write(log().toPath(), Arrays.copyOf(bytes, bytes.length - 3));

        redo();

        assertHolds(original());
        assertEquals(List.of(), WalCodec.decodeAll(Files.readAllBytes(log().toPath())).records());
    }

    @Test
    public void test_redo_twice_ends_the_same() throws Exception {
        insert("d");
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
        insert("d");
        final var group = new ForceGroup();
        group.add(rf.fx.page(0).toPath());
        group.add(rf.fx.paths.pkIndexFile(DB, COLL).toPath());
        group.force();
        power.powerLoss();

        redo();

        assertHolds(originalWith("d", "old-d"));
    }

    @Test
    public void test_a_clean_log_replays_nothing_and_stays_a_checkpoint() throws Exception {
        final var before = Files.readAllBytes(rf.fx.page(0).toPath());

        assertEquals(List.of(), redo());

        assertArrayEquals(before, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertEquals(0, WalCodec.decodeAll(Files.readAllBytes(log().toPath())).framesSinceCheckpoint());
    }

    @Test
    public void test_a_log_without_a_checkpoint_is_left_alone() throws Exception {
        Files.write(log().toPath(), new byte[]{1, 2, 3});

        assertEquals(List.of(), redo());

        assertHolds(original());
    }

    @Test
    public void test_a_log_in_a_quarantined_folder_is_not_replayed() throws Exception {
        insert("d");
        power.powerLoss();
        final var folder = rf.fx.paths.collectionFolder(DB, COLL);
        final var aside = new File(folder.getParentFile(), COLL + Globals.QUARANTINE_INFIX + "1");
        Files.move(folder.toPath(), aside.toPath());

        assertEquals(List.of(), redo());

        assertFalse(Files.readString(new File(aside, rf.fx.page(0).getName()).toPath(), StandardCharsets.UTF_8)
                .contains("old-d"), "a quarantined collection is evidence, never replayed into");
    }
}
