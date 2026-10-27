package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.test.TestGlobals;

public class TombstoneStoreTest extends FileSystemTestBase {

    private File tombstoneFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-tombstones.idx");
    }

    @Test
    public void test_a_tombstone_id_with_a_pipe_round_trips() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "mydb|mycoll|3", 42L);
        assertEquals(42L, fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).get("mydb|mycoll|3"));
    }

    @Test
    public void test_a_tombstone_id_with_a_delimiter_round_trips() throws Exception {
        final var id = "a" + Globals.ID_SEPARATOR + "b";
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, id, 7L);
        assertEquals(7L, fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).get(id));
    }

    @Test
    public void test_a_tombstone_id_with_a_newline_round_trips() throws Exception {
        final var id = "a\nb\rc\\d";
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, id, 9L);
        assertEquals(9L, fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).get(id));
    }

    @Test
    public void test_retract_removes_only_the_matching_id_and_version() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "a", 5L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "a|b", 7L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "b", 7L);

        fs.tombstones().retract(TestGlobals.DB, TestGlobals.COLL, "a|b", 7L);
        fs.tombstones().retract(TestGlobals.DB, TestGlobals.COLL, "a", 6L);

        final var tombstones = fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(5L, tombstones.get("a"));
        assertEquals(7L, tombstones.get("b"));
        assertFalse(tombstones.containsKey("a|b"));
    }

    @Test
    public void test_retract_keeps_an_older_tombstone_of_the_same_id() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "a", 5L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "a", 9L);

        fs.tombstones().retract(TestGlobals.DB, TestGlobals.COLL, "a", 9L);

        assertEquals(5L, fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).get("a"));
    }

    @Test
    public void test_retract_leaves_an_unrecognised_file_alone() throws Exception {
        java.nio.file.Files.writeString(tombstoneFile().toPath(), "garbage\n");

        fs.tombstones().retract(TestGlobals.DB, TestGlobals.COLL, "a", 1L);

        assertEquals("garbage\n", java.nio.file.Files.readString(tombstoneFile().toPath()));
    }

    @Test
    public void test_retract_of_an_absent_file_is_a_no_op() throws Exception {
        fs.tombstones().retract(TestGlobals.DB, TestGlobals.COLL, "a", 1L);

        assertFalse(tombstoneFile().exists());
    }

    @Test
    public void test_compact_rewrites_in_the_new_grammar() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "old|id", 1L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "new|id", 100L);

        fs.tombstones().compact(TestGlobals.DB, TestGlobals.COLL, 50L);

        final var remaining = fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, remaining.size());
        assertEquals(100L, remaining.get("new|id"));
        assertTrue(java.nio.file.Files.readString(tombstoneFile().toPath()).contains(Globals.ID_SEPARATOR),
                "compact must write the same grammar append does");
    }

    @Test
    public void test_a_file_in_the_old_pipe_grammar_is_skipped() throws Exception {
        final var content = "a|1" + System.lineSeparator() + "b|2" + System.lineSeparator();
        java.nio.file.Files.writeString(tombstoneFile().toPath(), content);

        assertTrue(fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).isEmpty(),
                "a pre-change tombstone file is unparseable, not half-readable");
        assertDoesNotThrow(() -> fs.tombstones().compact(TestGlobals.DB, TestGlobals.COLL, 0L));
    }

    @Test
    public void test_a_file_where_every_line_is_malformed_is_left_untouched() throws Exception {
        final var content = "a|1" + System.lineSeparator() + "b|2" + System.lineSeparator();
        java.nio.file.Files.writeString(tombstoneFile().toPath(), content);

        fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(content, java.nio.file.Files.readString(tombstoneFile().toPath()),
                "a file where nothing parsed is not the file this loader expects, so it must be left untouched"
                        + " rather than rewritten as empty");
    }

    @Test
    public void test_compact_leaves_a_file_where_every_line_is_malformed_untouched() throws Exception {
        final var content = "a|1" + System.lineSeparator() + "b|2" + System.lineSeparator();
        java.nio.file.Files.writeString(tombstoneFile().toPath(), content);

        fs.tombstones().compact(TestGlobals.DB, TestGlobals.COLL, 0L);

        assertEquals(content, java.nio.file.Files.readString(tombstoneFile().toPath()),
                "compaction must not rewrite a file it understood none of");
    }

    @Test
    public void test_a_torn_last_line_is_healed_on_read() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "surviving", 5L);
        java.nio.file.Files.writeString(tombstoneFile().toPath(),
                "torn-line-with-no-separator" + System.lineSeparator(), java.nio.file.StandardOpenOption.APPEND);

        final var read = fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(1, read.size());
        assertEquals(5L, read.get("surviving"));
        final var healed = java.nio.file.Files.readString(tombstoneFile().toPath());
        assertTrue(healed.contains("surviving") && !healed.contains("torn-line-with-no-separator"),
                "a torn line must be dropped and the survivors rewritten, matching the pk/field index self-heal");
    }

    @Test
    public void test_append_failure_leaves_the_file_unchanged() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "existing", 1L);
        final var file = tombstoneFile();
        final var before = java.nio.file.Files.readAllBytes(file.toPath());

        assertTrue(file.setWritable(false), "the test needs a writable-toggle filesystem to force the append to fail");
        try {
            assertThrows(java.io.IOException.class,
                    () -> fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "blocked", 2L));
        } finally {
            assertTrue(file.setWritable(true));
        }

        assertArrayEquals(before, java.nio.file.Files.readAllBytes(file.toPath()),
                "a failed append must not leave a torn line behind");
    }

    @Test
    public void test_an_append_after_an_unterminated_parseable_line_keeps_both_tombstones() throws Exception {
        java.nio.file.Files.writeString(tombstoneFile().toPath(), "torn" + Globals.ID_SEPARATOR + "17");

        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "acked", 99L);

        final var read = fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(17L, read.get("torn"));
        assertEquals(99L, read.get("acked"),
                "an acknowledged tombstone must not be glued onto a torn line the next heal would drop");
    }

    @Test
    public void test_an_append_after_a_terminated_line_writes_no_blank_line() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "first", 1L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "second", 2L);

        assertEquals(2, java.nio.file.Files.readAllLines(tombstoneFile().toPath()).size());
    }

    private <T> T unreadable(java.util.concurrent.Callable<T> read) throws Exception {
        final var file = tombstoneFile();
        assertTrue(file.setReadable(false), "the test needs a readable-toggle filesystem");
        try {
            return read.call();
        } finally {
            assertTrue(file.setReadable(true));
        }
    }

    @Test
    public void test_versionOf_reads_the_file_once() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "a", 5L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "b", 7L);
        assertEquals(5L, fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "a"));

        final long b = unreadable(() -> fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "b"));
        final long none = unreadable(() -> fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "c"));

        assertEquals(7L, b, "an unchanged file is answered from memory");
        assertEquals(0L, none);
    }

    @Test
    public void test_an_external_append_is_seen() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "a", 5L);
        assertEquals(0L, fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "z"));

        java.nio.file.Files.writeString(tombstoneFile().toPath(), "z" + Globals.ID_SEPARATOR + "11\n",
                java.nio.file.StandardOpenOption.APPEND);

        assertEquals(11L, fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "z"));
        assertEquals(5L, fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "a"));
    }

    @Test
    public void test_a_failed_append_leaves_the_resident_map_unchanged() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "existing", 1L);
        final var view = fs.tombstones().view(TestGlobals.DB, TestGlobals.COLL);
        final var file = tombstoneFile();
        assertTrue(file.setWritable(false));
        try {
            assertThrows(java.io.IOException.class,
                    () -> fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "blocked", 2L));
        } finally {
            assertTrue(file.setWritable(true));
        }

        assertEquals(0L, view.versionOf("blocked"));
        assertEquals(0L, fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "blocked"));
        assertEquals(1L, fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "existing"));
    }

    @Test
    public void test_an_append_is_published_to_a_loaded_view() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "a", 5L);
        final var view = fs.tombstones().view(TestGlobals.DB, TestGlobals.COLL);

        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "b", 9L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "a", 3L);

        assertEquals(9L, view.versionOf("b"));
        assertEquals(5L, view.versionOf("a"), "a lower version never replaces a higher one");
        final var after = fs.tombstones().view(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(2, after.size());
        assertEquals(9L, after.highest());
        assertEquals(3L, after.oldest());
    }

    @Test
    public void test_retract_restores_the_next_highest_version() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "a", 5L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "a", 9L);
        assertEquals(9L, fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "a"));

        fs.tombstones().retract(TestGlobals.DB, TestGlobals.COLL, "a", 9L);

        assertEquals(5L, (long) unreadable(() -> fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "a")),
                "the retract publishes the lines it kept");
    }

    @Test
    public void test_compact_publishes_the_kept_entries() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "old", 1L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "new", 100L);
        fs.tombstones().view(TestGlobals.DB, TestGlobals.COLL);

        fs.tombstones().compact(TestGlobals.DB, TestGlobals.COLL, 50L);

        final var view = unreadable(() -> fs.tombstones().view(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(1, view.size());
        assertEquals(0L, view.versionOf("old"));
        assertEquals(100L, view.versionOf("new"));
    }

    @Test
    public void test_forgetUnder_drops_a_quarantined_collection() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "x", 5L);
        final var stamp = java.nio.file.Files.getLastModifiedTime(tombstoneFile().toPath());
        assertEquals(5L, fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "x"));

        assertTrue(fs.folderQuarantine().moveCollectionAside(TestGlobals.DB, TestGlobals.COLL, 1L));
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
        java.nio.file.Files.writeString(tombstoneFile().toPath(), "y" + Globals.ID_SEPARATOR + "5\n");
        java.nio.file.Files.setLastModifiedTime(tombstoneFile().toPath(), stamp);

        assertEquals(0L, fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "x"),
                "a file of the same length and time in a re-created folder is not the quarantined one");
        assertEquals(5L, fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "y"));
    }

    @Test
    public void test_a_read_failure_is_not_cached_as_empty() throws Exception {
        java.nio.file.Files.writeString(tombstoneFile().toPath(), "a" + Globals.ID_SEPARATOR + "4\n");

        assertThrows(java.io.IOException.class,
                () -> unreadable(() -> fs.tombstones().view(TestGlobals.DB, TestGlobals.COLL)));

        assertEquals(4L, fs.tombstones().versionOf(TestGlobals.DB, TestGlobals.COLL, "a"));
    }

    @Test
    public void test_highestVersion_parses_without_loading() throws Exception {
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "a", 5L);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "b", 12L);

        assertEquals(12L, fs.tombstones().highestVersion(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(0L, fs.tombstones().highestVersion(TestGlobals.DB, "noSuchColl"));
    }
}
