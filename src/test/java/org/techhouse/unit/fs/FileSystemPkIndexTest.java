package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;

public class FileSystemPkIndexTest extends FileSystemTestBase {
    @Test
    public void test_a_malformed_line_does_not_throw_from_the_write_path() throws Exception {
        final var fileSystem = fs;
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
    public void test_a_delete_moves_no_other_record() throws IOException {
        final var fileSystem = fs;
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var entry1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry1.set_id("1");
        final var entry2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry2.set_id("2");
        final var pk1 = fileSystem.insertIntoCollection(entry1);
        final var pk2 = fileSystem.insertIntoCollection(entry2);

        fileSystem.deleteFromCollection(pk1);

        final var rows = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(List.of(pk2.toFileEntry()), rows.stream().map(PkIndexEntry::toFileEntry).toList());
    }

    @Test
    public void test_deleting_the_last_entry_leaves_an_empty_index() throws IOException {
        final var fileSystem = fs;
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id("1");
        final var pk = fileSystem.insertIntoCollection(entry);

        fileSystem.deleteFromCollection(pk);

        assertTrue(fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_sequential_deletes_never_move_a_survivor() throws Exception {
        final var fileSystem = fs;
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var pks = new ArrayList<PkIndexEntry>();
        for (var id : List.of("1", "2", "3")) {
            final var e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
            e.set_id(id);
            pks.add(fileSystem.insertIntoCollection(e));
        }

        fileSystem.deleteFromCollection(pks.get(0));
        assertDoesNotThrow(() -> fileSystem.deleteFromCollection(pks.get(1)));

        final var survivor = fileSystem.getById(pks.get(2));
        assertEquals("test", survivor.getData().get("name").asJsonString().getValue());
    }

    @Test
    public void test_single_update_multi_page_preserves_other_positions() throws Exception {
        FileSystem fs = FileSystemTestBase.freshFs();
        final var idxA = FileSystemTestBase.insertOnPage(fs, "a", 0);
        FileSystemTestBase.insertOnPage(fs, "b", 1);

        fs.updateFromCollection(FileSystemTestBase.updateEntry(idxA, "a", "updated-longer-value-for-a").toDbEntry(),
                idxA);

        assertEquals("short", FileSystemTestBase.readValueFromDisk(fs, "b"));
        assertEquals("updated-longer-value-for-a", FileSystemTestBase.readValueFromDisk(fs, "a"));
    }

    @Test
    public void test_single_update_same_page_moves_no_other_record() throws Exception {
        FileSystem fs = FileSystemTestBase.freshFs();
        final var idxA = FileSystemTestBase.insertOnPage(fs, "a", 0);
        final var idxB = FileSystemTestBase.insertOnPage(fs, "b", 0);
        final long bPosBefore = idxB.getPosition();
        assertTrue(bPosBefore > 0, "second same-page entry should start past position 0");

        fs.updateFromCollection(FileSystemTestBase.updateEntry(idxA, "a", "updated-longer-value-for-a").toDbEntry(),
                idxA);

        final var disk = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        final var bDisk = disk.stream().filter(p -> p.getValue().equals("b")).findFirst().orElseThrow();
        assertEquals(bPosBefore, bDisk.getPosition(), "'a' is appended and blanked, so 'b' never moves");
        assertEquals("short", fs.getById(bDisk).getData().get("v").asJsonString().getValue());
        assertEquals("updated-longer-value-for-a", FileSystemTestBase.readValueFromDisk(fs, "a"));
    }

    @Test
    public void test_delete_same_page_preserves_survivor_positions() throws Exception {
        FileSystem fs = FileSystemTestBase.freshFs();
        FileSystemTestBase.insertOnPage(fs, "a", 0);
        final var idxB = FileSystemTestBase.insertOnPage(fs, "b", 0);
        FileSystemTestBase.insertOnPage(fs, "c", 0);

        fs.deleteFromCollection(idxB);

        assertEquals("short", FileSystemTestBase.readValueFromDisk(fs, "a"));
        assertEquals("short", FileSystemTestBase.readValueFromDisk(fs, "c"));
        final var disk = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(disk.stream().noneMatch(p -> p.getValue().equals("b")), "'b' must be removed from the index");
    }

    @Test
    public void test_compactTombstones_dedups_and_drops_old() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "keep", 1000L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "keep", 2000L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "old", 100L);
        fs.tombstones().compact(TestGlobals.DB, TestGlobals.COLL, 500L);
        final var tombstones = fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, tombstones.size());
        assertEquals(2000L, tombstones.get("keep"));
        assertNull(tombstones.get("old"));
    }

    @Test
    public void test_compactTombstones_missing_file_is_noop() throws Exception {
        fs.tombstones().compact(TestGlobals.DB, "noSuchColl", 0L);
        assertTrue(fs.tombstones().read(TestGlobals.DB, "noSuchColl").isEmpty());
    }

    @Test
    public void test_a_self_heal_does_not_erase_a_concurrent_append() throws Exception {
        final var fileSystem = fs;
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
        final var fileSystem = fs;
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
        final var fileSystem = fs;
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

        assertThrows(IOException.class, () -> fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL));

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

        assertThrows(IOException.class, () -> fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL),
                "a file in the pre-change grammar is unparseable, not half-readable");
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
        final var fileSystem = fs;
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
        final var fileSystem = fs;
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

    private static DbEntry document(String id) {
        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id(id);
        entry.setData(new JsonObject());
        entry.setPage(0L);
        return entry;
    }

    @Test
    public void test_update_after_pk_index_corruption_refuses_to_rewrite() throws IOException {

        final var good1Index = fs.insertIntoCollection(document("good1"));
        fs.insertIntoCollection(document("good2"));

        Files.writeString(pkIndexFile().toPath(), "this is not a valid pk index line\nnor is this",
                StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        final var corruptedBytes = Files.readAllBytes(pkIndexFile().toPath());

        assertThrows(IOException.class, () -> fs.deleteFromCollection(good1Index));

        final var afterBytes = Files.readAllBytes(pkIndexFile().toPath());
        assertArrayEquals(corruptedBytes, afterBytes,
                "a pk index write that could not parse a single line must leave the file untouched");
    }

    @Test
    public void test_update_with_one_malformed_and_one_valid_line_still_self_heals() throws IOException {

        fs.insertIntoCollection(document("good1"));
        Files.writeString(pkIndexFile().toPath(), "\nthis is not a valid pk index line\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        final var good2Index = fs.insertIntoCollection(document("good2"));

        fs.deleteFromCollection(good2Index);

        final var diskLines = Files.readAllLines(pkIndexFile().toPath());
        assertTrue(diskLines.stream().noneMatch(l -> l.contains("not a valid")),
                "the malformed pk index line should have been removed");

        final var remaining = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, remaining.size());
        assertEquals("good1", remaining.getFirst().getValue());
    }
}
