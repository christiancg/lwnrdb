package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.techhouse.fs.CompactionFixture.OLD_VERSION;
import static org.techhouse.fs.CompactionFixture.record;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.fs.CompactionJournal.Kind;

public class CompactionRelocationRecoveryTest {
    private static final String DB = "relocDb";
    private static final String COLL = "relocColl";

    @TempDir
    File tmp;

    private CompactionFixture fx;
    private String originalPage0;
    private String originalPage1;

    @BeforeEach
    public void setUp() throws Exception {
        fx = new CompactionFixture(tmp, DB, COLL);
        fx.seed(0, "a", "b", "c");
        fx.seed(1, "x");
        originalPage0 = fx.pageText(0);
        originalPage1 = fx.pageText(1);
    }

    private void assertTheOldCopyIsBack() throws Exception {
        assertEquals(originalPage0, fx.pageText(0));
        assertEquals(originalPage1, fx.pageText(1));
        assertEquals(0L, fx.indexed("b").getPage());
        assertEquals(OLD_VERSION, fx.indexed("b").getVersion());
        assertEquals(record("b", "old-b"), fx.readAt(fx.indexed("b")));
        assertTrue(fx.everyRowReadsItsOwnRecord());
        assertEquals(0, fx.markers().length);
    }

    private void deleteTheOldCopy() throws Exception {
        final var removed = fx.indexed("b");
        fx.shiftAndCut("b");
        fx.store.deleteIndexValue(removed);
    }

    @Test
    public void aRelocationKilledBetweenTheDeleteAndTheInsertRestoresTheOldCopy() throws Exception {
        fx.begin(Kind.RELOCATE, "b");
        deleteTheOldCopy();
        assertNull(fx.indexed("b"), "the fixture must reproduce a document on no page and in no index");

        fx.recovery.recoverAll();

        assertTheOldCopyIsBack();
    }

    @Test
    public void aRelocationKilledAfterTheTargetAppendTruncatesTheTargetAndRestoresTheOldCopy() throws Exception {
        final var marker = fx.begin(Kind.RELOCATE, "b");
        deleteTheOldCopy();
        fx.journal.retarget(marker, 1, fx.page(1).length());
        fx.appendUpdatedCopy("b", 1);

        fx.recovery.recoverAll();

        assertTheOldCopyIsBack();
    }

    @Test
    public void aRelocationKilledAfterTheTargetPkAppendRemovesTheTargetRow() throws Exception {
        final var marker = fx.begin(Kind.RELOCATE, "b");
        deleteTheOldCopy();
        fx.journal.retarget(marker, 1, fx.page(1).length());
        final var position = fx.appendUpdatedCopy("b", 1);
        fx.store.indexNewPKValue(DB, COLL, "b", position, record("b", "new-b").getBytes(StandardCharsets.UTF_8).length,
                1, CompactionFixture.NEW_VERSION);

        fx.recovery.recoverAll();

        assertTheOldCopyIsBack();
    }

    @Test
    public void aRelocationKilledBeforeTheDeleteLeavesEverythingAsItWas() throws Exception {
        fx.begin(Kind.RELOCATE, "b");

        fx.recovery.recoverAll();

        assertTheOldCopyIsBack();
    }

    @Test
    public void aRestoreOntoTheSourcePageIsUndoneToo() throws Exception {
        final var marker = fx.begin(Kind.RELOCATE, "b");
        deleteTheOldCopy();
        fx.journal.retarget(marker, 0, fx.page(0).length());
        final var position = fx.appendUpdatedCopy("b", 0);
        fx.store.indexNewPKValue(DB, COLL, "b", position, record("b", "new-b").getBytes(StandardCharsets.UTF_8).length,
                0, CompactionFixture.NEW_VERSION);

        fx.recovery.recoverAll();

        assertTheOldCopyIsBack();
    }

    @Test
    public void markersInAdminAndAdminPagesFoldersAreRecovered() throws Exception {
        for (final var target : List.of(new String[]{Globals.ADMIN_DB_NAME, "users"},
                new String[]{Globals.ADMIN_PAGES_DB_NAME, "somedb_somecoll"})) {
            final var admin = new CompactionFixture(tmp, target[0], target[1]);
            admin.seed(0, "r1", "r2", "r3");
            admin.begin(Kind.DELETE, "r2");
            admin.shiftAndCut("r2");

            admin.recovery.recoverAll();

            assertNull(admin.indexed("r2"), target[0] + "|" + target[1]);
            assertTrue(admin.everyRowReadsItsOwnRecord(), target[0] + "|" + target[1]);
            assertEquals(0, admin.markers().length);
        }
    }

    @Test
    public void aQuarantinedFolderIsSkipped() throws Exception {
        final var quarantined = new CompactionFixture(tmp, DB, COLL + Globals.QUARANTINE_INFIX + "7-1");
        quarantined.seed(0, "q1", "q2");
        quarantined.begin(Kind.DELETE, "q1");

        assertTrue(fx.journal.findMarkerFiles().isEmpty());
        assertEquals(1, quarantined.markers().length);
    }

    @Test
    public void aMarkerNameNeverMatchesAPageIndexOrBuildMarkerLister() throws Exception {
        fx.begin(Kind.DELETE, "b");

        assertEquals(List.of(0L, 1L), List.copyOf(new DocumentPageStore(fx.paths).pageFileLengths(DB, COLL).keySet()));
        assertEquals(2L, new DocumentPageStore(fx.paths).pageFileCount(DB, COLL));
        assertTrue(new IndexBuildMarkers(fx.paths).listMarked().isEmpty());
        new FieldIndexStore(fx.paths).dropIndex(DB, COLL, "b");
        new FieldIndexStore(fx.paths).dropIndex(DB, COLL, "0");
        assertEquals(1, fx.markers().length);
    }
}
