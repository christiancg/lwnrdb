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

    @Test
    public void test_a_malformed_line_does_not_throw_from_the_write_path() throws Exception {
        final var fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var first = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        first.set_id("keep");
        fileSystem.insertIntoCollection(first);
        final var indexFile = new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator
                + TestGlobals.COLL + File.separator + TestGlobals.COLL + "-pk.idx");
        java.nio.file.Files.writeString(indexFile.toPath(), "this is not a pk index line" + System.lineSeparator(),
                java.nio.file.StandardOpenOption.APPEND);

        final var second = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        second.set_id("added");
        assertDoesNotThrow(() -> fileSystem.insertIntoCollection(second),
                "one torn line must not fail every write, the same way it does not fail every read");

        final var index = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(index.stream().anyMatch(e -> e.getValue().equals("keep")));
        assertTrue(index.stream().anyMatch(e -> e.getValue().equals("added")));
    }

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

    // The stale-position fix: the caller must apply the returned compaction to keep survivors consistent.
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

    @Test
    public void test_single_update_multi_page_preserves_other_positions() throws Exception {
        FileSystem fs = FileSystemPages.freshFs();
        final var idxA = FileSystemPages.insertOnPage(fs, "a", 0);
        FileSystemPages.insertOnPage(fs, "b", 1);

        fs.updateFromCollection(FileSystemPages.updateEntry(idxA, "a", "updated-longer-value-for-a").toDbEntry(), idxA);

        assertEquals("short", FileSystemPages.readValueFromDisk(fs, "b"));
        assertEquals("updated-longer-value-for-a", FileSystemPages.readValueFromDisk(fs, "a"));
    }

    @Test
    public void test_single_update_same_page_shifts_only_later_position() throws Exception {
        FileSystem fs = FileSystemPages.freshFs();
        final var idxA = FileSystemPages.insertOnPage(fs, "a", 0);
        final var idxB = FileSystemPages.insertOnPage(fs, "b", 0);
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

        fs.deleteFromCollection(idxB);

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

    @Test
    public void test_a_self_heal_does_not_erase_a_concurrent_append() throws Exception {
        final var fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var seed = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        seed.set_id("seed");
        fileSystem.insertIntoCollection(seed);
        final var indexFile = new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator
                + TestGlobals.COLL + File.separator + TestGlobals.COLL + "-pk.idx");

        final var appended = java.util.Collections.synchronizedList(new ArrayList<String>());
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final var stop = new java.util.concurrent.atomic.AtomicBoolean();
        final var writer = new Thread(() -> {
            var i = 0;
            while (!stop.get()) {
                try {
                    final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
                    entry.set_id("appended" + i++);
                    fileSystem.insertIntoCollection(entry);
                    appended.add(entry.get_id());
                } catch (Exception e) {
                    failure.compareAndSet(null, e);
                    return;
                }
            }
        }, "pk-append");
        writer.start();
        try {
            for (var round = 0; round < 200; round++) {
                java.nio.file.Files.writeString(indexFile.toPath(), "torn line " + round + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.APPEND);
                fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
            }
        } finally {
            stop.set(true);
            writer.join(10_000L);
        }
        if (failure.get() != null) {
            throw new AssertionError("the appending writer failed", failure.get());
        }

        final var onDisk = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).stream()
                .map(PkIndexEntry::getValue).collect(java.util.stream.Collectors.toSet());
        final var lost = new ArrayList<String>();
        for (final var id : List.copyOf(appended)) {
            if (!onDisk.contains(id)) {
                lost.add(id);
            }
        }
        assertTrue(lost.isEmpty(), "the self-heal rewrote the whole file from a snapshot taken before these commits,"
                + " and nothing rebuilds pk.idx from the documents, so the loss is permanent: " + lost);
    }

    @Test
    public void test_two_concurrent_heals_do_not_collide() throws Exception {
        final var fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var seed = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        seed.set_id("seed");
        fileSystem.insertIntoCollection(seed);
        final var indexFile = new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator
                + TestGlobals.COLL + File.separator + TestGlobals.COLL + "-pk.idx");
        java.nio.file.Files.writeString(indexFile.toPath(), "torn" + System.lineSeparator(),
                java.nio.file.StandardOpenOption.APPEND);

        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final var threads = new ArrayList<Thread>();
        for (var t = 0; t < 4; t++) {
            final var thread = new Thread(() -> {
                try {
                    for (var i = 0; i < 50; i++) {
                        fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
                    }
                } catch (Exception e) {
                    failure.compareAndSet(null, e);
                }
            }, "pk-heal-" + t);
            threads.add(thread);
            thread.start();
        }
        for (final var thread : threads) {
            thread.join(10_000L);
        }

        assertNull(failure.get(), "two healers racing on the same file must not fail the read");
        assertTrue(fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).stream()
                .anyMatch(e -> e.getValue().equals("seed")));
    }

    private File pkIndexFile() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL
                + File.separator + TestGlobals.COLL + "-pk.idx");
    }

    private FileSystem seededFileSystem() throws Exception {
        final var fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        for (final var id : new String[]{"a", "b"}) {
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data.deepCopy().asJsonObject());
            entry.set_id(id);
            fileSystem.insertIntoCollection(entry);
        }
        return fileSystem;
    }

    @Test
    public void test_a_pk_index_where_no_line_parses_is_not_truncated() throws Exception {
        final var fileSystem = seededFileSystem();
        final var file = pkIndexFile();
        java.nio.file.Files.writeString(file.toPath(),
                "not a pk line at all" + System.lineSeparator() + "nor is this one" + System.lineSeparator());
        final var before = java.nio.file.Files.readAllBytes(file.toPath());

        final var read = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(read.isEmpty());
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(file.toPath()),
                "nothing rebuilds the pk index, so a read that understood none of it must never rewrite it");
    }

    @Test
    public void test_an_old_grammar_pk_index_is_left_intact() throws Exception {
        final var fileSystem = seededFileSystem();
        final var file = pkIndexFile();
        java.nio.file.Files.writeString(file.toPath(),
                "a|0|20|0|0" + System.lineSeparator() + "b|20|20|0|0" + System.lineSeparator());
        final var before = java.nio.file.Files.readAllBytes(file.toPath());

        final var read = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(read.isEmpty(), "a file in the pre-change grammar is unparseable, not half-readable");
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(file.toPath()),
                "an old data directory must fail loudly, never destructively");
    }

    @Test
    public void test_a_single_torn_line_among_good_ones_is_still_healed() throws Exception {
        final var fileSystem = seededFileSystem();
        final var file = pkIndexFile();
        java.nio.file.Files.writeString(file.toPath(), "torn line with no separators" + System.lineSeparator(),
                java.nio.file.StandardOpenOption.APPEND);

        final var read = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(2, read.size(), "the intact entries must survive: " + read);
        assertFalse(java.nio.file.Files.readString(file.toPath()).contains("torn line"),
                "one torn line among good ones must still be healed away");
    }

    @Test
    public void test_a_document_read_from_disk_carries_its_write_version() throws Exception {
        final var fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id("versioned");
        entry.setVersion(1720000000123L);
        final var indexEntry = fileSystem.insertIntoCollection(entry);

        final var readBack = fileSystem.getById(indexEntry);

        assertEquals(1720000000123L, readBack.getVersion(),
                "a positioned read must take its version from the index entry that located it");
    }

    @Test
    public void test_every_document_read_by_index_entries_carries_its_write_version() throws Exception {
        final var fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var indexEntries = new ArrayList<PkIndexEntry>();
        for (final var id : new String[]{"v1", "v2"}) {
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data.deepCopy().asJsonObject());
            entry.set_id(id);
            entry.setVersion(id.equals("v1") ? 11L : 22L);
            indexEntries.add(fileSystem.insertIntoCollection(entry));
        }

        final var read = fileSystem.getByIndexEntries(indexEntries);

        assertEquals(2, read.size());
        for (final var entry : read) {
            assertEquals(entry.get_id().equals("v1") ? 11L : 22L, entry.getVersion());
        }
    }
}
