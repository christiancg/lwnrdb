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
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemCompactionJournalTest {
    private FileSystem fs;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "filePath", TestGlobals.PATH);
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
        if (dbDir.exists() && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    private PkIndexEntry insert(String id, String value) throws Exception {
        final var data = new JsonObject();
        data.addProperty("v", value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id(id);
        return fs.insertIntoCollection(entry);
    }

    private DbEntry updated(PkIndexEntry existing) {
        final var data = new JsonObject();
        data.addProperty("v", "uno");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id(existing.getValue());
        entry.setPage(existing.getPage());
        return entry;
    }

    private static File page0() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + "0"
                + Globals.DB_FILE_EXTENSION);
    }

    private static File pkIndex() {
        return new File(page0().getParentFile(), TestGlobals.COLL + "-pk.idx");
    }

    private void breakThePkIndex() throws Exception {
        Files.writeString(pkIndex().toPath(), "garbage\n", StandardCharsets.UTF_8);
    }

    @Test
    public void aCompletedDeleteLeavesNoMarker() throws Exception {
        final var first = insert("1", "one");
        insert("2", "two");

        fs.deleteFromCollection(first);

        assertTrue(fs.listCompactionMarkers().isEmpty());
    }

    @Test
    public void aDeleteWhosePkWriteFailsRestoresAndLeavesNoMarker() throws Exception {
        final var first = insert("1", "one");
        insert("2", "two");
        final var before = Files.readString(page0().toPath());
        breakThePkIndex();

        assertThrows(RuntimeException.class, () -> fs.deleteFromCollection(first));

        assertEquals(before, Files.readString(page0().toPath()));
        assertTrue(fs.listCompactionMarkers().isEmpty());
    }

    @Test
    public void aCompletedUpdateLeavesNoMarker() throws Exception {
        final var first = insert("1", "one");
        insert("2", "two");

        fs.updateFromCollection(updated(first), first);

        assertTrue(fs.listCompactionMarkers().isEmpty());
    }

    @Test
    public void anUpdateWhosePkWriteFailsRestoresAndLeavesNoMarker() throws Exception {
        final var first = insert("1", "one");
        insert("2", "two");
        final var before = Files.readString(page0().toPath());
        breakThePkIndex();

        assertThrows(Exception.class, () -> fs.updateFromCollection(updated(first), first));

        assertEquals(before, Files.readString(page0().toPath()));
        assertTrue(fs.listCompactionMarkers().isEmpty());
    }

    @Test
    public void aRelocationKeepsItsMarkerUntilItEnds() throws Exception {
        final var first = insert("1", "one");
        insert("2", "two");

        fs.deleteForRelocation(first);
        assertEquals(1, fs.listCompactionMarkers().size());
        final var moved = updated(first);
        moved.setPage(1);
        fs.insertRelocated(moved, first);
        fs.endRelocation(first);

        assertTrue(fs.listCompactionMarkers().isEmpty());
    }

    @Test
    public void startupRecoveryUndoesAnOpenRelocation() throws Exception {
        final var first = insert("1", "one");
        insert("2", "two");
        final var before = Files.readString(page0().toPath());
        fs.deleteForRelocation(first);

        assertTrue(fs.recoverInterruptedCompactions().isEmpty());

        assertEquals(before, Files.readString(page0().toPath()));
        assertEquals("one",
                fs.getById(fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).stream()
                        .filter(row -> row.getValue().equals("1")).findFirst().orElseThrow()).getData().get("v")
                        .asJsonString().getValue());
        assertTrue(fs.listCompactionMarkers().isEmpty());
    }

    @Test
    public void endingARelocationThatWasNeverOpenedIsHarmless() throws Exception {
        final var first = insert("1", "one");

        fs.endRelocation(first);
        final var moved = updated(first);
        fs.insertRelocated(moved, first);

        assertTrue(fs.listCompactionMarkers().isEmpty());
    }
}
