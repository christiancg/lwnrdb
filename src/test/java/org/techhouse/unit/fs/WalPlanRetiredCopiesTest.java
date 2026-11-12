package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.PageOccupancy;
import org.techhouse.fs.RetiredCopies;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class WalPlanRetiredCopiesTest extends FileSystemTestBase {
    private final Recorder recorder = new Recorder();

    private static final class Recorder implements RetiredCopies {
        private final List<String> retired = new ArrayList<>();
        private final List<String> oldLines = new ArrayList<>();
        private final List<String> born = new ArrayList<>();
        private String watched = TestGlobals.DB;
        private final List<String> mislabelledReads = new ArrayList<>();
        private boolean probeMislabelled;

        @Override
        public boolean watches(String dbName) {
            return dbName.equals(watched);
        }

        @Override
        public void retiring(String dbName, String collName, List<RetiredCopy> copies, List<String> bornIds,
                OldLines lines) {
            for (final var copy : copies) {
                if (probeMislabelled) {
                    try {
                        lines.read(new RetiredCopy("someone-else", copy.page(), copy.offset(), copy.length()));
                        mislabelledReads.add("read");
                    } catch (IOException refused) {
                        mislabelledReads.add(refused.getMessage());
                    }
                }
                retired.add(copy.id());
                try {
                    oldLines.add(new String(lines.read(copy), StandardCharsets.UTF_8).strip());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            born.addAll(bornIds);
        }
    }

    @BeforeEach
    public void installRecorder() {
        fs.snapshotFiles().onRetiredCopies(recorder);
    }

    @AfterEach
    public void restoreDefaults() throws Exception {
        fs.snapshotFiles().onRetiredCopies(RetiredCopies.NONE);
        TestUtils.setPrivateField(Configuration.getInstance(), "maxPageSize", ConfigKey.MAX_PAGE_SIZE.defaultValue());
    }

    private static DbEntry entry(String id, String value) {
        final var data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, id);
        data.addProperty("v", value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.setPage(0);
        return entry;
    }

    private static String lineOf(String id) {
        return "{\"_id\":\"" + id + "\",\"v\":\"one\"}";
    }

    @Test
    public void test_an_insert_reports_a_born_id_and_nothing_retired() throws IOException {
        fs.insertIntoCollection(entry("a", "one"));

        assertEquals(List.of("a"), recorder.born);
        assertTrue(recorder.retired.isEmpty());
    }

    @Test
    public void test_an_update_reports_the_old_line_before_it_is_blanked() throws Exception {
        final var row = fs.insertIntoCollection(entry("a", "one"));

        fs.updateFromCollection(entry("a", "two"), row);

        assertEquals(List.of("a"), recorder.retired);
        assertEquals(List.of(lineOf("a")), recorder.oldLines);
        assertEquals("two", readValueFromDisk(fs, "a"));
    }

    @Test
    public void test_a_copy_whose_line_holds_another_id_is_refused() throws IOException {
        final var row = fs.insertIntoCollection(entry("a", "one"));
        recorder.probeMislabelled = true;

        fs.updateFromCollection(entry("a", "two"), row);

        assertEquals(1, recorder.mislabelledReads.size());
        assertTrue(recorder.mislabelledReads.getFirst().contains("holds 'a'"), recorder.mislabelledReads.toString());
        assertEquals(List.of(lineOf("a")), recorder.oldLines);
    }

    @Test
    public void test_a_delete_reports_the_old_line() throws IOException {
        final var row = fs.insertIntoCollection(entry("a", "one"));

        fs.deleteFromCollection(row);

        assertEquals(List.of(lineOf("a")), recorder.oldLines);
    }

    @Test
    public void test_a_bulk_update_reports_every_old_line() throws IOException {
        final var first = fs.insertIntoCollection(entry("a", "one"));
        final var second = fs.insertIntoCollection(entry("b", "one"));

        fs.bulkUpdateFromCollection(TestGlobals.DB, TestGlobals.COLL,
                List.of(updateEntry(first, "a", "two"), updateEntry(second, "b", "two")));

        assertEquals(List.of("a", "b"), recorder.retired);
        assertEquals(List.of(lineOf("a"), lineOf("b")), recorder.oldLines);
    }

    @Test
    public void test_a_write_batch_reports_its_deletes_as_retired_and_its_inserts_as_born() throws IOException {
        final var doomed = fs.insertIntoCollection(entry("old", "one"));
        recorder.born.clear();

        fs.writeBatch(TestGlobals.DB, TestGlobals.COLL, List.of(entry("new", "one")), DbEntry::getPage,
                DbEntry::encodeLine, List.of(doomed));

        assertEquals(List.of("old"), recorder.retired);
        assertEquals(List.of("new"), recorder.born);
    }

    @Test
    public void test_a_vacuum_move_reports_nothing() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "maxPageSize", 1024L);
        final var rows = new ArrayList<PkIndexEntry>();
        for (final var id : List.of("a", "b", "c", "d")) {
            rows.add(fs.insertIntoCollection(entry(id, "x".repeat(200))));
        }
        for (final var row : rows.subList(0, 3)) {
            fs.deleteFromCollection(row);
        }
        final var survivor = rows.get(3);
        final var reported = recorder.retired.size();
        final var resident = new ArrayList<>(List.of(survivor));

        fs.vacuumPagesAfterDelete(TestGlobals.DB, TestGlobals.COLL, List.of(0L), resident,
                _ -> new PageOccupancy(survivor.getLength(), 1));

        final var moved = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).getFirst();
        assertTrue(moved.getPosition() < survivor.getPosition(), "the vacuum moved the survivor");
        assertEquals(reported, recorder.retired.size());
    }

    @Test
    public void test_an_unwatched_database_is_never_reported() throws IOException {
        recorder.watched = "elsewhere";
        final var row = fs.insertIntoCollection(entry("a", "one"));

        fs.updateFromCollection(entry("a", "two"), row);
        fs.deleteFromCollection(fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).getFirst());

        assertTrue(recorder.retired.isEmpty());
        assertTrue(recorder.born.isEmpty());
    }
}
