package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.techhouse.fs.CompactionFixture.NEW_VERSION;
import static org.techhouse.fs.CompactionFixture.OLD_VERSION;
import static org.techhouse.fs.CompactionFixture.record;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.CompactionJournal.Kind;

public class CompactionJournalTest {
    private static final String DB = "journalDb";
    private static final String COLL = "journalColl";

    @TempDir
    File tmp;

    private CompactionFixture fx;
    private String original;

    @BeforeEach
    public void setUp() throws Exception {
        fx = new CompactionFixture(tmp, DB, COLL);
        fx.seed(0, "a", "b", "c", "d", "e");
        original = fx.pageText(0);
    }

    private String originalWithoutB() {
        return original.replace(record("b", "old-b"), "");
    }

    @Test
    public void aDeleteKilledAfterTheShiftIsCompletedAndEveryLaterRecordIsReachable() throws Exception {
        fx.begin(Kind.DELETE, "b");
        fx.shiftAndCut("b");

        final var outcome = fx.recovery.recoverAll();

        assertEquals(List.of(), outcome.refused());
        assertEquals(List.of("b"), outcome.completedDeletes().stream().map(PkIndexEntry::getValue).toList());
        assertEquals(originalWithoutB(), fx.pageText(0));
        assertNull(fx.indexed("b"));
        assertEquals(List.of("a", "c", "d", "e"), fx.indexedRows().stream().map(PkIndexEntry::getValue).toList());
        assertTrue(fx.everyRowReadsItsOwnRecord());
        assertEquals(0, fx.markers().length);
    }

