package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemQuarantineTest {
    private FileSystem fileSystem;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static File root() {
        return new File(TestGlobals.PATH);
    }

    private static File[] quarantinedIn(File parent, String prefix) {
        final var found = parent.listFiles((_, name) -> name.startsWith(prefix + Globals.QUARANTINE_INFIX));
        assertNotNull(found);
        return found;
    }

    @Test
    public void test_quarantine_of_an_absent_database_reports_nothing_moved() {
        assertFalse(fileSystem.folderQuarantine().moveDatabaseAside("never_created"));
    }

    @Test
    public void test_database_is_moved_aside_under_the_root() throws Exception {
        fileSystem.createDatabaseFolder("movedb");
        fileSystem.createCollectionFile("movedb", "c1");

        assertTrue(fileSystem.folderQuarantine().moveDatabaseAside("movedb"));

        assertFalse(new File(root(), "movedb").exists());
        final var moved = quarantinedIn(root(), "movedb");
        assertEquals(1, moved.length);
        assertTrue(new File(moved[0], "c1").isDirectory());
    }

    @Test
    public void test_two_quarantines_of_one_name_never_collide() {
        fileSystem.createDatabaseFolder("twicedb");
        assertTrue(fileSystem.folderQuarantine().moveDatabaseAside("twicedb"));
        fileSystem.createDatabaseFolder("twicedb");
        assertTrue(fileSystem.folderQuarantine().moveDatabaseAside("twicedb"));
        fileSystem.createDatabaseFolder("twicedb");
        assertTrue(fileSystem.folderQuarantine().moveDatabaseAside("twicedb"));

        assertEquals(3, quarantinedIn(root(), "twicedb").length);
    }

    @Test
    public void test_an_empty_page_zero_is_not_a_leftover() throws Exception {
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        assertFalse(fileSystem.folderQuarantine().holdsLeftoverCollection(TestGlobals.DB, "fresh"));
        fileSystem.createCollectionFile(TestGlobals.DB, "fresh");

        assertFalse(fileSystem.folderQuarantine().holdsLeftoverCollection(TestGlobals.DB, "fresh"));

        Files.writeString(new File(new File(new File(root(), TestGlobals.DB), "fresh"), "fresh-pk.idx").toPath(), "x");
        assertTrue(fileSystem.folderQuarantine().holdsLeftoverCollection(TestGlobals.DB, "fresh"));
    }

    @Test
    public void test_an_empty_database_folder_is_not_a_leftover() throws Exception {
        assertFalse(fileSystem.folderQuarantine().holdsLeftoverDatabase("emptydb"));
        fileSystem.createDatabaseFolder("emptydb");

        assertFalse(fileSystem.folderQuarantine().holdsLeftoverDatabase("emptydb"));

        fileSystem.createCollectionFile("emptydb", "c1");
        assertTrue(fileSystem.folderQuarantine().holdsLeftoverDatabase("emptydb"));
    }

    @Test
    public void test_startup_scanners_skip_quarantined_folders() throws Exception {
        final var collection = new File(root(), "gonedb" + Globals.QUARANTINE_INFIX + "1" + File.separator + "c1");
        assertTrue(collection.mkdirs());
        Files.writeString(new File(collection, "c1-indexes.dirty").toPath(), "1");
        Files.writeString(new File(collection, "c1-name.building").toPath(), "");

        assertTrue(fileSystem.listDirtyIndexCollections().isEmpty(),
                "a moved-aside database must not ask the operator to REINDEX a collection that does not exist");
        assertTrue(fileSystem.indexBuildMarkers().listMarked().isEmpty());
    }
}
