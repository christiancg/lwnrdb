package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemProcedureTest {
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clearProcedures() {
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
    }

    private static File proceduresFolder() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + Globals.PROCEDURES_FOLDER);
    }

    @Test
    public void test_read_absent_procedure_returns_null() throws Exception {
        assertNull(fs.readProcedure(TestGlobals.DB, "nothing"));
    }

    @Test
    public void test_write_then_read_returns_source() throws Exception {
        fs.writeProcedure(TestGlobals.DB, "one", "{\"name\":\"one\"}");
        assertEquals("{\"name\":\"one\"}", fs.readProcedure(TestGlobals.DB, "one"));
    }

    @Test
    public void test_write_creates_procedures_folder_on_first_use() throws Exception {
        TestUtils.deleteFolder(proceduresFolder());
        assertFalse(proceduresFolder().exists());
        fs.writeProcedure(TestGlobals.DB, "one", "{}");
        assertTrue(proceduresFolder().isDirectory());
    }

    @Test
    public void test_overwrite_replaces_the_previous_source() throws Exception {
        fs.writeProcedure(TestGlobals.DB, "one", "{\"version\":1}");
        fs.writeProcedure(TestGlobals.DB, "one", "{\"version\":2}");
        assertEquals("{\"version\":2}", fs.readProcedure(TestGlobals.DB, "one"));
    }

    @Test
    public void test_delete_returns_false_when_absent() {
        assertFalse(fs.deleteProcedure(TestGlobals.DB, "nothing"));
    }

    @Test
    public void test_delete_removes_an_existing_procedure() throws Exception {
        fs.writeProcedure(TestGlobals.DB, "one", "{}");
        assertTrue(fs.deleteProcedure(TestGlobals.DB, "one"));
        assertNull(fs.readProcedure(TestGlobals.DB, "one"));
    }

    @Test
    public void test_list_returns_empty_when_folder_absent() {
        TestUtils.deleteFolder(proceduresFolder());
        assertTrue(fs.listProcedureNames(TestGlobals.DB).isEmpty());
    }

    @Test
    public void test_list_strips_extension_and_sorts() throws Exception {
        fs.writeProcedure(TestGlobals.DB, "second", "{}");
        fs.writeProcedure(TestGlobals.DB, "first", "{}");
        assertEquals(java.util.List.of("first", "second"), fs.listProcedureNames(TestGlobals.DB));
    }

    // The free-cascade claim: a DROP_DATABASE deletes the database folder's children generically, and
    // .procedures is one of them - which is why it must be a folder and not a loose file.
    @Test
    public void test_delete_database_removes_procedures_folder() throws Exception {
        fs.createDatabaseFolder("procdropdb");
        fs.createCollectionFile("procdropdb", "somecoll");
        fs.writeProcedure("procdropdb", "one", "{}");
        assertTrue(fs.deleteDatabase("procdropdb"));
        assertFalse(new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + "procdropdb").exists());
    }

    @Test
    public void test_read_absent_triggers_returns_null() throws Exception {
        assertNull(fs.readTriggers(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_write_then_read_triggers() throws Exception {
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL, "[{\"name\":\"t\"}]");
        assertEquals("[{\"name\":\"t\"}]", fs.readTriggers(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_delete_triggers_reports_whether_a_file_was_removed() throws Exception {
        assertFalse(fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL));
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL, "[]");
        assertTrue(fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL));
    }

    // A DROP_COLLECTION deletes every file in the collection folder, the trigger file among them
    @Test
    public void test_delete_collection_files_removes_triggers_file() throws Exception {
        fs.createCollectionFile(TestGlobals.DB, "trigdropcoll");
        fs.writeTriggers(TestGlobals.DB, "trigdropcoll", "[{\"name\":\"t\"}]");
        assertTrue(fs.deleteCollectionFiles(TestGlobals.DB, "trigdropcoll"));
        assertNull(fs.readTriggers(TestGlobals.DB, "trigdropcoll"));
    }
}
