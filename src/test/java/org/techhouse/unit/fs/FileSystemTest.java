package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.DirectoryNotFoundException;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
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
        TestUtils.setPrivateField(fileSystem, "dbPath", testDbPath);

        boolean result = fileSystem.createDatabaseFolder(TestGlobals.DB);

        assertTrue(result);
        File dbFolder = new File(testDbPath + Globals.FILE_SEPARATOR + TestGlobals.DB);
        assertTrue(dbFolder.exists());
        assertTrue(dbFolder.delete());
    }

    // Successfully delete database folder and all its contents when database exists
    @Test
    public void test_delete_existing_database() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        boolean result = fileSystem.deleteDatabase(TestGlobals.DB);

        assertTrue(result);
    }

    // Return false when database folder does not exist
    @Test
    public void test_delete_nonexistent_database() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        boolean result = fileSystem.deleteDatabase("nonExistentDb");

        assertFalse(result);
    }

    // Handle case when folder creation fails
    @Test
    public void test_handle_folder_creation_failure() throws IOException {
        FileSystem fileSystem = new FileSystem();

        File mockFile = mock(File.class);
        File mockFolder = mock(File.class);

        when(mockFile.getParent()).thenReturn("/test/path");
        when(mockFolder.exists()).thenReturn(false);
        when(mockFolder.mkdir()).thenReturn(false);

        boolean result = fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        assertFalse(result);
        verify(mockFile, never()).createNewFile();
    }

    // Successfully delete all files in existing collection folder and the folder itself
    @Test
    public void test_delete_collection_files_success() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        boolean result = fileSystem.deleteCollectionFiles(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(result);
    }

    // Return false when collection folder does not exist
    @Test
    public void test_delete_collection_files_nonexistent_folder() {
        FileSystem fileSystem = new FileSystem();
        String dbName = "nonExistentDb";
        String collectionName = "nonExistentCollection";

        boolean result = fileSystem.deleteCollectionFiles(dbName, collectionName);

        assertFalse(result);
    }

    // Handle case when file does not exist
    @Test
    public void test_get_by_id_throws_when_file_not_exists() {
        // Arrange
        FileSystem fileSystem = new FileSystem();
        String dbName = "nonExistentDb";
        String collectionName = "nonExistentCollection";
        String id = "123";
        PkIndexEntry pkIndexEntry = new PkIndexEntry(dbName, collectionName, id, 0L, 100L, 0);

        // Act & Assert
        assertThrows(FileNotFoundException.class, () -> fileSystem.getById(pkIndexEntry));
    }

    // Successfully retrieves DbEntry when valid PkIndexEntry is provided
    @Test
    public void test_get_by_id_returns_valid_db_entry() throws Exception {
        // Arrange
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        String id = "123";

        JsonObject expectedJson = new JsonObject();
        expectedJson.addProperty("name", "test");
        expectedJson.addProperty(Globals.PK_FIELD, id);
        DbEntry dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.setData(expectedJson);
        dbEntry.set_id(id);
        PkIndexEntry indexEntry = fileSystem.insertIntoCollection(dbEntry);

        // Act
        DbEntry result = fileSystem.getById(indexEntry);

        // Assert
        assertNotNull(result);
        assertEquals(TestGlobals.DB, result.getDatabaseName());
        assertEquals(TestGlobals.COLL, result.getCollectionName());
        assertEquals(id, result.get_id());
        assertEquals("test", result.getData().get("name").asJsonString().getValue());
    }

    // Successfully inserts multiple DbEntry objects into collection file
    @Test
    public void test_bulk_insert_multiple_entries_success()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);
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
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);
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
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);

        List<DbEntry> entries = new ArrayList<>();

        List<IndexedDbEntry> result = fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // Successfully inserts new entry into collection file and creates index entry
    @Test
    public void test_insert_entry_creates_index() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);

        JsonObject data = new JsonObject();
        data.addProperty("name", "test");

        DbEntry entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);

        PkIndexEntry result = fs.insertIntoCollection(entry);

        assertNotNull(result);
        assertEquals(entry.get_id(), result.getValue());
        assertEquals(TestGlobals.DB, result.getDatabaseName());
        assertEquals(TestGlobals.COLL, result.getCollectionName());
        assertTrue(result.getPosition() >= 0);
        assertTrue(result.getLength() > 0);

        final var filePath = TestUtils.getPrivateField(fs, "dbPath", String.class);
        File collFile = new File(filePath + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + "0.dat");
        assertTrue(collFile.exists());
    }

    // Successfully deletes entry by shifting remaining entries and updating file length
    @Test
    public void test_delete_entry_shifts_remaining_entries()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Setup
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        final var file = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + "0.dat");

        JsonObject data = new JsonObject();
        data.addProperty("name", "test");

        DbEntry entry1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry1.set_id("1");
        DbEntry entry2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry2.set_id("2");

        fileSystem.insertIntoCollection(entry1);
        fileSystem.insertIntoCollection(entry2);

        PkIndexEntry entryToDelete = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 25, 0);

        // Execute
        fileSystem.deleteFromCollection(entryToDelete);

        // Verify
        try (RandomAccessFile reader = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            byte[] content = new byte[(int) reader.length()];
            reader.readFully(content);
            assertEquals(entry2.toFileEntry(),
                    new String(content, StandardCharsets.UTF_8).replace(Globals.NEWLINE, ""));
        }

        assertTrue(file.delete());
    }

    // Successfully updates multiple entries in collection and returns updated IndexedDbEntry list
    @Test
    public void test_bulk_update_multiple_entries_success()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

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
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

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
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
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
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
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
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
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

    // Successfully writes index entries to file for each type in the map
    @Test
    public void test_writes_index_entries_to_file() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "testField";
        Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap = new HashMap<>();
        List<FieldIndexEntry<?>> stringEntries = List.of(
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value1", Set.of("id1", "id2")),
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value2", Set.of("id3")));
        indexEntryMap.put(String.class, stringEntries);
        fileSystem.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, indexEntryMap);
        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-String.idx");
        String fileContent = Files.readString(indexFile.toPath());
        assertTrue(fileContent.contains("value1" + Globals.ID_SEPARATOR + "id2" + Globals.ID_SEPARATOR + "id1")
                || fileContent.contains("value1" + Globals.ID_SEPARATOR + "id1" + Globals.ID_SEPARATOR + "id2"));
        assertTrue(fileContent.contains("value2" + Globals.ID_SEPARATOR + "id3"));
        assertTrue(fileContent.endsWith(Globals.NEWLINE));
    }

    // Empty index entry map
    @Test
    public void test_empty_index_entry_map() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "testField";
        Map<Class<?>, List<FieldIndexEntry<?>>> emptyMap = new HashMap<>();
        fileSystem.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, emptyMap);
        final var index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName,
                String.class);
        assertNull(index);
    }

    // Successfully update index file when both insertedEntry and removedEntry are provided
    @Test
    public void test_update_index_files_with_insert_and_remove()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "testField";

        Set<String> ids1 = new HashSet<>(Arrays.asList("id1", "id2"));
        Set<String> ids2 = new HashSet<>(Arrays.asList("id3", "id4"));

        FieldIndexEntry<String> insertEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value1", ids1);
        FieldIndexEntry<String> removeEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value2", ids2);

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, insertEntry, removeEntry);
        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-String.idx");
        String fileContent = Files.readString(indexFile.toPath());

        assertTrue(fileContent.contains("value1" + Globals.ID_SEPARATOR + "id1" + Globals.ID_SEPARATOR + "id2")
                || fileContent.contains("value1" + Globals.ID_SEPARATOR + "id2" + Globals.ID_SEPARATOR + "id1"));
        assertFalse(fileContent.contains("value2" + Globals.ID_SEPARATOR + "id3" + Globals.ID_SEPARATOR + "id4")
                || fileContent.contains("value2" + Globals.ID_SEPARATOR + "id4" + Globals.ID_SEPARATOR + "id3"));
    }

    // Handle case when removedEntry has empty ids set
    @Test
    public void test_update_index_files_with_empty_ids_remove()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        String fieldName = "testField";

        Set<String> emptyIds = new HashSet<>();
        FieldIndexEntry<Integer> removeEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 123, emptyIds);

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, null, removeEntry);

        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-Number.idx");
        String fileContent = Files.readString(indexFile.toPath());

        assertFalse(fileContent.contains("123" + Globals.INDEX_ENTRY_SEPARATOR));
    }

    @Test
    public void test_update_index_files_with_pipe_in_field_value()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "testField";

        FieldIndexEntry<String> entry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "foo|bar",
                Set.of("id1"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, entry, null);

        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-String.idx");
        String fileContent = Files.readString(indexFile.toPath());
        assertTrue(fileContent.contains("foo|bar" + Globals.ID_SEPARATOR + "id1"));
    }

    @Test
    public void test_search_does_not_match_prefix_when_value_contains_pipe()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "testField";

        // Write two entries: "foo" and "foo|bar" — the search for "foo" must not hit "foo|bar"
        FieldIndexEntry<String> fooBar = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "foo|bar",
                Set.of("id2"));
        FieldIndexEntry<String> foo = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "foo", Set.of("id1"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, fooBar, null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, foo, null);

        // Remove "foo" — should only remove the exact "foo" entry, leaving "foo|bar" intact
        FieldIndexEntry<String> fooEmpty = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "foo", Set.of());
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, null, fooEmpty);

        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-String.idx");
        String fileContent = Files.readString(indexFile.toPath());

        assertFalse(fileContent.contains("foo" + Globals.ID_SEPARATOR + "id1"),
                "exact 'foo' entry should have been removed");
        assertTrue(fileContent.contains("foo|bar" + Globals.ID_SEPARATOR + "id2"),
                "'foo|bar' entry must not be affected");
    }

    @Test
    public void test_field_index_round_trip_with_pipe_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "testField";

        FieldIndexEntry<String> entry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "someValue",
                Set.of("doc|id|one", "doc|id|two"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, entry, null);

        List<FieldIndexEntry<String>> index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL,
                fieldName, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals("someValue", index.getFirst().getValue());
        assertTrue(index.getFirst().getIds().contains("doc|id|one"));
        assertTrue(index.getFirst().getIds().contains("doc|id|two"));
    }

    @Test
    public void test_field_index_round_trip_with_semicolon_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "testField";

        FieldIndexEntry<String> entry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "someValue",
                Set.of("doc;id;one", "doc;id;two"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, entry, null);

        List<FieldIndexEntry<String>> index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL,
                fieldName, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals("someValue", index.getFirst().getValue());
        assertTrue(index.getFirst().getIds().contains("doc;id;one"));
        assertTrue(index.getFirst().getIds().contains("doc;id;two"));
    }

    @Test
    public void test_field_index_round_trip_with_pipe_and_semicolon_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "testField";

        Set<String> ids = Set.of("id|pipe", "id;semi", "id|and;both");
        FieldIndexEntry<String> entry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "someValue", ids);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, entry, null);

        List<FieldIndexEntry<String>> index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL,
                fieldName, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertEquals(ids, index.getFirst().getIds());
    }

    @Test
    public void test_field_index_update_id_with_special_chars()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "testField";

        // Insert initial entry pointing to a document with special-char ID
        FieldIndexEntry<String> initial = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "aValue",
                Set.of("doc|one", "doc;two"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, initial, null);

        // Remove one ID, keep the other
        FieldIndexEntry<String> removed = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "aValue",
                Set.of("doc;two"));
        FieldIndexEntry<String> updated = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "aValue",
                Set.of("doc|one"));
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, updated, removed);

        List<FieldIndexEntry<String>> index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL,
                fieldName, String.class);
        assertNotNull(index);
        assertEquals(1, index.size());
        assertTrue(index.getFirst().getIds().contains("doc|one"));
        assertFalse(index.getFirst().getIds().contains("doc;two"));
    }

    @Test
    public void test_pk_index_round_trip_with_pipe_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        DbEntry entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("my|custom|id");
        entry.setData(new JsonObject());
        entry.setPage(0L);

        PkIndexEntry saved = fileSystem.insertIntoCollection(entry);
        assertEquals("my|custom|id", saved.getValue());

        List<PkIndexEntry> index = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, index.size());
        assertEquals("my|custom|id", index.getFirst().getValue());
    }

    @Test
    public void test_pk_index_round_trip_with_semicolon_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        DbEntry entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("my;custom;id");
        entry.setData(new JsonObject());
        entry.setPage(0L);

        PkIndexEntry saved = fileSystem.insertIntoCollection(entry);
        assertEquals("my;custom;id", saved.getValue());

        List<PkIndexEntry> index = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, index.size());
        assertEquals("my;custom;id", index.getFirst().getValue());
    }

    @Test
    public void test_pk_index_round_trip_with_pipe_and_semicolon_in_id()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        DbEntry entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("my|and;special|id");
        entry.setData(new JsonObject());
        entry.setPage(0L);

        PkIndexEntry saved = fileSystem.insertIntoCollection(entry);
        assertEquals("my|and;special|id", saved.getValue());

        List<PkIndexEntry> index = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, index.size());
        assertEquals("my|and;special|id", index.getFirst().getValue());
    }

    // Successfully delete all index files for a given field in an existing collection
    @Test
    public void test_delete_index_files_success() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);

        String fieldName = "testField";

        File mockCollFolder = new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);

        File indexFile1 = new File(mockCollFolder, "1" + Globals.INDEX_FILE_NAME_SEPARATOR + fieldName
                + Globals.INDEX_FILE_NAME_SEPARATOR + "idx" + Globals.INDEX_FILE_EXTENSION);
        File indexFile2 = new File(mockCollFolder, "2" + Globals.INDEX_FILE_NAME_SEPARATOR + fieldName
                + Globals.INDEX_FILE_NAME_SEPARATOR + "idx" + Globals.INDEX_FILE_EXTENSION);

        assertTrue(indexFile1.createNewFile());
        assertTrue(indexFile2.createNewFile());

        boolean result = fs.dropIndex(TestGlobals.DB, TestGlobals.COLL, fieldName);

        assertTrue(result);
        assertFalse(indexFile1.exists());
        assertFalse(indexFile2.exists());
    }

    // Return false when collection folder does not exist
    @Test
    public void test_drop_index_nonexistent_collection() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);

        String dbName = "nonExistentDb";
        String collName = "nonExistentColl";
        String fieldName = "testField";

        boolean result = fs.dropIndex(dbName, collName, fieldName);

        assertFalse(result);
    }

    // Successfully reads and maps index files for a given field in a collection
    @Test
    public void test_read_and_map_index_files_success() throws NoSuchFieldException, IllegalAccessException {
        // Arrange
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "age";

        Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap = new HashMap<>();
        List<FieldIndexEntry<?>> stringEntries = List.of(
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value1", Set.of("id1", "id2")),
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value2", Set.of("id3")));
        indexEntryMap.put(String.class, stringEntries);
        fileSystem.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, indexEntryMap);

        // Act
        ConcurrentMap<String, List<FieldIndexEntry<?>>> result = fileSystem.readAllWholeFieldIndexFiles(TestGlobals.DB,
                TestGlobals.COLL, fieldName);

        assertNotNull(result);
        assertNotNull(result.get("String"));
        assertEquals(2, result.get("String").size());
    }

    // Returns null when collection folder does not exist
    @Test
    public void test_returns_null_when_collection_folder_missing() throws NoSuchFieldException, IllegalAccessException {
        // Arrange
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "age";

        // Act
        ConcurrentMap<String, List<FieldIndexEntry<?>>> result = fileSystem.readAllWholeFieldIndexFiles(TestGlobals.DB,
                "nonexistentCollection", fieldName);

        // Assert
        assertNull(result);
    }

    // Read and parse index file entries for Number type fields
    @Test
    public void test_read_number_type_index_entries() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);

        String fieldName = "testField";
        Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap = new HashMap<>();
        List<FieldIndexEntry<?>> stringEntries = List.of(
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value1", Set.of("id1", "id2")),
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value2", Set.of("id3")));
        indexEntryMap.put(String.class, stringEntries);
        fs.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, indexEntryMap);

        List<FieldIndexEntry<String>> entries = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName,
                String.class);

        assertNotNull(entries);
        assertEquals(2, entries.size());
        assertTrue(entries.getFirst().getIds().contains("id1") && entries.getFirst().getIds().contains("id2"));
        assertTrue(entries.get(1).getIds().contains("id3"));
    }

    // Handle empty index files
    @Test
    public void test_empty_index_file_returns_empty_list()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);

        List<FieldIndexEntry<String>> entries = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL,
                "emptyField", String.class);

        assertNull(entries);
    }

    // Returns sorted list of PkIndexEntry objects when index file exists and contains entries
    @Test
    public void test_read_index_file_returns_sorted_entries()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        File mockIndexFile = mock(File.class);
        when(mockIndexFile.exists()).thenReturn(true);
        Path path = Path.of(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-_id-String.idx");
        when(mockIndexFile.toPath()).thenReturn(path);

        List<String> fileLines = Arrays.asList(
                "value3" + Globals.INDEX_ENTRY_SEPARATOR + "300" + Globals.INDEX_ENTRY_SEPARATOR + "100"
                        + Globals.INDEX_ENTRY_SEPARATOR + "0",
                "value1" + Globals.INDEX_ENTRY_SEPARATOR + "100" + Globals.INDEX_ENTRY_SEPARATOR + "100"
                        + Globals.INDEX_ENTRY_SEPARATOR + "0",
                "value2" + Globals.INDEX_ENTRY_SEPARATOR + "200" + Globals.INDEX_ENTRY_SEPARATOR + "100"
                        + Globals.INDEX_ENTRY_SEPARATOR + "0");
        Files.write(path, fileLines);

        // Act
        List<PkIndexEntry> result = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        // Assert
        assertEquals(3, result.size());
        assertEquals("value1", result.get(0).getValue());
        assertEquals("value2", result.get(1).getValue());
        assertEquals("value3", result.get(2).getValue());
    }

    // Handle empty index file
    @Test
    public void test_empty_index_file_returns_empty_list_2()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        File mockIndexFile = mock(File.class);
        when(mockIndexFile.exists()).thenReturn(false);

        // Act
        List<PkIndexEntry> result = fileSystem.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);

        // Assert
        assertTrue(result.isEmpty());
    }

    // streamPages yields one map per page file
    @Test
    public void test_stream_pages_yields_one_map_per_page()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        DbEntry e1 = new DbEntry();
        e1.set_id("1");
        e1.setDatabaseName(TestGlobals.DB);
        e1.setCollectionName(TestGlobals.COLL);
        e1.setData(new JsonObject());
        e1.setPage(0);
        fileSystem.insertIntoCollection(e1);

        DbEntry e2 = new DbEntry();
        e2.set_id("2");
        e2.setDatabaseName(TestGlobals.DB);
        e2.setCollectionName(TestGlobals.COLL);
        e2.setData(new JsonObject());
        e2.setPage(1);
        fileSystem.insertIntoCollection(e2);

        final var pages = fileSystem.streamPages(TestGlobals.DB, TestGlobals.COLL).toList();
        assertEquals(2, pages.size());
        final var allIds = new java.util.HashSet<String>();
        for (var p : pages)
            allIds.addAll(p.keySet());
        assertTrue(allIds.contains("1"));
        assertTrue(allIds.contains("2"));
    }

    // streamPages on a non-existent collection folder returns an empty stream
    @Test
    public void test_stream_pages_missing_folder_empty()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        assertEquals(0L, fileSystem.streamPages("noSuchDbNameForStreamTest", "noSuchCollName").count());
    }

    // findPkIndexEntry returns matching entry from configured path
    @Test
    public void test_find_pk_index_entry() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        DbEntry entry = new DbEntry();
        entry.set_id("findMe");
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.setData(new JsonObject());
        fileSystem.insertIntoCollection(entry);

        final var found = fileSystem.findPkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "findMe");
        assertNotNull(found);
        assertEquals("findMe", found.getValue());

        assertNull(fileSystem.findPkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "nope"));
    }

    @Test
    public void test_readWholeCollectionPage_skips_malformed_lines() throws Exception {
        final var fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);

        // Insert one valid entry so the page file exists with a known good line.
        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("good");
        entry.setData(new JsonObject());
        entry.setPage(0L);
        fs.insertIntoCollection(entry);

        // Append a torn/garbage line, simulating a crash mid-write.
        final var pageFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB
                + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL
                + Globals.FILE_PAGE_SEPARATOR + 0 + Globals.DB_FILE_EXTENSION);
        Files.writeString(pageFile.toPath(), "\nthis-is-not-json{partial", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        // The malformed line is skipped in-memory; the file is left untouched
        // so the .idx file's byte offsets remain valid (rewriting the .dat
        // here would require coordinated .idx rewriting — a real compaction).
        final var firstRead = fs.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0L);
        assertEquals(1, firstRead.size());
        assertTrue(firstRead.containsKey("good"));

        final var diskLines = Files.readAllLines(pageFile.toPath());
        assertTrue(diskLines.stream().anyMatch(l -> l.contains("partial")),
                ".dat is intentionally left as-is to preserve .idx offsets");
    }

    @Test
    public void test_readWholePkIndexFile_drops_and_rewrites_malformed_lines() throws Exception {
        final var fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);

        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("good");
        entry.setData(new JsonObject());
        entry.setPage(0L);
        fs.insertIntoCollection(entry);

        // Compose the PK index file path manually and append a garbage line.
        final var indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB
                + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL
                + Globals.INDEX_FILE_NAME_SEPARATOR + Globals.PK_FIELD + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.PK_FIELD_TYPE + Globals.INDEX_FILE_EXTENSION);
        Files.writeString(indexFile.toPath(), "\nthis is not a valid pk index line", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        final var firstRead = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, firstRead.size());
        assertEquals("good", firstRead.getFirst().getValue());

        final var diskLines = Files.readAllLines(indexFile.toPath());
        assertTrue(diskLines.stream().noneMatch(l -> l.contains("not a valid")),
                "the malformed PK index line should have been removed");

        final var secondRead = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, secondRead.size());
    }

    // getByIndexEntries returns an empty list for null or empty input
    @Test
    public void test_get_by_index_entries_empty_input()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);

        assertTrue(fs.getByIndexEntries(null).isEmpty());
        assertTrue(fs.getByIndexEntries(new ArrayList<>()).isEmpty());
    }

    // getByIndexEntries reads only the requested entries across multiple pages
    @Test
    public void test_get_by_index_entries_reads_requested_across_pages()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        final var entries = new ArrayList<DbEntry>();
        for (int i = 0; i < 3; i++) {
            JsonObject d = new JsonObject();
            d.addProperty("v", i);
            DbEntry e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d);
            e.set_id("p0-" + i);
            e.setPage(0);
            entries.add(e);
        }
        JsonObject d3 = new JsonObject();
        d3.addProperty("v", 99);
        DbEntry e3 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d3);
        e3.set_id("p1-0");
        e3.setPage(1);
        entries.add(e3);

        final var indexed = fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);
        final var byId = new HashMap<String, PkIndexEntry>();
        for (var ix : indexed) {
            byId.put(ix.get_id(), ix.getIndex());
        }

        // Request one from page 0 and one from page 1 — must get exactly those two.
        final var requested = List.of(byId.get("p0-1"), byId.get("p1-0"));
        final var result = fs.getByIndexEntries(requested);

        assertEquals(2, result.size());
        final var ids = result.stream().map(DbEntry::get_id).collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.contains("p0-1"));
        assertTrue(ids.contains("p1-0"));
        final var p01 = result.stream().filter(e -> e.get_id().equals("p0-1")).findFirst().orElseThrow();
        assertEquals(1, p01.getData().get("v").asJsonNumber().getValue().intValue());
    }

    // getByIndexEntries reads multiple entries from a single page in position order
    @Test
    public void test_get_by_index_entries_single_page_multiple()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        final var entries = new ArrayList<DbEntry>();
        for (int i = 0; i < 3; i++) {
            JsonObject d = new JsonObject();
            d.addProperty("name", "n" + i);
            DbEntry e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d);
            e.set_id("id-" + i);
            entries.add(e);
        }
        final var indexed = fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);
        final var pkEntries = indexed.stream().map(IndexedDbEntry::getIndex)
                .collect(java.util.stream.Collectors.toList());

        final var result = fs.getByIndexEntries(pkEntries);
        assertEquals(3, result.size());
        final var ids = result.stream().map(DbEntry::get_id).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("id-0", "id-1", "id-2"), ids);
    }

    // streamEntries on a missing/empty collection yields an empty stream
    @Test
    public void test_stream_entries_empty_collection()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);
        fs.createBaseDbPath();

        try (var stream = fs.streamEntries(TestGlobals.DB, "nonExistentColl")) {
            assertEquals(0, stream.count());
        }
    }

    // streamEntries yields all entries across pages
    @Test
    public void test_stream_entries_yields_all_across_pages()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);

        final var entries = new ArrayList<DbEntry>();
        JsonObject d1 = new JsonObject();
        d1.addProperty("f", "a");
        DbEntry e1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d1);
        e1.set_id("a");
        e1.setPage(0);
        JsonObject d2 = new JsonObject();
        d2.addProperty("f", "b");
        DbEntry e2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, d2);
        e2.set_id("b");
        e2.setPage(1);
        entries.add(e1);
        entries.add(e2);
        fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);

        final Set<String> ids;
        try (var stream = fs.streamEntries(TestGlobals.DB, TestGlobals.COLL)) {
            ids = stream.map(DbEntry::get_id).collect(java.util.stream.Collectors.toSet());
        }
        assertEquals(Set.of("a", "b"), ids);
    }

    // writeHashIndexFile + readWholeHashIndexFile round-trip for OBJECT and ARRAY kinds
    @Test
    public void test_hash_index_write_and_read_round_trip()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "payload";

        FieldIndexEntry<String> objEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "aaaa1111",
                new HashSet<>(Set.of("id1", "id2")));
        fileSystem.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT, List.of(objEntry));
        FieldIndexEntry<String> arrEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "bbbb2222",
                new HashSet<>(Set.of("id3")));
        fileSystem.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.ARRAY, List.of(arrEntry));

        final var objIndex = fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName,
                IndexKind.OBJECT);
        assertNotNull(objIndex);
        assertEquals(1, objIndex.size());
        assertEquals("aaaa1111", objIndex.getFirst().getValue());
        assertEquals(Set.of("id1", "id2"), objIndex.getFirst().getIds());

        final var arrIndex = fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName,
                IndexKind.ARRAY);
        assertNotNull(arrIndex);
        assertEquals(1, arrIndex.size());
        assertEquals("bbbb2222", arrIndex.getFirst().getValue());
    }

    // writeHashIndexFile with an empty list writes nothing; reading returns null
    @Test
    public void test_hash_index_write_empty_list_is_noop()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        fileSystem.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, "payload", IndexKind.OBJECT, List.of());
        assertNull(fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, "payload", IndexKind.OBJECT));
    }

    // updateHashIndexFiles inserts a new entry then removes it again
    @Test
    public void test_hash_index_update_insert_then_remove()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "payload";

        FieldIndexEntry<String> inserted = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "cccc3333",
                new HashSet<>(Set.of("id1")));
        fileSystem.updateHashIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT, inserted, null);
        var index = fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT);
        assertNotNull(index);
        assertEquals(1, index.size());

        FieldIndexEntry<String> removed = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "cccc3333",
                new HashSet<>());
        fileSystem.updateHashIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT, null, removed);
        index = fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT);
        assertTrue(index == null || index.stream().noneMatch(e -> e.getValue().equals("cccc3333")));
    }

    // dropIndex removes the per-kind hash index files alongside scalar ones
    @Test
    public void test_drop_index_removes_hash_index_files()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "payload";
        fileSystem.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT, List
                .of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "dddd4444", new HashSet<>(Set.of("a")))));
        fileSystem.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.ARRAY, List
                .of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "eeee5555", new HashSet<>(Set.of("b")))));

        assertTrue(fileSystem.dropIndex(TestGlobals.DB, TestGlobals.COLL, fieldName));
        assertNull(fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT));
        assertNull(fileSystem.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.ARRAY));
    }

    // deleteFromCollection returns the compaction (removed row's coordinates) when a survivor moves.
    @Test
    public void test_delete_returns_compaction() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
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
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id("1");
        final var pk = fileSystem.insertIntoCollection(entry);

        assertNull(fileSystem.deleteFromCollection(pk));
    }

    // updateFromCollection returns the new index entry plus the compaction for the old slot.
    @Test
    public void test_update_returns_index_entry_and_compaction()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        final var data = new JsonObject();
        data.addProperty("name", "test");
        final var entry1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry1.set_id("1");
        final var entry2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry2.set_id("2");
        final var pk1 = fileSystem.insertIntoCollection(entry1);
        fileSystem.insertIntoCollection(entry2);
        data.get("name").asJsonString().setValue("updated");
        final var updatedEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        updatedEntry.set_id("1");

        // Updating entry "1" (not the last one) relocates it to the end, so a survivor moves.
        final var result = fileSystem.updateFromCollection(updatedEntry, pk1);

        assertNotNull(result.indexEntry());
        assertEquals("1", result.indexEntry().getValue());
        assertNotNull(result.compaction());
        assertEquals(pk1.getPosition(), result.compaction().removedPosition());
        assertEquals(pk1.getLength(), result.compaction().removedLength());
    }

    // Two sequential deletes on the same page do not crash when the caller applies the returned
    // compaction to keep the surviving entries' positions consistent (the stale-position fix).
    @Test
    public void test_sequential_deletes_applying_compaction_no_crash() throws Exception {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
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
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
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
}
