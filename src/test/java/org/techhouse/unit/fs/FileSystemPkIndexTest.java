package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemPkIndexTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() {
        File dbDir = new File(TestGlobals.PATH);
        if (dbDir.exists() && dbDir.isDirectory() && dbDir.canRead() && dbDir.canWrite()
                && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    // deleteFromCollection returns the compaction (removed row's coordinates) when a survivor moves.
    @Test
    public void test_delete_returns_compaction() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var entry1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry1.set_id("1");
        final var entry2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry2.set_id("2");
        final var pk1 = fileSystem.insertIntoCollection(entry1);
        fileSystem.insertIntoCollection(entry2);

        final var compaction = fileSystem.deleteFromCollection(pk1);

        assertNotNull(compaction);
        assertEquals(TestGlobals.DB, compaction.dbName());
        assertEquals(TestGlobals.COLL, compaction.collName());
        assertEquals(pk1.getPage(), compaction.page());
        assertEquals(pk1.getPosition(), compaction.removedPosition());
        assertEquals(pk1.getLength(), compaction.removedLength());
    }

    // Deleting the last entry on a page moves no survivor, so the returned compaction is null.
    @Test
    public void test_delete_last_entry_returns_null_compaction()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id("1");
        final var pk = fileSystem.insertIntoCollection(entry);

        assertNull(fileSystem.deleteFromCollection(pk));
    }

    // Two sequential deletes on the same page do not crash when the caller applies the returned
    // compaction to keep the surviving entries' positions consistent (the stale-position fix).
    @Test
    public void test_sequential_deletes_applying_compaction_no_crash() throws Exception {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var pks = new ArrayList<PkIndexEntry>();
        for (var id : List.of("1", "2", "3")) {
            final var e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
            e.set_id(id);
            pks.add(fileSystem.insertIntoCollection(e));
        }

        applyCompaction(pks, fileSystem.deleteFromCollection(pks.get(0)));
        // Without applying the position fix, this second delete would use a stale position and throw.
        assertDoesNotThrow(() -> applyCompaction(pks, fileSystem.deleteFromCollection(pks.get(1))));

        final var survivor = fileSystem.getById(pks.get(2));
        assertEquals("test", survivor.getData().get("name").asJsonString().getValue());
    }

    // Mirrors Cache.shiftPkPositionsAfterCompaction for the in-test PkIndexEntry list.
    private static void applyCompaction(List<PkIndexEntry> pks, org.techhouse.fs.PkCompaction compaction) {
        if (compaction == null) {
            return;
        }
        for (final var pk : pks) {
            if (pk.getPage() == compaction.page() && pk.getPosition() > compaction.removedPosition()) {
                pk.setPosition(pk.getPosition() - compaction.removedLength());
            }
        }
    }

    // A stale position past the end of file no longer throws NegativeArraySizeException (over-EOF guard).
    @Test
    public void test_delete_with_over_eof_position_does_not_throw()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id("1");
        final var pk = fileSystem.insertIntoCollection(entry);
        // Same id as the inserted row (so the PK-index removal succeeds) but a position past EOF,
        // simulating a stale cached position; the over-EOF guard must avoid the negative-array crash.
        final var stale = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", pk.getPosition() + 1000,
                pk.getLength(), pk.getPage());

        assertDoesNotThrow(() -> fileSystem.deleteFromCollection(stale));
    }

    // ── PK-index per-page reindex correctness (regression for bulk/multi-page update corruption) ──

    // Reads the entry back the way a cache-disabled / post-restart read does: from the persisted PK
    // index file, not from any in-memory copy. Also asserts the stored position is not corrupt.

    @Test
    public void test_single_update_multi_page_preserves_other_positions() throws Exception {
        FileSystem fs = FileSystemPages.freshFs();
        final var idxA = FileSystemPages.insertOnPage(fs, "a", 0);
        FileSystemPages.insertOnPage(fs, "b", 1);

        // Updating 'a' (page 0) must not disturb 'b' on page 1.
        fs.updateFromCollection(FileSystemPages.updateEntry(idxA, "a", "updated-longer-value-for-a").toDbEntry(), idxA);

        assertEquals("short", FileSystemPages.readValueFromDisk(fs, "b"));
        assertEquals("updated-longer-value-for-a", FileSystemPages.readValueFromDisk(fs, "a"));
    }

    @Test
    public void test_single_update_same_page_shifts_only_later_position() throws Exception {
        FileSystem fs = FileSystemPages.freshFs();
        final var idxA = FileSystemPages.insertOnPage(fs, "a", 0);
        final var idxB = FileSystemPages.insertOnPage(fs, "b", 0); // positioned after 'a' on page 0
        final long bPosBefore = idxB.getPosition();
        assertTrue(bPosBefore > 0, "second same-page entry should start past position 0");

        fs.updateFromCollection(FileSystemPages.updateEntry(idxA, "a", "updated-longer-value-for-a").toDbEntry(), idxA);

        final var disk = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        final var bDisk = disk.stream().filter(p -> p.getValue().equals("b")).findFirst().orElseThrow();
        assertEquals(bPosBefore - idxA.getLength(), bDisk.getPosition(),
                "'b' shifts toward the start by 'a's old length after 'a' is relocated");
        assertEquals("short", fs.getById(bDisk).getData().get("v").asJsonString().getValue());
        assertEquals("updated-longer-value-for-a", FileSystemPages.readValueFromDisk(fs, "a"));
    }

    @Test
    public void test_delete_same_page_preserves_survivor_positions() throws Exception {
        FileSystem fs = FileSystemPages.freshFs();
        FileSystemPages.insertOnPage(fs, "a", 0);
        final var idxB = FileSystemPages.insertOnPage(fs, "b", 0);
        FileSystemPages.insertOnPage(fs, "c", 0);

        fs.deleteFromCollection(idxB); // delete the middle entry

        assertEquals("short", FileSystemPages.readValueFromDisk(fs, "a"));
        assertEquals("short", FileSystemPages.readValueFromDisk(fs, "c"));
        final var disk = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(disk.stream().noneMatch(p -> p.getValue().equals("b")), "'b' must be removed from the index");
    }

    @Test
    public void test_compactTombstones_dedups_and_drops_old() throws Exception {
        final var fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "keep", 1000L);
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "keep", 2000L);
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "old", 100L);
        fs.compactTombstones(TestGlobals.DB, TestGlobals.COLL, 500L);
        final var tombstones = fs.readTombstones(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, tombstones.size());
        assertEquals(2000L, tombstones.get("keep"));
        assertNull(tombstones.get("old"));
    }

    @Test
    public void test_compactTombstones_missing_file_is_noop() throws Exception {
        final var fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.compactTombstones(TestGlobals.DB, "noSuchColl", 0L);
        assertTrue(fs.readTombstones(TestGlobals.DB, "noSuchColl").isEmpty());
    }
}
