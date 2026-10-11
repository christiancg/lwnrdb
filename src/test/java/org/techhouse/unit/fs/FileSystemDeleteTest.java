package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;

public class FileSystemDeleteTest extends FileSystemTestBase {
    @Test
    public void test_delete_existing_database() {
        final var fileSystem = fs;

        boolean result = fileSystem.deleteDatabase(TestGlobals.DB);

        assertTrue(result);
    }

    @Test
    public void test_delete_absent_database_counts_as_deleted() {
        final var fileSystem = fs;

        boolean result = fileSystem.deleteDatabase("nonExistentDb");

        assertTrue(result);
    }

    @Test
    public void test_delete_collection_files_success() {
        final var fileSystem = fs;

        boolean result = fileSystem.deleteCollectionFiles(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(result);
    }

    @Test
    public void test_delete_absent_collection_folder_counts_as_deleted() {
        FileSystem fileSystem = new FileSystem();
        String dbName = "nonExistentDb";
        String collectionName = "nonExistentCollection";

        boolean result = fileSystem.deleteCollectionFiles(dbName, collectionName);

        assertTrue(result);
    }

    @Test
    public void test_delete_entry_shifts_remaining_entries() throws IOException {
        final var fileSystem = fs;

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

        fileSystem.deleteFromCollection(entryToDelete);

        try (RandomAccessFile reader = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            byte[] content = new byte[(int) reader.length()];
            reader.readFully(content);
            assertEquals(entry2.toFileEntry(),
                    new String(content, StandardCharsets.UTF_8).replace(Globals.NEWLINE, ""));
        }

        assertTrue(file.delete());
    }
}
