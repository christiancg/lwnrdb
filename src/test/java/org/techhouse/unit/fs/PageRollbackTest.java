package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PageRollbackTest {

    private FileSystem fileSystem;

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() {
        final var dbDir = new File(TestGlobals.PATH);
        if (dbDir.exists() && dbDir.isDirectory() && dbDir.canRead() && dbDir.canWrite()
                && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    private File collectionFolder() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL);
    }

    private File pageFile() {
        return new File(collectionFolder(),
                TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + 0 + Globals.DB_FILE_EXTENSION);
    }

    private void replacePkIndexWithAnUnwritableDirectory() throws IOException {
        final var pkIndex = new File(collectionFolder(), TestGlobals.COLL + "-" + Globals.PK_FIELD + "-"
                + Globals.INDEX_TYPE_STRING + Globals.INDEX_FILE_EXTENSION);
        Files.deleteIfExists(pkIndex.toPath());
        assertTrue(pkIndex.mkdirs(), "the test needs the pk index path to be unwritable");
    }

    private static DbEntry entry(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString("v"));
        final var dbEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        dbEntry.set_id(id);
        dbEntry.setPage(0);
        return dbEntry;
    }

    @Test
    public void test_a_failed_bulk_index_write_rolls_the_appended_records_back() throws Exception {
        fileSystem.insertIntoCollection(entry("kept"));
        final var before = Files.readAllBytes(pageFile().toPath());
        replacePkIndexWithAnUnwritableDirectory();

        final var batch = new ArrayList<>(List.of(entry("a"), entry("b"), entry("c")));
        assertThrows(IOException.class,
                () -> fileSystem.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, batch),
                "a bulk insert whose index write fails must surface the failure");

        assertArrayEquals(before, Files.readAllBytes(pageFile().toPath()),
                "every record of a batch whose index write failed must be truncated back off the page,"
                        + " or a scan sees documents FIND_BY_ID cannot reach");
    }

    @Test
    public void test_a_failed_single_index_write_rolls_the_appended_record_back() throws Exception {
        fileSystem.insertIntoCollection(entry("kept"));
        final var before = Files.readAllBytes(pageFile().toPath());
        replacePkIndexWithAnUnwritableDirectory();

        assertThrows(IOException.class, () -> fileSystem.insertIntoCollection(entry("orphan")),
                "a single insert whose index write fails must surface the failure");

        assertArrayEquals(before, Files.readAllBytes(pageFile().toPath()),
                "the single insert path truncates its own append back");
    }

    @Test
    public void test_a_failed_delete_index_write_restores_the_page() throws Exception {
        final var first = fileSystem.insertIntoCollection(entry("first"));
        fileSystem.insertIntoCollection(entry("second"));
        final var before = Files.readAllBytes(pageFile().toPath());
        replacePkIndexWithAnUnwritableDirectory();

        assertThrows(RuntimeException.class, () -> fileSystem.deleteFromCollection(first),
                "a delete whose index write fails must surface the failure");

        assertArrayEquals(before, Files.readAllBytes(pageFile().toPath()),
                "the page is compacted before the index is rewritten, so a failed rewrite must put it back");
    }

    @Test
    public void test_a_failed_update_index_write_restores_the_page() throws Exception {
        final var first = fileSystem.insertIntoCollection(entry("first"));
        fileSystem.insertIntoCollection(entry("second"));
        final var before = Files.readAllBytes(pageFile().toPath());
        replacePkIndexWithAnUnwritableDirectory();

        final var replacement = entry("first");
        replacement.getData().add("value", new JsonString("changed"));
        assertThrows(IOException.class, () -> fileSystem.updateFromCollection(replacement, first),
                "an update whose index write fails must surface the failure");

        assertArrayEquals(before, Files.readAllBytes(pageFile().toPath()),
                "the page is rewritten before the index, so a failed index write must put it back");
    }

    @Test
    public void test_a_successful_bulk_insert_leaves_every_record_on_the_page() throws Exception {
        final var batch = new ArrayList<>(List.of(entry("a"), entry("b"), entry("c")));

        final var indexed = fileSystem.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, batch);

        assertEquals(3, indexed.size(), "the rollback path must not disturb the ordinary bulk insert");
        assertTrue(pageFile().length() > 0, "the records must actually be on the page");
    }
}
