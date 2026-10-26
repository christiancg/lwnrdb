package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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

public class WriteBatchTest {
    private static final String DB = "batchDb";
    private static final String COLL = "batchColl";
    private static final String WAL = COLL + Globals.WAL_EXTENSION;
    private static final long SHIPPED_MAX_PAGE_SIZE = 2_097_152L;
    private static final long SMALL_PAGE = 80L;

    @TempDir
    File tmp;

    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "maxPageSize", SHIPPED_MAX_PAGE_SIZE);
    }

    private List<DbEntry> inserts(long page, String... ids) {
        final var entries = new ArrayList<DbEntry>();
        for (final var id : ids) {
            entries.add(rf.entry(id, id, page));
        }
        return entries;
    }

    private void writeBatch(List<DbEntry> inserts, List<PkIndexEntry> deletes) throws IOException {
        rf.appender.bulkInsertAndDelete(DB, COLL, inserts, DbEntry::getPage,
                entry -> ReplacerFixture.line(entry.get_id(), entry.get_id()), deletes);
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
        assertEquals(expected, scanned(), "a page scan answers every written record once");
        final var indexed = new TreeMap<String, String>();
        for (final var row : rf.fx.indexedRows()) {
            indexed.put(row.getValue(), rf.fx.readAt(row));
        }
        final var records = new TreeMap<String, String>();
        expected.forEach((id, value) -> records.put(id, StorageFixture.record(id, value)));
        assertEquals(records, indexed, "the pk index points every id at its record");
    }

    @Test
    public void test_inserts_and_deletes_land_together() throws Exception {
        writeBatch(inserts(1, "x", "y"), List.of(rf.fx.indexed("a"), rf.fx.indexed("c")));

        assertHolds(Map.of("b", "old-b", "x", "x", "y", "y"));
    }

    @Test
    public void test_a_mixed_insert_and_delete_batch_forces_only_the_log_once() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            writeBatch(inserts(0, "x"), List.of(rf.fx.indexed("b")));

            assertEquals(List.of(WAL),
                    recorder.forcedNames().stream().filter(name -> !name.equals(COLL) && !name.equals(DB)).toList());
        }
    }

    @Test
    public void test_a_mixed_batch_record_replays_whole() throws Exception {
        try (var power = PowerLossFixture.install()) {
            rf = new ReplacerFixture(new File(tmp, "power"), DB, COLL);
            for (final var id : List.of("a", "b", "c")) {
                rf.appender.insert(rf.entry(id, "old-" + id, 0), ReplacerFixture.line(id, "old-" + id));
            }
            new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);
            writeBatch(inserts(1, "x"), List.of(rf.fx.indexed("a")));
            power.powerLoss();
            PowerLossFixture.restart(rf.fx.paths, DB, COLL);

            WalRedo.redoAll(rf.fx.paths);

            assertHolds(Map.of("b", "old-b", "c", "old-c", "x", "x"));
        }
    }

    @Test
    public void test_a_batch_whose_log_force_fails_leaves_nothing() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            recorder.failingFor(WAL);

            assertThrows(IOException.class, () -> writeBatch(inserts(1, "x"), List.of(rf.fx.indexed("a"))));

            recorder.failingFor(null);
            assertHolds(Map.of("a", "old-a", "b", "old-b", "c", "old-c"));
        }
    }

    @Test
    public void test_a_delete_on_a_vacuumed_insert_page_blanks_the_record_where_it_moved() throws Exception {
        rf.replacer.delete(rf.fx.indexed("a"));
        TestUtils.setPrivateField(Configuration.getInstance(), "maxPageSize", SMALL_PAGE);
        final var staleC = rf.fx.indexed("c");

        writeBatch(inserts(0, "d"), List.of(staleC));

        assertFalse(rf.vacuumed.isEmpty(), "the insert page had a quarter dead and was vacuumed first");
        assertHolds(Map.of("b", "old-b", "d", "d"));
        assertTrue(rf.fx.page(0).length() <= SMALL_PAGE, "the vacuum made room instead of overfilling the page");
    }

    @Test
    public void test_a_delete_past_its_page_end_is_refused_before_anything_is_logged() throws Exception {
        final var phantom = new PkIndexEntry(DB, COLL, "ghost", 10_000L, 24L, 0L, StorageFixture.OLD_VERSION);

        assertThrows(IOException.class, () -> writeBatch(inserts(1, "x"), List.of(phantom)));

        assertHolds(Map.of("a", "old-a", "b", "old-b", "c", "old-c"));
    }
}
