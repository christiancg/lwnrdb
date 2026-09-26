package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TombstoneStoreTest {
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

    private File tombstoneFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-tombstones.idx");
    }

    @Test
    public void test_a_tombstone_id_with_a_pipe_round_trips() throws Exception {
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "mydb|mycoll|3", 42L);
        assertEquals(42L, fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).get("mydb|mycoll|3"));
    }

    @Test
    public void test_a_tombstone_id_with_a_delimiter_round_trips() throws Exception {
        final var id = "a" + Globals.ID_SEPARATOR + "b";
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, id, 7L);
        assertEquals(7L, fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).get(id));
    }

    @Test
    public void test_a_tombstone_id_with_a_newline_round_trips() throws Exception {
        final var id = "a\nb\rc\\d";
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, id, 9L);
        assertEquals(9L, fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).get(id));
    }

    @Test
    public void test_compact_rewrites_in_the_new_grammar() throws Exception {
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "old|id", 1L);
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "new|id", 100L);

        fs.compactTombstones(TestGlobals.DB, TestGlobals.COLL, 50L);

        final var remaining = fs.readTombstones(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, remaining.size());
        assertEquals(100L, remaining.get("new|id"));
        assertTrue(java.nio.file.Files.readString(tombstoneFile().toPath()).contains(Globals.ID_SEPARATOR),
                "compact must write the same grammar append does");
    }

    @Test
    public void test_a_file_in_the_old_pipe_grammar_is_skipped() throws Exception {
        java.nio.file.Files.writeString(tombstoneFile().toPath(),
                "a|1" + System.lineSeparator() + "b|2" + System.lineSeparator());

        assertTrue(fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).isEmpty(),
                "a pre-change tombstone file is unparseable, not half-readable");
        assertDoesNotThrow(() -> fs.compactTombstones(TestGlobals.DB, TestGlobals.COLL, 0L));
    }
}
