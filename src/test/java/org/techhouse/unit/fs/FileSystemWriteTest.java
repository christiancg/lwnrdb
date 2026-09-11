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
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.DirectoryNotFoundException;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemWriteTest {
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

    // Create and initialize database directory structure with proper permissions
    @Test
    public void test_create_base_db_path_success() {
        FileSystem fs = new FileSystem();
        fs.createBaseDbPath();

        File dbDir = new File(TestGlobals.PATH);
        assertTrue(dbDir.exists());
        assertTrue(dbDir.isDirectory());
        assertTrue(dbDir.canRead());
        assertTrue(dbDir.canWrite());
    }

    // Handle non-existent directories and files gracefully
    @Test
    public void test_create_base_db_path_invalid_path() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", "/invalid/path/that/cannot/be/created");

        assertThrows(DirectoryNotFoundException.class, fs::createBaseDbPath);
    }

    // Creates admin database folder successfully when it doesn't exist
    @Test
    public void test_creates_admin_database_successfully() throws IOException {
        FileSystem fileSystem = new FileSystem();
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();

        File adminDbFolder = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + Globals.ADMIN_DB_NAME);
        assertTrue(adminDbFolder.exists());

        File databasesCollection = new File(
                adminDbFolder.getPath() + Globals.FILE_SEPARATOR + Globals.ADMIN_DATABASES_COLLECTION_NAME);
        assertTrue(databasesCollection.exists());

        File collectionsCollection = new File(
                adminDbFolder.getPath() + Globals.FILE_SEPARATOR + Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        assertTrue(collectionsCollection.exists());
    }

    // Creates new database folder when it doesn't exist and returns true
    @Test
    public void test_creates_new_db_folder_successfully() throws NoSuchFieldException, IllegalAccessException {
        String testDbPath = System.getProperty("java.io.tmpdir");
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, testDbPath);

        boolean result = fileSystem.createDatabaseFolder(TestGlobals.DB);

        assertTrue(result);
        File dbFolder = new File(testDbPath + Globals.FILE_SEPARATOR + TestGlobals.DB);
        assertTrue(dbFolder.exists());
        assertTrue(dbFolder.delete());
    }

    // Successfully inserts multiple DbEntry objects into collection file
    @Test
    public void test_bulk_insert_multiple_entries_success()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        List<DbEntry> entries = new ArrayList<>();
        JsonObject data1 = new JsonObject();
        data1.addProperty("field1", "value1");
        DbEntry entry1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data1);

        JsonObject data2 = new JsonObject();
        data2.addProperty("field2", "value2");
        DbEntry entry2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data2);

        entries.add(entry1);
        entries.add(entry2);

        List<IndexedDbEntry> result = fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(TestGlobals.DB, result.getFirst().getDatabaseName());
        assertEquals(TestGlobals.COLL, result.getFirst().getCollectionName());
        assertNotNull(result.getFirst().get_id());
        assertNotNull(result.getFirst().getIndex());
    }

    // bulkInsert with entries on different pages writes each entry to its own page only
    @Test
    public void test_bulk_insert_groups_entries_by_page()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        List<DbEntry> entries = new ArrayList<>();
        JsonObject d1 = new JsonObject();
        d1.addProperty("f", "p0");
        DbEntry e1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d1);
        e1.setPage(0);
        e1.set_id("p0-1");

        JsonObject d2 = new JsonObject();
        d2.addProperty("f", "p1");
        DbEntry e2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d2);
        e2.setPage(1);
        e2.set_id("p1-1");

        entries.add(e1);
        entries.add(e2);

        List<IndexedDbEntry> result = fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);
        assertEquals(2, result.size());

        final var page0 = fs.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0);
        final var page1 = fs.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 1);
        assertEquals(1, page0.size(), "Page 0 should contain only its grouped entry");
        assertEquals(1, page1.size(), "Page 1 should contain only its grouped entry");
        assertTrue(page0.containsKey("p0-1"));
        assertTrue(page1.containsKey("p1-1"));
    }

    // Handle empty list of entries
    @Test
    public void test_bulk_insert_empty_list() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);

        List<DbEntry> entries = new ArrayList<>();

        List<IndexedDbEntry> result = fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // Successfully updates multiple entries in collection and returns updated IndexedDbEntry list
    @Test
    public void test_bulk_update_multiple_entries_success()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

        final var data1 = new JsonObject();
        data1.addProperty("_id", "1");
        data1.addProperty("field", "value");
        final var data2 = new JsonObject();
        data1.addProperty("_id", "2");
        data2.addProperty("field", "value");

        final var newEntries = List.of(DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data1),
                DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data2));
        List<IndexedDbEntry> entries = fileSystem.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL,
                newEntries);

        final var first = entries.get(0);
        first.getData().get("field").asJsonString().setValue("changed");
        final var second = entries.get(1);
        second.getData().get("field").asJsonString().setValue("changed");

        final var result = fileSystem.bulkUpdateFromCollection(TestGlobals.DB, TestGlobals.COLL, entries).updated();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> e.getData().get("field").asJsonString().getValue().equals("changed")));
        assertEquals(TestGlobals.DB, result.getFirst().getDatabaseName());
        assertEquals(TestGlobals.COLL, result.getFirst().getCollectionName());
    }

    // Empty entries list handling
    @Test
    public void test_bulk_update_empty_entries_list() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

        List<IndexedDbEntry> entries = new ArrayList<>();

        final var result = fileSystem.bulkUpdateFromCollection(TestGlobals.DB, TestGlobals.COLL, entries);

        assertNotNull(result);
        assertTrue(result.updated().isEmpty());
        assertTrue(result.compactions().isEmpty());
    }

    // Updating multiple entries that share a page in one batch must NOT corrupt the page: each
    // document still reads back with its updated content and correct position afterwards.
    @Test
    public void test_bulk_update_multiple_same_page_entries_no_corruption() throws Exception {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var toInsert = new ArrayList<DbEntry>();
        for (var id : List.of("1", "2", "3")) {
            final var data = new JsonObject();
            data.addProperty("_id", id);
            data.addProperty("field", "orig-" + id);
            toInsert.add(DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data));
        }
        final var inserted = fileSystem.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, toInsert);
        // All three live on page 0; change each value to a DIFFERENT-length string to force shifts.
        for (var ie : inserted) {
            ie.getData().get("field").asJsonString().setValue("updated-value-for-" + ie.get_id() + "-longer");
        }

        final var result = fileSystem.bulkUpdateFromCollection(TestGlobals.DB, TestGlobals.COLL, inserted);

        // Every updated entry must read back from disk with its new content at its reported position.
        for (var ie : result.updated()) {
            final var read = fileSystem.getById(ie.getIndex());
            assertEquals("updated-value-for-" + ie.get_id() + "-longer",
                    read.getData().get("field").asJsonString().getValue(),
                    "doc " + ie.get_id() + " must read back intact after a multi-same-page bulk update");
        }
    }

    // Correctly updates file length after modification
    @Test
    public void test_update_file_length() throws NoSuchFieldException, IllegalAccessException, IOException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var file = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + "0.dat");
        final var jsonObject = new JsonObject();
        jsonObject.addProperty("name", "test");
        final var dbEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, jsonObject);
        final var inserted = fileSystem.insertIntoCollection(dbEntry);
        assertTrue(file.length() > 0);
        final var originalFileLength = file.length();
        jsonObject.addProperty("name", "updated");
        final var dbEntryUpdated = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, jsonObject);
        fileSystem.updateFromCollection(dbEntryUpdated, inserted);
        assertTrue(file.length() > originalFileLength);
    }

    // Concurrent inserts and reads on the same page never corrupt the file: every insert lands and
    // every concurrent read returns only complete, parseable entries (exercises the per-file locks).
    @Test
    public void test_concurrent_inserts_and_reads_keep_page_coherent() throws Exception {
        final var fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final int writers = 8;
        final int perWriter = 25;
        final var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        final var threads = new ArrayList<Thread>();
        for (int w = 0; w < writers; w++) {
            final int base = w * perWriter;
            threads.add(new Thread(() -> {
                for (int i = 0; i < perWriter; i++) {
                    try {
                        final var obj = new JsonObject();
                        obj.addProperty("_id", "c-" + (base + i));
                        obj.addProperty("v", base + i);
                        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
                        entry.setPage(0);
                        fileSystem.insertIntoCollection(entry);
                        fileSystem.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0);
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }
            }));
        }
        for (var t : threads)
            t.start();
        for (var t : threads)
            t.join(15000);
        assertTrue(errors.isEmpty(), "no errors expected under concurrent insert/read: " + errors);
        final var page = fileSystem.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0);
        assertEquals(writers * perWriter, page.size());
    }

    // ── PK-index per-page reindex correctness (regression for bulk/multi-page update corruption) ──

    // Reads the entry back the way a cache-disabled / post-restart read does: from the persisted PK
    // index file, not from any in-memory copy. Also asserts the stored position is not corrupt.

    @Test
    public void test_bulk_update_multi_page_reads_back_intact() throws Exception {
        FileSystem fs = FileSystemPages.freshFs();
        final var idxA = FileSystemPages.insertOnPage(fs, "a", 0);
        final var idxB = FileSystemPages.insertOnPage(fs, "b", 1);
        final var idxC = FileSystemPages.insertOnPage(fs, "c", 2);
        final var updates = List.of(FileSystemPages.updateEntry(idxA, "a", "updated-longer-value-for-a"),
                FileSystemPages.updateEntry(idxB, "b", "updated-longer-value-for-b"),
                FileSystemPages.updateEntry(idxC, "c", "updated-longer-value-for-c"));

        fs.bulkUpdateFromCollection(TestGlobals.DB, TestGlobals.COLL, updates);

        assertEquals("updated-longer-value-for-a", FileSystemPages.readValueFromDisk(fs, "a"));
        assertEquals("updated-longer-value-for-b", FileSystemPages.readValueFromDisk(fs, "b"));
        assertEquals("updated-longer-value-for-c", FileSystemPages.readValueFromDisk(fs, "c"));
    }

    @Test
    public void test_bulk_update_same_page_reads_back_intact() throws Exception {
        FileSystem fs = FileSystemPages.freshFs();
        final var idxA = FileSystemPages.insertOnPage(fs, "a", 0);
        final var idxB = FileSystemPages.insertOnPage(fs, "b", 0);
        final var idxC = FileSystemPages.insertOnPage(fs, "c", 0);
        final var updates = List.of(FileSystemPages.updateEntry(idxA, "a", "updated-longer-value-for-a"),
                FileSystemPages.updateEntry(idxB, "b", "updated-longer-value-for-b"),
                FileSystemPages.updateEntry(idxC, "c", "updated-longer-value-for-c"));

        fs.bulkUpdateFromCollection(TestGlobals.DB, TestGlobals.COLL, updates);

        assertEquals("updated-longer-value-for-a", FileSystemPages.readValueFromDisk(fs, "a"));
        assertEquals("updated-longer-value-for-b", FileSystemPages.readValueFromDisk(fs, "b"));
        assertEquals("updated-longer-value-for-c", FileSystemPages.readValueFromDisk(fs, "c"));
    }
}
