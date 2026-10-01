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
        final var content = "a|1" + System.lineSeparator() + "b|2" + System.lineSeparator();
        java.nio.file.Files.writeString(tombstoneFile().toPath(), content);

        assertTrue(fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).isEmpty(),
                "a pre-change tombstone file is unparseable, not half-readable");
        assertDoesNotThrow(() -> fs.compactTombstones(TestGlobals.DB, TestGlobals.COLL, 0L));
    }

    @Test
    public void test_a_file_where_every_line_is_malformed_is_left_untouched() throws Exception {
        final var content = "a|1" + System.lineSeparator() + "b|2" + System.lineSeparator();
        java.nio.file.Files.writeString(tombstoneFile().toPath(), content);

        fs.readTombstones(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(content, java.nio.file.Files.readString(tombstoneFile().toPath()),
                "a file where nothing parsed is not the file this loader expects, so it must be left untouched"
                        + " rather than rewritten as empty");
    }

    @Test
    public void test_compact_leaves_a_file_where_every_line_is_malformed_untouched() throws Exception {
        final var content = "a|1" + System.lineSeparator() + "b|2" + System.lineSeparator();
        java.nio.file.Files.writeString(tombstoneFile().toPath(), content);

        fs.compactTombstones(TestGlobals.DB, TestGlobals.COLL, 0L);

        assertEquals(content, java.nio.file.Files.readString(tombstoneFile().toPath()),
                "compaction must not rewrite a file it understood none of");
    }

    @Test
    public void test_a_torn_last_line_is_healed_on_read() throws Exception {
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "surviving", 5L);
        java.nio.file.Files.writeString(tombstoneFile().toPath(),
                "torn-line-with-no-separator" + System.lineSeparator(), java.nio.file.StandardOpenOption.APPEND);

        final var read = fs.readTombstones(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(1, read.size());
        assertEquals(5L, read.get("surviving"));
        final var healed = java.nio.file.Files.readString(tombstoneFile().toPath());
        assertTrue(healed.contains("surviving") && !healed.contains("torn-line-with-no-separator"),
                "a torn line must be dropped and the survivors rewritten, matching the pk/field index self-heal");
    }

    @Test
    public void test_append_failure_leaves_the_file_unchanged() throws Exception {
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "existing", 1L);
        final var file = tombstoneFile();
        final var before = java.nio.file.Files.readAllBytes(file.toPath());

        assertTrue(file.setWritable(false), "the test needs a writable-toggle filesystem to force the append to fail");
        try {
            assertThrows(java.io.IOException.class,
                    () -> fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "blocked", 2L));
        } finally {
            assertTrue(file.setWritable(true));
        }

        assertArrayEquals(before, java.nio.file.Files.readAllBytes(file.toPath()),
                "a failed append must not leave a torn line behind");
    }

    @Test
    public void test_an_append_after_an_unterminated_parseable_line_keeps_both_tombstones() throws Exception {
        java.nio.file.Files.writeString(tombstoneFile().toPath(), "torn" + Globals.ID_SEPARATOR + "17");

        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "acked", 99L);

        final var read = fs.readTombstones(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(17L, read.get("torn"));
        assertEquals(99L, read.get("acked"),
                "an acknowledged tombstone must not be glued onto a torn line the next heal would drop");
    }

    @Test
    public void test_an_append_after_a_terminated_line_writes_no_blank_line() throws Exception {
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "first", 1L);
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "second", 2L);

        assertEquals(2, java.nio.file.Files.readAllLines(tombstoneFile().toPath()).size());
    }
}
