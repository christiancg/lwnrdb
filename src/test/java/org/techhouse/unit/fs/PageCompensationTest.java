package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PageCompensationTest {
    private FileSystem fs;

    @BeforeEach
    public void setUp() throws Exception {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() {
        final var dbDir = new File(TestGlobals.PATH);
        if (dbDir.exists() && dbDir.isDirectory() && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    private File pageFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + "0"
                + Globals.DB_FILE_EXTENSION);
    }

    private File pkIndexFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.PK_FIELD + Globals.INDEX_FILE_NAME_SEPARATOR + Globals.INDEX_TYPE_STRING
                + Globals.INDEX_FILE_EXTENSION);
    }

    private org.techhouse.data.PkIndexEntry insert(String id) throws Exception {
        final var data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, id);
        data.addProperty("value", "v-" + id);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id(id);
        return fs.insertIntoCollection(entry);
    }

    @Test
    public void test_a_failed_index_write_on_delete_restores_the_page() throws Exception {
        final var first = insert("a");
        insert("b");
        final var pageBefore = Files.readString(pageFile().toPath(), StandardCharsets.UTF_8);

        final var index = pkIndexFile();
        assertTrue(index.delete());
        assertTrue(index.mkdirs(), "a directory in the index file's place makes the index write fail");
        try {
            assertThrows(RuntimeException.class, () -> fs.deleteFromCollection(first));
        } finally {
            assertTrue(index.delete());
        }

        assertEquals(pageBefore, Files.readString(pageFile().toPath(), StandardCharsets.UTF_8),
                "the page is compacted before the index is rewritten, so a failed index write left every"
                        + " surviving record at an offset the index no longer describes");
    }

    @Test
    public void test_a_successful_delete_still_compacts_the_page() throws Exception {
        final var first = insert("a");
        insert("b");

        fs.deleteFromCollection(first);

        final var page = Files.readString(pageFile().toPath(), StandardCharsets.UTF_8);
        assertTrue(page.contains("v-b"), "the surviving record must remain");
        assertFalse(page.contains("v-a"), "the deleted record must be gone");
    }
}
