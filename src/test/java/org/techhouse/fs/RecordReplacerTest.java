package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class RecordReplacerTest {
    private static final String DB = "replacerDb";
    private static final String COLL = "replacerColl";
    private static final String SUBJECT = DB + "|" + COLL;

    @TempDir
    File tmp;

    private ReplacerFixture rf;
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private IntConsumer originalHalt;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
        originalHalt = TestUtils.getPrivateField(faultInjector, "halt", IntConsumer.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        TestUtils.setPrivateField(faultInjector, "halt", originalHalt);
    }

    private Map<String, String> rowsOf(String... ids) throws IOException {
        final var rows = new java.util.TreeMap<String, String>();
        for (final var id : ids) {
            rows.put(id, rf.fx.indexed(id).toFileEntry());
        }
        return rows;
    }

    @Test
    public void anUpdateAppendsTheNewCopyAndBlanksTheOldOne() throws Exception {
        final var old = rf.fx.indexed("b");
        final var lengthBefore = rf.fx.page(0).length();

        final var updated = rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        assertEquals(lengthBefore, updated.getPosition());
        assertEquals(StorageFixture.record("b", "new-b"), rf.valueOf(updated));
        assertEquals(updated.toFileEntry(), rf.fx.indexed("b").toFileEntry());
        final var hole = " ".repeat((int) old.getLength() - 1) + "\n";
        assertEquals(hole, rf.fx.readAt(old));
        assertEquals(0, rf.intents());
    }

    @Test
    public void noOtherRowEverMoves() throws Exception {
        final var before = rowsOf("a", "c");

        rf.replace("a", "a value long enough to change the length", 0, PagePlacement.APPEND);
        rf.replacer.delete(rf.fx.indexed("b"));

        assertEquals(before.get("c"), rowsOf("c").get("c"));
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void aDeleteBlanksTheRecordAndAppendsADeleteRow() throws Exception {
        final var old = rf.fx.indexed("b");

        rf.replacer.delete(old);

        assertNull(rf.fx.indexed("b"));
        assertTrue(rf.fx.readAt(old).isBlank());
        assertEquals(PkIndexEntry.toDeleteRow("b"),
                Files.readAllLines(rf.fx.paths.pkIndexFile(DB, COLL).toPath()).getLast());
        assertEquals(0, rf.intents());
    }

    @Test
    public void aRelocationAppendsToTheTargetAndBlanksTheSource() throws Exception {
        final var old = rf.fx.indexed("b");

        final var moved = rf.replace("b", "moved", 1, null);

        assertEquals(1L, moved.getPage());
        assertEquals(0L, moved.getPosition());
        assertEquals(StorageFixture.record("b", "moved"), rf.valueOf(moved));
        assertTrue(rf.fx.readAt(old).isBlank());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void aFailureBeforeTheCommitRemovesItsOwnCopy() throws Exception {
        final var page = Files.readAllBytes(rf.fx.page(0).toPath());
        final var pkFile = rf.fx.paths.pkIndexFile(DB, COLL);
        assertTrue(pkFile.setWritable(false));
        try {
            assertThrows(IOException.class, () -> rf.replace("b", "new-b", 0, PagePlacement.APPEND));
            assertThrows(IOException.class, () -> rf.replacer.delete(rf.fx.indexed("c")));
        } finally {
            assertTrue(pkFile.setWritable(true));
        }

        assertArrayEquals(page, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertEquals(0, rf.intents(), "a write that failed before its commit ends its own intent");
    }

    @Test
    public void anEntryPastItsPageIsRefusedBeforeAnythingIsWritten() throws Exception {
        final var page = Files.readAllBytes(rf.fx.page(0).toPath());
        final var b = rf.fx.indexed("b");
        final var dangling = new PkIndexEntry(DB, COLL, "b", page.length - 2, b.getLength(), 0, b.getVersion());

        assertThrows(IOException.class, () -> rf.replacer.replace(rf.entry("b", "x", 0), ReplacerFixture.line("b", "x"),
                dangling, 0, PagePlacement.APPEND, null));
        assertThrows(IOException.class, () -> rf.replacer.delete(dangling));

        assertArrayEquals(page, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertEquals(0, rf.intents());
    }

    @Test
    public void aConcurrentInsertCannotLandBetweenAnUncommittedCopyAndItsCommit() throws Exception {
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        faultInjector.arm(FaultPoint.PAGE_COPY_APPENDED, FaultAction.PAUSE, SUBJECT);
        final var update = CompletableFuture.supplyAsync(() -> {
            try {
                return rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(faultInjector.awaitReached(FaultPoint.PAGE_COPY_APPENDED, 5_000));
        final var insert = CompletableFuture.supplyAsync(() -> {
            try {
                return rf.appender.insert(rf.entry("d", "old-d", 0), ReplacerFixture.line("d", "old-d"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThrows(java.util.concurrent.TimeoutException.class, () -> insert.get(300, TimeUnit.MILLISECONDS),
                "the target page's write lock is held from the append through the commit");
        faultInjector.release(FaultPoint.PAGE_COPY_APPENDED);

        final var updated = update.get(5, TimeUnit.SECONDS);
        final var inserted = insert.get(5, TimeUnit.SECONDS);
        assertTrue(inserted.getPosition() > updated.getPosition());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    private List<String> haltRecording(FaultPoint point, Observation observation) throws Exception {
        final var observed = new ArrayList<String>();
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        TestUtils.setPrivateField(faultInjector, "halt",
                (IntConsumer) _ -> observed.add(observation.describeQuietly()));
        faultInjector.arm(point, FaultAction.HALT, SUBJECT);
        return observed;
    }

    @FunctionalInterface
    private interface Observation {
        String describe() throws IOException;

        default String describeQuietly() {
            try {
                return describe();
            } catch (IOException e) {
                return e.toString();
            }
        }
    }

    @Test
    public void pageCopyAppendedFiresAfterTheAppendAndBeforeTheCommit() throws Exception {
        final var lengthBefore = rf.fx.page(0).length();
        final var oldRow = rf.fx.indexed("b").toFileEntry();
        final var observed = haltRecording(FaultPoint.PAGE_COPY_APPENDED,
                () -> "grown=" + (rf.fx.page(0).length() > lengthBefore) + " committed="
                        + !rf.fx.indexed("b").toFileEntry().equals(oldRow));

        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        assertEquals(List.of("grown=true committed=false"), observed);
    }

    @Test
    public void pkRowCommittedFiresAfterTheCommitAndBeforeTheBlank() throws Exception {
        final var old = rf.fx.indexed("b");
        final var observed = haltRecording(FaultPoint.PK_ROW_COMMITTED,
                () -> "committed=" + (rf.fx.indexed("b").getVersion() == StorageFixture.NEW_VERSION) + " blanked="
                        + rf.fx.readAt(old).isBlank());

        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        assertEquals(List.of("committed=true blanked=false"), observed);
    }

    @Test
    public void pkRowCommittedFiresForADeleteBeforeItsBlank() throws Exception {
        final var old = rf.fx.indexed("b");
        final var observed = haltRecording(FaultPoint.PK_ROW_COMMITTED,
                () -> "deleted=" + (rf.fx.indexed("b") == null) + " blanked=" + rf.fx.readAt(old).isBlank());

        rf.replacer.delete(old);

        assertEquals(List.of("deleted=true blanked=false"), observed);
    }

    @Test
    public void pageVacuumRenamedFiresAfterTheMoveAndBeforeTheRows() throws Exception {
        rf.replacer.delete(rf.fx.indexed("a"));
        final var lengthBefore = rf.fx.page(0).length();
        final var cRowBefore = rf.fx.indexed("c").toFileEntry();
        final var observed = haltRecording(FaultPoint.PAGE_VACUUM_RENAMED,
                () -> "compacted=" + (rf.fx.page(0).length() < lengthBefore) + " rowsMoved="
                        + !rf.fx.indexed("c").toFileEntry().equals(cRowBefore));

        rf.vacuum.vacuum(DB, COLL, 0, PageRows.of(rf.fx.store, null, DB, COLL, 0));

        assertEquals(List.of("compacted=true rowsMoved=false"), observed);
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
        assertFalse(rf.fx.paths.vacuumFile(DB, COLL, 0).exists());
    }
}
