package org.techhouse.unit.fs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.DirectoryNotFoundException;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

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
                    assertTrue(f.delete());
                }
            }
        }
        assertTrue(folder.delete());
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

        File databasesCollection = new File(adminDbFolder.getPath() + Globals.FILE_SEPARATOR + Globals.ADMIN_DATABASES_COLLECTION_NAME);
        assertTrue(databasesCollection.exists());

        File collectionsCollection = new File(adminDbFolder.getPath() + Globals.FILE_SEPARATOR + Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
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

        assertTrue(file.delete());
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

    // Successfully writes index entries to file for each type in the map
    @Test
    public void test_writes_index_entries_to_file() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "testField";
        Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap = new HashMap<>();
        List<FieldIndexEntry<?>> stringEntries = List.of(
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value1", Set.of("id1", "id2")),
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value2", Set.of("id3"))
        );
        indexEntryMap.put(String.class, stringEntries);
        fileSystem.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, indexEntryMap);
        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB +Globals.FILE_SEPARATOR+ TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-String.idx");
        String fileContent = Files.readString(indexFile.toPath());
        assertTrue(fileContent.contains("value1" + Globals.INDEX_ENTRY_SEPARATOR + "id2;id1") ||
                fileContent.contains("value1" + Globals.INDEX_ENTRY_SEPARATOR + "id1;id2"));
        assertTrue(fileContent.contains("value2" + Globals.INDEX_ENTRY_SEPARATOR + "id3"));
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
        final var index = fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, String.class);
        assertNull(index);
    }

    // Successfully update index file when both insertedEntry and removedEntry are provided
    @Test
    public void test_update_index_files_with_insert_and_remove() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);
        String fieldName = "testField";

        Set<String> ids1 = new HashSet<>(Arrays.asList("id1", "id2"));
        Set<String> ids2 = new HashSet<>(Arrays.asList("id3", "id4"));

        FieldIndexEntry<String> insertEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value1", ids1);
        FieldIndexEntry<String> removeEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value2", ids2);

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, insertEntry, removeEntry);
        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB +Globals.FILE_SEPARATOR+ TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-String.idx");
        String fileContent = Files.readString(indexFile.toPath());

        assertTrue(fileContent.contains("value1" + Globals.INDEX_ENTRY_SEPARATOR + "id1;id2") ||
                fileContent.contains("value1" + Globals.INDEX_ENTRY_SEPARATOR + "id2;id1"));
        assertFalse(fileContent.contains("value2" + Globals.INDEX_ENTRY_SEPARATOR + "id3;id4") ||
                fileContent.contains("value2" + Globals.INDEX_ENTRY_SEPARATOR + "id4;id3"));
    }

    // Handle case when removedEntry has empty ids set
    @Test
    public void test_update_index_files_with_empty_ids_remove() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        String fieldName = "testField";

        Set<String> emptyIds = new HashSet<>();
        FieldIndexEntry<Integer> removeEntry = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 123, emptyIds);

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, null, removeEntry);

        File indexFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB +Globals.FILE_SEPARATOR+ TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + fieldName + "-Number.idx");
        String fileContent = Files.readString(indexFile.toPath());

        assertFalse(fileContent.contains("123" + Globals.INDEX_ENTRY_SEPARATOR));
    }

    // Successfully delete all index files for a given field in an existing collection
    @Test
    public void test_delete_index_files_success() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);

        String fieldName = "testField";

        File mockCollFolder = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);

        File indexFile1 = new File(mockCollFolder, "1" + Globals.INDEX_FILE_NAME_SEPARATOR + fieldName + Globals.INDEX_FILE_NAME_SEPARATOR + "idx" + Globals.INDEX_FILE_EXTENSION);
        File indexFile2 = new File(mockCollFolder, "2" + Globals.INDEX_FILE_NAME_SEPARATOR + fieldName + Globals.INDEX_FILE_NAME_SEPARATOR + "idx" + Globals.INDEX_FILE_EXTENSION);

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
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value2", Set.of("id3"))
        );
        indexEntryMap.put(String.class, stringEntries);
        fileSystem.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, indexEntryMap);

        // Act
        ConcurrentMap<String, List<FieldIndexEntry<?>>> result = fileSystem.readAllWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName);

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
        ConcurrentMap<String, List<FieldIndexEntry<?>>> result = fileSystem.readAllWholeFieldIndexFiles(TestGlobals.DB, "unexistantCollection", fieldName);

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
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "value2", Set.of("id3"))
        );
        indexEntryMap.put(String.class, stringEntries);
        fs.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, indexEntryMap);

        List<FieldIndexEntry<String>> entries = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, fieldName, String.class);

        assertNotNull(entries);
        assertEquals(2, entries.size());
        assertTrue(entries.getFirst().getIds().contains("id1") && entries.getFirst().getIds().contains("id2"));
        assertTrue(entries.get(1).getIds().contains("id3"));
    }

    // Handle empty index files
    @Test
    public void test_empty_index_file_returns_empty_list() throws IOException, NoSuchFieldException, IllegalAccessException {
        FileSystem fs = new FileSystem();
        TestUtils.setPrivateField(fs, "dbPath", TestGlobals.PATH);

        List<FieldIndexEntry<String>> entries = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, "emptyField", String.class);

        assertNull(entries);
    }

    // Returns sorted list of PkIndexEntry objects when index file exists and contains entries
    @Test
    public void test_read_index_file_returns_sorted_entries() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        File mockIndexFile = mock(File.class);
        when(mockIndexFile.exists()).thenReturn(true);
        Path path = Path.of(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB +Globals.FILE_SEPARATOR+ TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-_id-String.idx");
        when(mockIndexFile.toPath()).thenReturn(path);

        List<String> fileLines = Arrays.asList(
                "value3" + Globals.INDEX_ENTRY_SEPARATOR + "300" + Globals.INDEX_ENTRY_SEPARATOR + "100",
                "value1" + Globals.INDEX_ENTRY_SEPARATOR + "100" + Globals.INDEX_ENTRY_SEPARATOR + "100",
                "value2" + Globals.INDEX_ENTRY_SEPARATOR + "200" + Globals.INDEX_ENTRY_SEPARATOR + "100"
        );
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
    public void test_empty_index_file_returns_empty_list_2() throws IOException, NoSuchFieldException, IllegalAccessException {
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

    // Successfully reads and parses collection file into Map<String, DbEntry>
    @Test
    public void test_read_collection_file_success() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        String testData = "{\"_id\":\"123\",\"name\":\"test\"}{\"_id\":\"456\",\"name\":\"test2\"}";

        File mockFile = mock(File.class);
        when(mockFile.exists()).thenReturn(true);
        when(mockFile.toPath()).thenReturn(Path.of("test.db"));

        try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
            mockedFiles.when(() -> Files.readString(any(Path.class))).thenReturn(testData);

            // Act
            Map<String, DbEntry> result = fileSystem.readWholeCollection(TestGlobals.DB, TestGlobals.COLL);

            // Assert
            assertEquals(2, result.size());
            assertTrue(result.containsKey("123"));
            assertTrue(result.containsKey("456"));
            assertEquals(TestGlobals.DB, result.get("123").getDatabaseName());
            assertEquals(TestGlobals.COLL, result.get("123").getCollectionName());
        }
    }

    // Handles empty collection file
    @Test
    public void test_empty_collection_file() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        // Act
        Map<String, DbEntry> result = fileSystem.readWholeCollection(TestGlobals.DB, TestGlobals.COLL);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // Successfully splits collection identifier with correct separator and returns collection data
    @Test
    public void test_split_collection_identifier_and_return_data() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        String collectionIdentifier = TestGlobals.DB + "|" + TestGlobals.COLL;
        DbEntry expectedEntry = new DbEntry();
        expectedEntry.set_id("1");
        expectedEntry.setDatabaseName(TestGlobals.DB);
        expectedEntry.setCollectionName(TestGlobals.COLL);
        expectedEntry.setData(new JsonObject());
        Map<String, DbEntry> expected = Map.of("1", expectedEntry);

        fileSystem.insertIntoCollection(expectedEntry);

        // Act
        Map<String, DbEntry> result = fileSystem.readWholeCollection(collectionIdentifier);

        // Assert
        assertEquals(expected.size(), result.size());
        assertEquals(expected.get("1").get_id(), result.get("1").get_id());
        assertEquals(expected.get("1").getDatabaseName(), result.get("1").getDatabaseName());
        assertEquals(expected.get("1").getCollectionName(), result.get("1").getCollectionName());
    }

    // Handles empty collection file by returning empty HashMap
    @Test
    public void test_empty_collection_returns_empty_map() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        FileSystem fileSystem = new FileSystem();
        TestUtils.setPrivateField(fileSystem, "dbPath", TestGlobals.PATH);

        String collectionIdentifier = TestGlobals.DB + "|" + TestGlobals.COLL;

        // Act
        Map<String, DbEntry> result = fileSystem.readWholeCollection(collectionIdentifier);

        // Assert
        assertTrue(result.isEmpty());
    }
}