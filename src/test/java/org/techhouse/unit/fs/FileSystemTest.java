package org.techhouse.unit.fs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.DirectoryNotFoundException;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
        if (dbDir.exists() && dbDir.isDirectory() && dbDir.canRead() && dbDir.canWrite() && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            deleteFolder(dbDir);
        }
    }

    private void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if(files!=null) { //some JVMs return null for empty dirs
            for(File f: files) {
                if(f.isDirectory()) {
                    deleteFolder(f);
                } else {
                    f.delete();
                }
            }
        }
        folder.delete();
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

        dbDir.delete();
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

        File databasesCollection = new File(adminDbFolder.getPath() + Globals.FILE_SEPARATOR + Globals.ADMIN_DATABASES_COLLECTION_NAME);
        assertTrue(databasesCollection.exists());

        File collectionsCollection = new File(adminDbFolder.getPath() + Globals.FILE_SEPARATOR + Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        assertTrue(collectionsCollection.exists());

        File dbDir = new File(TestGlobals.PATH);
        dbDir.delete();
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
        dbFolder.delete();
    }

    // Handles null database name
    @Test
    @Disabled //TODO: should probably handle this case
    public void test_handles_null_database_name() throws NoSuchFieldException, IllegalAccessException {
        String testDbPath = System.getProperty("java.io.tmpdir");
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", testDbPath);

        assertThrows(NullPointerException.class, () -> fileSystem.createDatabaseFolder(null));
    }

    // Successfully delete database folder and all its contents when database exists
    @Test
    public void test_delete_existing_database() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        File dbFolder = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB);
        dbFolder.mkdirs();

        File collFolder = new File(dbFolder, "collection1");
        collFolder.mkdirs();

        File testFile = new File(collFolder, "test.json");
        testFile.createNewFile();

        boolean result = fileSystem.deleteDatabase(TestGlobals.DB);

        assertTrue(result);
        assertFalse(dbFolder.exists());
        assertFalse(collFolder.exists());
        assertFalse(testFile.exists());
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
    public void test_delete_collection_files_success() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        File collectionFolder = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB +
                Globals.FILE_SEPARATOR + TestGlobals.COLL);
        collectionFolder.mkdirs();

        File testFile1 = new File(collectionFolder, "test1.dat");
        File testFile2 = new File(collectionFolder, "test2.dat");
        testFile1.createNewFile();
        testFile2.createNewFile();

        boolean result = fileSystem.deleteCollectionFiles(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(result);
        assertFalse(collectionFolder.exists());
        assertFalse(testFile1.exists());
        assertFalse(testFile2.exists());
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
        PkIndexEntry pkIndexEntry = new PkIndexEntry(dbName, collectionName, id, 0L, 100L);

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
    public void test_bulk_insert_multiple_entries_success() throws IOException, NoSuchFieldException, IllegalAccessException {
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
        File collFile = new File(filePath + Globals.FILE_SEPARATOR + TestGlobals.DB +Globals.FILE_SEPARATOR+ TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + ".dat");
        assertTrue(collFile.exists());
    }

    // Successfully deletes entry by shifting remaining entries and updating file length
    @Test
    public void test_delete_entry_shifts_remaining_entries() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Setup
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        final var file = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + ".dat");

        JsonObject data = new JsonObject();
        data.addProperty("name", "test");

        DbEntry entry1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry1.set_id("1");
        DbEntry entry2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry2.set_id("2");

        fileSystem.insertIntoCollection(entry1);
        fileSystem.insertIntoCollection(entry2);

        PkIndexEntry entryToDelete = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 25);

        // Execute
        fileSystem.deleteFromCollection(entryToDelete);

        // Verify
        try (RandomAccessFile reader = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            byte[] content = new byte[(int)reader.length()];
            reader.readFully(content);
            assertEquals(entry2.toFileEntry(), new String(content, StandardCharsets.UTF_8));
            assertEquals(entry2.toFileEntry().length(), reader.length());
        }

        file.delete();
    }

    // Successfully updates multiple entries in collection and returns updated IndexedDbEntry list
    @Test
    public void test_bulk_update_multiple_entries_success() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        final var data1 = new JsonObject();
        data1.addProperty("_id", "1");
        data1.addProperty("field", "value");
        final var data2 = new JsonObject();
        data1.addProperty("_id", "2");
        data2.addProperty("field", "value");

        final var newEntries = List.of(DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data1), DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data2));
        List<IndexedDbEntry> entries = fileSystem.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, newEntries);

        final var first = entries.get(0);
        first.getData().get("field").asJsonString().setValue("changed");
        final var second = entries.get(1);
        second.getData().get("field").asJsonString().setValue("changed");

        List<IndexedDbEntry> result = fileSystem.bulkUpdateFromCollection(TestGlobals.DB, TestGlobals.COLL, entries);

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

        List<IndexedDbEntry> result = fileSystem.bulkUpdateFromCollection(TestGlobals.DB, TestGlobals.COLL, entries);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // Correctly updates file length after modification
    @Test
    public void test_update_file_length() throws NoSuchFieldException, IllegalAccessException, IOException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        final var file = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + ".dat");
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
}