package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.test.TestUtils;

public class WriteAheadLogTest {
    private static final String DB = "walDb";
    private static final String COLL = "walColl";
    private static final long SHIPPED_CHECKPOINT_BYTES = 8L * 1024 * 1024;

    @TempDir
    File tmp;

    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", SHIPPED_CHECKPOINT_BYTES);
    }

    private File log() {
        return rf.fx.paths.writeAheadLog(DB, COLL);
    }

    private WalCodec.Decoded decoded() throws IOException {
        return WalCodec.decodeAll(Files.readAllBytes(log().toPath()));
    }

    @Test
    public void test_the_first_write_starts_the_log_with_a_checkpoint() throws Exception {
        final var decoded = decoded();

        assertEquals(DB, decoded.checkpoint().dbName());
        assertEquals(COLL, decoded.checkpoint().collName());
        assertEquals(3, decoded.records().size(), "one record per seeded row");
    }

    @Test
    public void test_the_log_checkpoints_itself_past_wal_checkpoint_bytes() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", 1L);

        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        assertEquals(List.of(), decoded().records());
        assertEquals(rf.fx.page(0).length(), decoded().checkpoint().pageLengths().get(0L));
        assertEquals(rf.fx.paths.pkIndexFile(DB, COLL).length(), decoded().checkpoint().pkIndexLength());
    }

    @Test
    public void test_a_record_appended_by_another_writer_is_seen_by_length() throws Exception {
        final var other = WalCodec
                .encode(new WalRecord(500L, List.of(new WalChange(WalTarget.PAGE_BLANK, 0L, 0L, 0L, new byte[0]))));
        Files.write(log().toPath(), other, StandardOpenOption.APPEND);

        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        final var records = decoded().records();
        assertEquals(501L, records.getLast().seq(), "the bookkeeping re-reads a log it did not write last");
        assertEquals(5, records.size());
    }

    @Test
    public void test_a_torn_tail_is_cut_before_the_next_append() throws Exception {
        Files.write(log().toPath(), new byte[]{'W', 'R', 0}, StandardOpenOption.APPEND);

        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        final var decoded = decoded();
        assertEquals(Files.size(log().toPath()), decoded.cleanLength());
        assertEquals(4, decoded.records().size());
    }

    @Test
    public void test_a_dropped_collection_is_not_checkpointed_back_into_existence() throws Exception {
        final var folder = rf.fx.paths.collectionFolder(DB, COLL);
        assertTrue(FileTreeDeleter.delete(folder));

        new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);

        assertFalse(folder.exists());
    }

    @Test
    public void test_a_full_log_whose_checkpoint_fails_does_not_fail_the_write() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", 1L);
        try (var recorder = RecordingForcer.install(true)) {
            recorder.failingFor(".wal.repair");

            final var updated = rf.replace("b", "new-b", 0, PagePlacement.APPEND);

            assertEquals(StorageFixture.record("b", "new-b"), rf.fx.readAt(updated));
            assertFalse(decoded().records().isEmpty(), "the log keeps the records its checkpoint could not drop");
        }
    }

    @Test
    public void test_the_guard_runs_only_when_the_log_has_records_and_its_ids_are_logged() throws Exception {
        final var original = TestUtils.getPrivateStaticField(WriteAheadLog.class, "guard", CheckpointGuard.class);
        final var persisted = new AtomicInteger();
        WriteAheadLog.guardWith(new CheckpointGuard() {
            @Override
            public boolean holdsIndexWork(String dbName, String collName) {
                return false;
            }

            @Override
            public Collection<String> pendingIds(String dbName, String collName) {
                persisted.incrementAndGet();
                return List.of("waiting");
            }
        });
        try {
            final var log = new WriteAheadLog(rf.fx.paths);
            log.checkpoint(DB, COLL);
            log.checkpoint(DB, COLL);

            assertEquals(1, persisted.get(), "the second checkpoint drops nothing, so it has nothing to protect");
            assertEquals(List.of("waiting"), decoded().pendingIds());
        } finally {
            WriteAheadLog.guardWith(original);
        }
    }

    private long wals(RecordingForcer recorder) {
        return recorder.forcedNames().stream().filter((COLL + ".wal")::equals).count();
    }

    private static boolean blanked(String text) {
        return text.isBlank();
    }

    @Test
    public void test_a_group_forces_its_records_once_after_one_base_per_checkpoint() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            final var pkBefore = rf.fx.paths.pkIndexFile(DB, COLL).length();
            wal.openGroup(DB, COLL);
            rf.replace("a", "new-a", 0, PagePlacement.APPEND);
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            rf.replacer.delete(rf.fx.indexed("c"));

            assertEquals(1L, wals(recorder), "only the group base is forced before the group settles");
            assertTrue(wal.closeGroup(DB, COLL));
            assertEquals(2L, wals(recorder));
            assertEquals(pkBefore, decoded().groupBase());
            assertEquals(6, decoded().records().size(), "three seeded rows and three grouped writes");

            wal.openGroup(DB, COLL);
            rf.replace("a", "newer-a", 0, PagePlacement.APPEND);
            assertTrue(wal.closeGroup(DB, COLL));
            assertEquals(3L, wals(recorder), "a later group in the same interval reuses the base");
        }
    }

    @Test
    public void test_a_group_defers_its_blanks_until_the_force() throws Exception {
        try (var ignored = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            final var old = rf.fx.indexed("b");
            wal.openGroup(DB, COLL);

            rf.replace("b", "new-b", 0, PagePlacement.APPEND);

            assertEquals(StorageFixture.record("b", "old-b"), rf.fx.readAt(old), "the old copy waits for the force");
            assertTrue(wal.closeGroup(DB, COLL));
            assertTrue(blanked(rf.fx.readAt(old)));
            assertEquals(StorageFixture.record("b", "new-b"), rf.fx.readAt(rf.fx.indexed("b")));
        }
    }

    @Test
    public void test_a_checkpoint_inside_a_group_settles_it_first() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            final var old = rf.fx.indexed("b");
            wal.openGroup(DB, COLL);
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);

            wal.checkpoint(DB, COLL);

            assertEquals(2L, wals(recorder), "the base, then the settle");
            assertTrue(blanked(rf.fx.readAt(old)));
            assertEquals(List.of(), decoded().records());
            assertEquals(WalCodec.NO_GROUP_BASE, decoded().groupBase());
            assertTrue(wal.closeGroup(DB, COLL), "nothing is left to settle");
            assertEquals(2L, wals(recorder));
        }
    }

    @Test
    public void test_require_applied_settles_an_open_group_before_replaying() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            final var old = rf.fx.indexed("b");
            wal.openGroup(DB, COLL);
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            wal.markUnapplied(DB, COLL);

            wal.requireApplied(DB, COLL);

            assertTrue(wals(recorder) >= 1L, "the replay runs only over a forced log");
            assertTrue(blanked(rf.fx.readAt(old)));
            assertTrue(wal.closeGroup(DB, COLL));
        }
    }

    @Test
    public void test_a_failed_group_force_takes_every_record_back() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            final var page = rf.fx.page(0);
            final var pk = rf.fx.paths.pkIndexFile(DB, COLL);
            final var pageBefore = page.length();
            final var pkBefore = pk.length();
            final var recordsBefore = decoded().records().size();
            final var old = rf.fx.indexed("b");
            wal.openGroup(DB, COLL);
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            rf.replacer.delete(rf.fx.indexed("c"));
            recorder.failingFor(COLL + ".wal");

            assertFalse(wal.closeGroup(DB, COLL));

            assertEquals(pageBefore, page.length());
            assertEquals(pkBefore, pk.length());
            assertEquals(recordsBefore, decoded().records().size(), "the log keeps only its base");
            assertEquals(pkBefore, decoded().groupBase());
            assertEquals(StorageFixture.record("b", "old-b"), rf.fx.readAt(old));
            assertEquals(old, rf.fx.indexed("b"));
            assertEquals(StorageFixture.record("c", "old-c"), rf.fx.readAt(rf.fx.indexed("c")));
        }
    }

    @Test
    public void test_a_write_after_a_failed_settle_in_the_same_group_is_refused() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            wal.openGroup(DB, COLL);
            rf.replace("a", "new-a", 0, PagePlacement.APPEND);
            recorder.failingFor(COLL + ".wal");
            TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", 1L);
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);

            assertThrows(IOException.class, () -> rf.replace("c", "new-c", 0, PagePlacement.APPEND));
            assertFalse(wal.closeGroup(DB, COLL));
            assertEquals(StorageFixture.record("a", "old-a"), rf.fx.readAt(rf.fx.indexed("a")));
            assertEquals(StorageFixture.record("b", "old-b"), rf.fx.readAt(rf.fx.indexed("b")));
        }
    }

    @Test
    public void test_a_failed_take_back_marks_the_log_unapplied() throws Exception {
        final var page = rf.fx.page(0);
        final var old = rf.fx.indexed("b");
        try (var recorder = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            wal.openGroup(DB, COLL);
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            recorder.failingFor(COLL + ".wal");
            assertTrue(page.setWritable(false));
            try {
                assertFalse(wal.closeGroup(DB, COLL));
            } finally {
                assertTrue(page.setWritable(true));
            }
        }
        try (var ignored = RecordingForcer.install(true)) {
            rf.replace("a", "new-a", 0, PagePlacement.APPEND);

            assertEquals(StorageFixture.record("b", "new-b"), rf.fx.readAt(rf.fx.indexed("b")),
                    "the next write replays the group it could not take back");
            assertTrue(blanked(rf.fx.readAt(old)));
        }
    }

    @Test
    public void test_a_checkpoint_forces_the_unforced_index_logs_with_the_pages() throws Exception {
        final var idxlog = new File(rf.fx.paths.collectionFolder(DB, COLL), COLL + "-tag-String.idxlog");
        try (var recorder = RecordingForcer.install(true)) {
            FieldIndexLog.append(new File(idxlog.getParentFile(), COLL + "-tag-String.idx"), idxlog,
                    List.of(new IndexLogChange("alpha", List.of("a"), List.of())));
            assertFalse(recorder.forcedNames().contains(idxlog.getName()), "an index log append is not forced");
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            recorder.clear();

            new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);

            assertTrue(recorder.forcedNames().containsAll(List.of(idxlog.getName(), rf.fx.page(0).getName())),
                    recorder.forcedNames().toString());
            recorder.clear();
            rf.replace("a", "new-a", 0, PagePlacement.APPEND);
            new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);
            assertFalse(recorder.forcedNames().contains(idxlog.getName()), "a forced log is forgotten");
        }
    }

    @Test
    public void test_a_checkpoint_drops_a_group_base_even_with_no_record_after_it() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            wal.checkpoint(DB, COLL);
            wal.openGroup(DB, COLL);
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            recorder.failingFor(COLL + ".wal");
            assertFalse(wal.closeGroup(DB, COLL));
            recorder.failingFor(null);
            assertEquals(0, decoded().framesSinceCheckpoint());
            assertNotEquals(WalCodec.NO_GROUP_BASE, decoded().groupBase());

            wal.checkpoint(DB, COLL);

            assertEquals(WalCodec.NO_GROUP_BASE, decoded().groupBase(),
                    "a pk.idx rewrite after this checkpoint must not leave a stale base behind");
        }
    }

    @Test
    public void test_a_pk_index_rewrite_on_a_quiet_log_forces_nothing_in_the_log() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);
            recorder.clear();

            assertTrue(rf.fx.store.rewriteRows(DB, COLL, rows -> rows));

            assertTrue(recorder.forcedNames().stream().noneMatch(name -> name.startsWith(COLL + ".wal")),
                    recorder.forcedNames().toString());
        }
    }

    @Test
    public void test_an_append_outside_a_group_is_one_forced_record() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            final var lengthBefore = log().length();

            rf.replace("a", "new-a", 0, PagePlacement.APPEND);

            assertEquals(1L, wals(recorder));
            final var records = decoded().records();
            assertEquals(4, records.size());
            assertFalse(records.getLast().changes().isEmpty(), "the header and the payload landed together");
            assertEquals(log().length() - lengthBefore, WalCodec.encode(records.getLast()).length);
        }
    }

    @Test
    public void test_state_of_a_missing_log_is_empty() throws Exception {
        assertTrue(log().delete());

        rf.replace("a", "new-a", 0, PagePlacement.APPEND);

        final var decoded = decoded();
        assertEquals(DB, decoded.checkpoint().dbName(), "a log that is gone starts over with a checkpoint");
        assertEquals(1, decoded.records().size());
    }
}
