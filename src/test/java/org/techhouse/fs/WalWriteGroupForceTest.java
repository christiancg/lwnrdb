package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WalWriteGroupForceTest {
    private static final String DB = "forceDb";
    private static final String COLL = "forceColl";
    private static final String WAL = COLL + ".wal";

    @TempDir
    File tmp;

    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
    }

    private static long wals(RecordingForcer recorder) {
        return recorder.forcedNames().stream().filter(WAL::equals).count();
    }

    private int records() throws IOException {
        return WalCodec.decodeAll(Files.readAllBytes(rf.fx.paths.writeAheadLog(DB, COLL).toPath())).records().size();
    }

    @Test
    public void test_force_group_forces_once_and_defers_blanks() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            assertTrue(wal.forceGroup(DB, COLL), "no open group has nothing to force");
            final var old = rf.fx.indexed("b");
            wal.openGroup(DB, COLL);
            assertTrue(wal.forceGroup(DB, COLL), "an empty group has nothing to force");
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            final var beforeForce = wals(recorder);

            assertTrue(wal.forceGroup(DB, COLL));

            assertEquals(beforeForce + 1L, wals(recorder));
            assertEquals(StorageFixture.record("b", "old-b"), rf.fx.readAt(old), "the blanks wait for the settle");
            assertTrue(wal.forceGroup(DB, COLL), "a forced group is not forced again");
            assertEquals(beforeForce + 1L, wals(recorder));
            assertTrue(wal.closeGroup(DB, COLL));
            assertTrue(rf.fx.readAt(old).isBlank());
        }
    }

    @Test
    public void test_a_failed_force_marks_the_group_failed_without_cutting_files() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            final var page = rf.fx.page(0);
            final var pk = rf.fx.paths.pkIndexFile(DB, COLL);
            final var pageBefore = page.length();
            final var pkBefore = pk.length();
            final var recordsBefore = records();
            wal.openGroup(DB, COLL);
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            final var pageGrown = page.length();
            final var pkGrown = pk.length();
            recorder.failingFor(WAL);

            assertFalse(wal.forceGroup(DB, COLL));

            final var group = wal.openGroupOf(DB, COLL);
            assertNotNull(group);
            assertTrue(group.failed());
            assertEquals(pageGrown, page.length(), "the take-back waits for the exclusive lock");
            assertEquals(pkGrown, pk.length());
            recorder.failingFor(null);
            assertFalse(wal.closeGroup(DB, COLL));
            assertEquals(pageBefore, page.length());
            assertEquals(pkBefore, pk.length());
            assertEquals(recordsBefore, records());
        }
    }

    @Test
    public void test_settle_after_a_forced_group_does_not_force_again() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            wal.openGroup(DB, COLL);
            rf.replace("a", "new-a", 0, PagePlacement.APPEND);
            rf.replacer.delete(rf.fx.indexed("c"));
            assertTrue(wal.forceGroup(DB, COLL));
            final var forced = wals(recorder);

            assertTrue(wal.closeGroup(DB, COLL));

            assertEquals(forced, wals(recorder));
            assertEquals(StorageFixture.record("a", "new-a"), rf.fx.readAt(rf.fx.indexed("a")));
        }
    }

    @Test
    public void test_a_failed_force_is_never_retried() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            final var wal = new WriteAheadLog(rf.fx.paths);
            final var old = rf.fx.indexed("b");
            wal.openGroup(DB, COLL);
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            recorder.failingFor(WAL);
            assertFalse(wal.forceGroup(DB, COLL));
            recorder.failingFor(null);
            final var forced = wals(recorder);

            assertFalse(wal.forceGroup(DB, COLL), "a group whose fsync failed stays failed");
            assertEquals(forced, wals(recorder), "the failed group was not forced again");
            assertFalse(wal.closeGroup(DB, COLL), "the settle takes the group back instead of forcing it");

            assertEquals(StorageFixture.record("b", "old-b"), rf.fx.readAt(old));
            assertEquals(old, rf.fx.indexed("b"));
        }
    }
}
