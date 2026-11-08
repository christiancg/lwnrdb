package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ex.LoggedWriteNotAppliedException;
import org.techhouse.test.TestUtils;

public class WalPlanDeferredApplyTest {
    private static final String DB = "deferDb";
    private static final String COLL = "deferColl";
    private static final String WAL = COLL + ".wal";
    private static final String ID = DB + "|" + COLL;

    @TempDir
    File tmp;

    private ReplacerFixture rf;
    private RecordingForcer recorder;
    private final List<String> events = new ArrayList<>();
    private DeferredApplyListener installed;

    private final DeferredApplyListener recording = new DeferredApplyListener() {
        @Override
        public long replaying(String dbName, String collName, List<PkIndexEntry> rows) {
            events.add("replaying " + idsOf(rows));
            return 7L;
        }

        @Override
        public void replayed(String dbName, String collName, List<PkIndexEntry> rows, long generation) {
            events.add("replayed " + idsOf(rows) + " " + generation);
        }

        @Override
        public void notReplayed(String dbName, String collName, List<PkIndexEntry> rows, long generation) {
            events.add("not replayed " + generation);
        }
    };

    @BeforeEach
    public void setUp() throws Exception {
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
        recorder = RecordingForcer.install(true);
        installed = TestUtils.getPrivateStaticField(DeferredApplies.class, "listener", DeferredApplyListener.class);
        DeferredApplies.listenWith(recording);
    }

    @AfterEach
    public void tearDown() throws Exception {
        assertTrue(rf.fx.page(0).setWritable(true));
        DeferredApplies.listenWith(installed);
        DeferredApplies.clear(DB, COLL);
        recorder.close();
    }

    private static List<String> idsOf(List<PkIndexEntry> rows) {
        return rows.stream().map(PkIndexEntry::getValue).toList();
    }

    private void failApplyAndTakeBack() {
        recorder.clear();
        recorder.refusingWhen((forced, name) -> name.equals(WAL) && forced.contains(WAL));
        assertTrue(rf.fx.page(0).setWritable(false));
    }

    private void freeTheDisk() {
        recorder.refusingWhen((_, _) -> false);
        assertTrue(rf.fx.page(0).setWritable(true));
    }

    @Test
    public void aForcedWriteWhoseApplyAndTakeBackFailThrowsLoggedNotApplied() {
        failApplyAndTakeBack();

        assertThrows(LoggedWriteNotAppliedException.class, () -> rf.replace("b", "new-b", 0, PagePlacement.APPEND));
        assertTrue(DeferredApplies.pending(ID));
    }

    @Test
    public void aWriteTakenBackIsAPlainFailure() {
        assertTrue(rf.fx.page(0).setWritable(false));

        final var failure = assertThrows(IOException.class, () -> rf.replace("b", "new-b", 0, PagePlacement.APPEND));

        assertFalse(failure instanceof LoggedWriteNotAppliedException);
        assertFalse(DeferredApplies.pending(ID));
    }

    @Test
    public void theDeferredApplyTellsItsListenerAroundTheReplay() throws Exception {
        failApplyAndTakeBack();
        assertThrows(IOException.class, () -> rf.replace("b", "new-b", 0, PagePlacement.APPEND));
        freeTheDisk();

        assertTrue(new WriteAheadLog(rf.fx.paths).requireApplied(DB, COLL));

        assertEquals(2, events.size(), events.toString());
        assertTrue(events.get(0).startsWith("replaying ") && events.get(0).contains("b"), events.toString());
        assertTrue(events.get(1).startsWith("replayed ") && events.get(1).endsWith(" 7"), events.toString());
        assertEquals(StorageFixture.record("b", "new-b"), rf.fx.readAt(rf.fx.indexed("b")));
        assertFalse(DeferredApplies.pending(ID));
    }

    @Test
    public void aWriterThatFindsTheLogUnappliedIsRefusedAfterApplyingIt() throws Exception {
        failApplyAndTakeBack();
        assertThrows(IOException.class, () -> rf.replace("b", "new-b", 0, PagePlacement.APPEND));
        freeTheDisk();
        final var staleA = rf.fx.indexed("a");

        final var refused = assertThrows(IOException.class, () -> rf.replace("a", "new-a", 0, PagePlacement.APPEND));

        assertFalse(refused instanceof LoggedWriteNotAppliedException);
        assertEquals(StorageFixture.record("a", "old-a"), rf.fx.readAt(staleA));
        assertEquals(StorageFixture.record("b", "new-b"), rf.fx.readAt(rf.fx.indexed("b")));
        assertEquals(StorageFixture.record("a", "new-a"),
                rf.fx.readAt(rf.replace("a", "new-a", 0, PagePlacement.APPEND)));
    }

    @Test
    public void aFailedReplayClearsWhatItMarked() {
        failApplyAndTakeBack();
        assertThrows(IOException.class, () -> rf.replace("b", "new-b", 0, PagePlacement.APPEND));
        recorder.refusingWhen((_, _) -> false);

        assertThrows(IOException.class, () -> new WriteAheadLog(rf.fx.paths).requireApplied(DB, COLL));

        assertEquals("not replayed 7", events.getLast());
        assertTrue(DeferredApplies.pending(ID));
    }
}
