package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestUtils;

public class PageVacuumTest {
    private static final String DB = "vacuumDb";
    private static final String COLL = "vacuumColl";

    @TempDir
    File tmp;

    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c", "d");
        rf.replacer.delete(rf.fx.indexed("b"));
    }

    private List<PkIndexEntry> pageRows() throws IOException {
        return PageRows.of(rf.fx.store, null, DB, COLL, 0);
    }

    @Test
    public void liveRecordsAreRewrittenInPositionOrderWithoutHoles() throws Exception {
        final var a = rf.fx.indexed("a");
        final var c = rf.fx.indexed("c");
        final var d = rf.fx.indexed("d");

        final var vacuumed = rf.vacuum.vacuum(DB, COLL, 0, pageRows());

        assertEquals(StorageFixture.record("a", "old-a") + StorageFixture.record("c", "old-c")
                + StorageFixture.record("d", "old-d"), rf.fx.firstPageText());
        assertEquals(Map.of("c", new VacuumedPage.Move(c.getPosition(), a.getLength()), "d",
                new VacuumedPage.Move(d.getPosition(), a.getLength() + c.getLength())), vacuumed.moves());
        assertEquals(List.of(vacuumed), rf.vacuumed, "the listener hears of every vacuum");
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
        assertEquals(a.toFileEntry(), rf.fx.indexed("a").toFileEntry(), "a record that did not move keeps its row");
        assertEquals(0, rf.intents());
    }

    @Test
    public void repositionMovesOnlyTheRowsTheVacuumMoved() throws Exception {
        final var rows = List.of(rf.fx.indexed("a"), rf.fx.indexed("c"), rf.fx.indexed("d"),
                new PkIndexEntry(DB, COLL, "c", 999, 10, 1));
        final var cPosition = rows.get(1).getPosition();

        rf.vacuum.vacuum(DB, COLL, 0, pageRows()).reposition(rows);

        assertEquals(0L, rows.get(0).getPosition());
        assertTrue(rows.get(1).getPosition() < cPosition);
        assertEquals(999L, rows.get(3).getPosition(), "a row on another page is left alone");
    }

    @Test
    public void anUnindexedNonBlankLineAbortsWithThePageByteIdentical() throws Exception {
        Files.writeString(rf.fx.page(0).toPath(), StorageFixture.record("stranger", "x"), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        final var before = Files.readAllBytes(rf.fx.page(0).toPath());

        assertThrows(IOException.class, () -> rf.vacuum.vacuum(DB, COLL, 0, pageRows()));

        assertArrayEquals(before, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertEquals(0, rf.intents());
        assertTrue(rf.vacuumed.isEmpty());
    }

    @Test
    public void anIdMismatchAborts() throws Exception {
        final var rows = pageRows();
        final var c = rows.stream().filter(row -> row.getValue().equals("c")).findFirst().orElseThrow();
        c.setValue("not-c");
        final var before = Files.readAllBytes(rf.fx.page(0).toPath());

        assertThrows(IOException.class, () -> rf.vacuum.vacuum(DB, COLL, 0, rows));

        assertArrayEquals(before, Files.readAllBytes(rf.fx.page(0).toPath()));
    }

    @Test
    public void aRowWhoseRecordIsMissingAborts() throws Exception {
        final var rows = new java.util.ArrayList<>(pageRows());
        rows.add(new PkIndexEntry(DB, COLL, "ghost", 1, 5, 0));

        assertThrows(IOException.class, () -> rf.vacuum.vacuum(DB, COLL, 0, rows));
    }

    @Test
    public void theRewrittenPageIsOwnerOnlyAndNoTempFileIsLeft() throws Exception {
        rf.vacuum.vacuum(DB, COLL, 0, pageRows());

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(rf.fx.page(0).toPath()));
        assertFalse(rf.fx.paths.vacuumFile(DB, COLL, 0).exists());
    }

    @Test
    public void aPageOfOnlyHolesTruncatesWithoutAnIntentOrTempFile() throws Exception {
        for (final var id : List.of("a", "c", "d")) {
            rf.replacer.delete(rf.fx.indexed(id));
        }
        final var intentLog = rf.fx.paths.collectionFolder(DB, COLL).toPath()
                .resolve(COLL + org.techhouse.config.Globals.INTENT_LOG_EXTENSION);
        final var intentBytes = Files.exists(intentLog) ? Files.size(intentLog) : 0L;

        final var vacuumed = rf.vacuum.vacuum(DB, COLL, 0, pageRows());

        assertEquals(0L, rf.fx.page(0).length());
        assertTrue(rf.fx.page(0).exists(), "the page file stays, empty");
        assertEquals(Map.of(), vacuumed.moves());
        assertEquals(List.of(vacuumed), rf.vacuumed, "the listener still hears of it");
        assertEquals(intentBytes, Files.exists(intentLog) ? Files.size(intentLog) : 0L, "no intent is written");
        assertFalse(rf.fx.paths.vacuumFile(DB, COLL, 0).exists());
    }

    @Test
    public void theDeletedIdIsExcludedFromTheRowsItVerifies() throws Exception {
        final var c = rf.fx.indexed("c");
        final var rows = rf.fx.indexedRows();
        rf.replacer.delete(c);

        rf.vacuum.vacuumAfterWrite(DB, COLL, 0, rows, new PageOccupancy(0L, 9L), WrittenRows.deleted("c"));

        assertEquals(StorageFixture.record("a", "old-a") + StorageFixture.record("d", "old-d"), rf.fx.firstPageText());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void vacuumIfNeededLeavesAPageWithRoomAlone() throws Exception {
        assertNull(rf.vacuum.vacuumIfNeeded(DB, COLL, 0, 10, null, null));
        assertNull(rf.vacuum.vacuumIfNeeded(DB, COLL, 0, Long.MAX_VALUE / 2, PagePlacement.APPEND, null));
        assertTrue(rf.vacuumed.isEmpty());
    }

    @Test
    public void anExplicitVacuumPlacementVacuums() throws Exception {
        final var vacuumed = rf.vacuum.vacuumIfNeeded(DB, COLL, 0, 10, PagePlacement.VACUUM_THEN_APPEND, null);

        assertNotNull(vacuumed);
        assertEquals(0L, vacuumed.page());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    private static PageOccupancy occupancyOf(List<PkIndexEntry> pageRows) {
        return new PageOccupancy(PageRows.liveBytes(pageRows), pageRows.size());
    }

    private static Set<String> entriesOf(List<PkIndexEntry> rows) {
        final var entries = new HashSet<String>();
        rows.forEach(row -> entries.add(row.toFileEntry()));
        return entries;
    }

    @Test
    public void rowsFromLinesEqualTheWalkOnRandomPages() throws Exception {
        for (var seed = 0; seed < 20; seed++) {
            final var random = new Random(seed);
            final var fixture = new ReplacerFixture(new File(tmp, "random-" + seed), DB, COLL);
            final var ids = new ArrayList<String>();
            for (var i = 0; i < 30; i++) {
                final var id = String.format("r%02d", i);
                fixture.fx.seed(i % 2, id);
                ids.add(id);
            }
            for (final var id : ids) {
                final var roll = random.nextInt(3);
                final var row = fixture.fx.indexed(id);
                if (roll == 0) {
                    fixture.replacer.delete(row);
                } else if (roll == 1) {
                    fixture.replace(id, "v".repeat(1 + random.nextInt(20)), row.getPage(), PagePlacement.APPEND);
                }
            }
            final var rows = fixture.fx.indexedRows();
            for (final var page : List.of(0L, 1L)) {
                final var walk = PageRows.of(fixture.fx.store, rows, DB, COLL, page);
                final var lines = PageLines.recordsOf(fixture.fx.page(page), 0L);

                final var found = PageVacuum.rowsOfLines(lines, rows, page, occupancyOf(walk));

                assertNotNull(found, "seed " + seed + " page " + page);
                assertEquals(entriesOf(walk), entriesOf(found), "seed " + seed + " page " + page);
            }
        }
    }

    @Test
    public void theRowsOfAKnownPageAreFoundWithoutIteratingTheResidentRows() throws Exception {
        final var rows = new NeverIteratedRows(rf.fx.indexedRows());

        rf.vacuum.vacuumAfterWrite(DB, COLL, 0, rows, occupancyOf(pageRows()), WrittenRows.NONE);

        assertEquals(StorageFixture.record("a", "old-a") + StorageFixture.record("c", "old-c")
                + StorageFixture.record("d", "old-d"), rf.fx.firstPageText());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void aGroupOfAppendedRowsVacuumsAKnownPageWithoutAWalk() throws Exception {
        final var configuration = org.techhouse.config.Configuration.getInstance();
        final var maxPageSize = configuration.getMaxPageSize();
        rf.replacer.delete(rf.fx.indexed("c"));
        final var rows = new NeverIteratedRows(rf.fx.indexedRows());
        final var group = 3L * ReplacerFixture.line("n", "new-n").length;
        final var occupancy = occupancyOf(rf.fx.indexedRows());
        TestUtils.setPrivateField(configuration, "maxPageSize", rf.fx.page(0).length() + 4);
        try {
            final var vacuumed = rf.vacuum.vacuumIfNeeded(DB, COLL, 0, group, null, rows, _ -> occupancy);

            assertNotNull(vacuumed, "the page lacks room for the group and holds a quarter page dead");
            assertEquals(StorageFixture.record("a", "old-a") + StorageFixture.record("d", "old-d"),
                    rf.fx.firstPageText());
            assertTrue(rf.fx.everyRowReadsItsOwnRecord());
        } finally {
            TestUtils.setPrivateField(configuration, "maxPageSize", maxPageSize);
        }
    }

    @Test
    public void aRowNamingAHoleAbortsThroughTheCountFallback() throws Exception {
        final var hole = new PkIndexEntry(DB, COLL, "b", rf.fx.indexed("a").getLength(),
                rf.fx.indexed("c").getPosition() - rf.fx.indexed("a").getLength(), 0, StorageFixture.OLD_VERSION);
        final var rows = new ArrayList<>(rf.fx.indexedRows());
        rows.add(1, hole);
        final var before = Files.readAllBytes(rf.fx.page(0).toPath());

        assertThrows(IOException.class, () -> rf.vacuum.vacuumAfterWrite(DB, COLL, 0, rows,
                new PageOccupancy(0L, rows.size()), WrittenRows.NONE));

        assertArrayEquals(before, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertEquals(0, rf.intents());
    }

    @Test
    public void aRowNamingAHoleAbortsWhenTheRowsArePointScanned() throws Exception {
        rf.fx.store.appendRows(DB, COLL, List.of(new PkIndexEntry(DB, COLL, "b", rf.fx.indexed("a").getLength(),
                rf.fx.indexed("c").getPosition() - rf.fx.indexed("a").getLength(), 0, StorageFixture.NEW_VERSION)));
        final var before = Files.readAllBytes(rf.fx.page(0).toPath());

        assertThrows(
                IOException.class, () -> rf.vacuum.vacuumAfterWrite(DB, COLL, 0, PointScannedRows.INSTANCE,
                        new PageOccupancy(0L, 4L), WrittenRows.NONE),
                "the walk sees the row whose id has no line on the page");

        assertArrayEquals(before, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertEquals(0, rf.intents());
    }

    @Test
    public void anEmptiedPageIsTruncatedOnlyWhenTheWalkFindsNoRowOnIt() throws Exception {
        final var stale = rf.fx.indexedRows();
        for (final var id : List.of("a", "c", "d")) {
            rf.replacer.delete(rf.fx.indexed(id));
        }
        final var before = Files.readAllBytes(rf.fx.page(0).toPath());

        assertThrows(IOException.class,
                () -> rf.vacuum.vacuumAfterWrite(DB, COLL, 0, stale, new PageOccupancy(0L, 0L), WrittenRows.NONE),
                "a zero count never stands in for the walk before bytes are cut");

        assertArrayEquals(before, Files.readAllBytes(rf.fx.page(0).toPath()));
    }

    @Test
    public void aStaleCountFallsBackToTheExactWalk() throws Exception {
        final var rows = rf.fx.indexedRows();

        rf.vacuum.vacuumAfterWrite(DB, COLL, 0, rows, new PageOccupancy(0L, 7L), WrittenRows.NONE);

        assertEquals(StorageFixture.record("a", "old-a") + StorageFixture.record("c", "old-c")
                + StorageFixture.record("d", "old-d"), rf.fx.firstPageText());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void aRepositionByLookupMovesOnlyRowsStillAtTheirOldOffset() {
        final var moved = new PkIndexEntry(DB, COLL, "c", 50, 10, 0);
        final var replaced = new PkIndexEntry(DB, COLL, "d", 80, 10, 0);
        final var elsewhere = new PkIndexEntry(DB, COLL, "e", 90, 10, 1);
        final var rows = Map.of("c", moved, "d", replaced, "e", elsewhere);
        final var vacuumed = new VacuumedPage(DB, COLL, 0, Map.of("c", new VacuumedPage.Move(50, 20), "d",
                new VacuumedPage.Move(60, 30), "e", new VacuumedPage.Move(90, 40), "x", new VacuumedPage.Move(1, 0)));

        vacuumed.reposition(rows::get);

        assertEquals(20L, moved.getPosition());
        assertEquals(80L, replaced.getPosition(), "a row no longer at the move's origin is left alone");
        assertEquals(90L, elsewhere.getPosition(), "a row on another page is left alone");
    }
}