    @Test
    public void aDeleteKilledBeforeTheShiftIsCompleted() throws Exception {
        fx.begin(Kind.DELETE, "b");

        fx.recovery.recoverAll();

        assertEquals(originalWithoutB(), fx.pageText(0));
        assertNull(fx.indexed("b"));
        assertTrue(fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void aDeleteKilledAfterItsPkWriteOnlyDropsTheMarker() throws Exception {
        final var removed = fx.indexed("b");
        fx.begin(Kind.DELETE, "b");
        fx.shiftAndCut("b");
        fx.store.deleteIndexValue(removed);
        final var rowsAfterTheDelete = fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList();

        final var outcome = fx.recovery.recoverAll();

        assertEquals(List.of("b"), outcome.completedDeletes().stream().map(PkIndexEntry::getValue).toList(),
                "the kill still landed before the pending mark, so the index cleanup is still owed");

        assertEquals(originalWithoutB(), fx.pageText(0));
        assertEquals(rowsAfterTheDelete, fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList());
        assertEquals(0, fx.markers().length);
    }

    @Test
    public void anUpdateKilledAfterTheShiftIsUndoneToTheAcknowledgedVersion() throws Exception {
        final var before = fx.indexed("b");
        fx.begin(Kind.UPDATE, "b");
        fx.shiftAndCut("b");
        fx.appendUpdatedCopy("b", 0);

        assertEquals(List.of(), fx.recovery.recoverAll().completedDeletes());

        assertEquals(original, fx.pageText(0));
        assertEquals(before.toFileEntry(), fx.indexed("b").toFileEntry());
        assertEquals(record("b", "old-b"), fx.readAt(fx.indexed("b")));
        assertTrue(fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void anUpdateKilledAfterItsPkWriteIsUndoneOnPageAndIndexTogether() throws Exception {
        final var rowsBefore = fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList();
        fx.begin(Kind.UPDATE, "b");
        fx.shiftAndCut("b");
        final var copyAt = fx.appendUpdatedCopy("b", 0);
        fx.indexUpdatedCopy("b", copyAt);
        assertTrue(fx.everyRowReadsItsOwnRecord(), "the fixture must reproduce a fully applied update");

        fx.recovery.recoverAll();

        assertEquals(original, fx.pageText(0));
        assertEquals(rowsBefore, fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList());
        assertEquals(OLD_VERSION, fx.indexed("b").getVersion());
    }

    @Test
    public void anUpdateOfTheLastRecordOnItsPageIsUndone() throws Exception {
        final var rowsBefore = fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList();
        fx.begin(Kind.UPDATE, "e");
        fx.shiftAndCut("e");
        fx.indexUpdatedCopy("e", fx.appendUpdatedCopy("e", 0));

        fx.recovery.recoverAll();

        assertEquals(original, fx.pageText(0));
        assertEquals(rowsBefore, fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList());
    }

    @Test
    public void aTornMarkerIsDiscardedWithoutTouchingThePage() throws Exception {
        fx.begin(Kind.DELETE, "b");
        final var marker = fx.markers()[0];
        final var bytes = Files.readAllBytes(marker.toPath());
        Files.write(marker.toPath(), Arrays.copyOf(bytes, bytes.length - 3));

        fx.recovery.recoverAll();

        assertEquals(original, fx.pageText(0));
        assertNotNull(fx.indexed("b"));
        assertEquals(0, fx.markers().length);
    }

    @Test
    public void aMarkerWithAMangledHeaderIsDiscarded() throws Exception {
        fx.begin(Kind.UPDATE, "b");
        Files.writeString(fx.markers()[0].toPath(), "UPDATE\u001Fnot-enough-fields\n", StandardCharsets.UTF_8);

        fx.recovery.recoverAll();

        assertEquals(original, fx.pageText(0));
        assertEquals(0, fx.markers().length);
    }

    @Test
    public void recoveryIsIdempotentWhenRunTwice() throws Exception {
        fx.begin(Kind.DELETE, "c");
        fx.shiftAndCut("c");
        final var markerBytes = Files.readAllBytes(fx.markers()[0].toPath());
        final var markerPath = fx.markers()[0].toPath();
        fx.recovery.recoverAll();
        final var pageAfterFirst = fx.pageText(0);
        final var rowsAfterFirst = fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList();

        Files.write(markerPath, markerBytes);
        fx.recovery.recoverAll();

        assertEquals(pageAfterFirst, fx.pageText(0));
        assertEquals(rowsAfterFirst, fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList());
    }

    @Test
    public void anUnrecognisedPkIndexLeavesPageIndexAndMarkerAlone() throws Exception {
        fx.begin(Kind.DELETE, "b");
        fx.shiftAndCut("b");
        final var shifted = fx.pageText(0);
        final var pkFile = fx.paths.pkIndexFile(DB, COLL);
        Files.writeString(pkFile.toPath(), "garbage\nmore garbage\n", StandardCharsets.UTF_8);

        final var outcome = fx.recovery.recoverAll();

        assertEquals(1, outcome.refused().size());
        assertEquals(List.of(), outcome.completedDeletes(), "a refused delete was not completed");
        assertEquals(shifted, fx.pageText(0));
        assertEquals("garbage\nmore garbage\n", Files.readString(pkFile.toPath(), StandardCharsets.UTF_8));
        assertEquals(1, fx.markers().length);
    }

    @Test
    public void aMarkerWhosePageIsGoneIsDropped() throws Exception {
        fx.begin(Kind.DELETE, "b");
        assertTrue(fx.page(0).delete());

        assertEquals(List.of(), fx.recovery.recoverAll().refused());

        assertEquals(0, fx.markers().length);
    }

    @Test
    public void rewriteRowsRefusesAFileWithNoParsedLine() throws Exception {
        final var pkFile = fx.paths.pkIndexFile(DB, COLL);
        Files.writeString(pkFile.toPath(), "garbage\n", StandardCharsets.UTF_8);

        assertFalse(fx.store.rewriteRows(DB, COLL, rows -> rows));

        assertEquals("garbage\n", Files.readString(pkFile.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void rewriteRowsWritesTheChangedRowsSortedById() throws Exception {
        assertTrue(fx.store.rewriteRows(DB, COLL, rows -> {
            rows.removeIf(row -> row.getValue().equals("c"));
            return rows;
        }));

        assertEquals(List.of("a", "b", "d", "e"), fx.indexedRows().stream().map(PkIndexEntry::getValue).toList());
    }

    private CompactionJournal.Marker applyAnUpdateOfB() throws IOException {
        final var marker = fx.begin(Kind.UPDATE, "b");
        fx.shiftAndCut("b");
        fx.indexUpdatedCopy("b", fx.appendUpdatedCopy("b", 0));
        return marker;
    }

    private void endWithAFailingDelete(CompactionJournal.Marker marker) {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(any(Path.class))).thenThrow(new IOException("unlink refused"));
            assertDoesNotThrow(() -> fx.journal.end(marker));
        }
    }

    @Test
    public void aMarkerThatCannotBeDeletedIsEmptiedAndNeverRevertsLaterWrites() throws Exception {
        final var marker = applyAnUpdateOfB();
        endWithAFailingDelete(marker);
        fx.seed(0, "f");
        final var pageBeforeRestart = fx.pageText(0);

        assertEquals(List.of(), fx.recovery.recoverAll().refused());

        assertEquals(pageBeforeRestart, fx.pageText(0), "a completed compaction must not be replayed at startup");
        assertEquals(NEW_VERSION, fx.indexed("b").getVersion(), "the acknowledged update must survive");
        assertNotNull(fx.indexed("f"));
        assertTrue(fx.everyRowReadsItsOwnRecord());
        assertEquals(0, fx.markers().length);
    }

    @Test
    public void aMarkerThatCanBeNeitherDeletedNorEmptiedIsLeftAsItWas() throws Exception {
        final var marker = applyAnUpdateOfB();
        final var file = fx.journal.markerFile(marker);
        final var bytes = Files.readAllBytes(file.toPath());
        assertTrue(file.setWritable(false));
        try {
            endWithAFailingDelete(marker);
        } finally {
            assertTrue(file.setWritable(true));
        }

        assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
    }
}
