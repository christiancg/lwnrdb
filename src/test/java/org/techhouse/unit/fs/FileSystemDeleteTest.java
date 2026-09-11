package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemDeleteTest {
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

    // Successfully delete database folder and all its contents when database exists
    @Test
    public void test_delete_existing_database() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

        boolean result = fileSystem.deleteDatabase(TestGlobals.DB);

        assertTrue(result);
    }

    // Return false when database folder does not exist
    @Test
    public void test_delete_nonexistent_database() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

        boolean result = fileSystem.deleteDatabase("nonExistentDb");

        assertFalse(result);
    }

    // Successfully delete all files in existing collection folder and the folder itself
    @Test
    public void test_delete_collection_files_success() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

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

    // Successfully deletes entry by shifting remaining entries and updating file length
    @Test
    public void test_delete_entry_shifts_remaining_entries()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Setup
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);

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
}
