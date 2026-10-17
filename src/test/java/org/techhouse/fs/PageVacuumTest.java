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
import java.util.List;
import java.util.Map;
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
        assertEquals(0, rf.intents().length);
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
        assertEquals(0, rf.intents().length);
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
    public void aPageOfOnlyHolesVacuumsToAnEmptyFile() throws Exception {
        for (final var id : List.of("a", "c", "d")) {
            rf.replacer.delete(rf.fx.indexed(id));
        }

        rf.vacuum.vacuum(DB, COLL, 0, pageRows());

        assertEquals(0L, rf.fx.page(0).length());
        assertTrue(rf.fx.page(0).exists(), "the page file stays, empty");
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
}
