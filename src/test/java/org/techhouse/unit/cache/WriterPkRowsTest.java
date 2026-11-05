package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.PkIndexList;
import org.techhouse.cache.WriterPkRows;
import org.techhouse.data.PkIndexEntry;

public class WriterPkRowsTest {
    private static final int ROWS = 2_000;

    private static PkIndexEntry row(String id, long position) {
        return new PkIndexEntry("db", "coll", id, position, 10, 0);
    }

    private static String id(int i) {
        return String.format("id%05d", i);
    }

    private static PkIndexList listOfEvenIds() {
        final var rows = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < ROWS; i += 2) {
            rows.add(row(id(i), i));
        }
        return PkIndexList.of(rows);
    }

    private static long positionOf(List<PkIndexEntry> rows, String id) {
        final var row = PkIndexList.rowOf(rows, id);
        assertNotNull(row, id);
        return row.getPosition();
    }

    private static List<String> idsOf(List<PkIndexEntry> rows) {
        return rows.stream().map(PkIndexEntry::getValue).toList();
    }

    private static void assertSortedWith(List<PkIndexEntry> rows, int expectedSize) {
        final var ids = idsOf(rows);
        assertEquals(expectedSize, ids.size());
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    @Test
    public void anUpdateReplacesTheRowItLookedUp() {
        final var list = listOfEvenIds();
        final var rows = WriterPkRows.of(list);
        final var found = rows.row(id(500));
        assertNotNull(found);
        final var moved = row(id(500), 99_999);

        rows.store(moved, true);

        assertEquals(500, found.getPosition());
        assertSame(moved, PkIndexList.rowOf(list, id(500)));
        assertSortedWith(list, ROWS / 2);
    }

    @Test
    public void anInsertLandsWhereTheMissedLookupPointed() {
        final var list = listOfEvenIds();
        final var rows = WriterPkRows.of(list);
        assertNull(rows.row(id(501)));

        rows.store(row(id(501), 1), false);

        assertSortedWith(list, ROWS / 2 + 1);
        assertEquals(1, positionOf(list, id(501)));
    }

    @Test
    public void aDeleteRemovesTheRowItLookedUp() {
        final var list = listOfEvenIds();
        final var rows = WriterPkRows.of(list);
        rows.row(id(1_000));

        rows.remove(id(1_000));

        assertNull(PkIndexList.rowOf(list, id(1_000)));
        assertSortedWith(list, ROWS / 2 - 1);
    }

    @Test
    public void aSlotMovedByAnotherEditIsSearchedAgain() {
        final var list = listOfEvenIds();
        final var rows = WriterPkRows.of(list);
        rows.row(id(900));
        list.addFirst(row("a", 0));

        rows.store(row(id(900), 7), true);
        rows.row(id(901));
        list.add(1, row("b", 0));
        rows.store(row(id(901), 8), false);

        assertEquals(7, positionOf(list, id(900)));
        assertEquals(8, positionOf(list, id(901)));
        assertSortedWith(list, ROWS / 2 + 3);
    }

    @Test
    public void aVacuumRepositioningRowsInPlaceKeepsTheSlot() {
        final var list = listOfEvenIds();
        final var rows = WriterPkRows.of(list);
        rows.row(id(1_200));
        for (final var each : list) {
            each.setPosition(each.getPosition() + 1);
        }

        rows.remove(id(1_200));

        assertNull(PkIndexList.rowOf(list, id(1_200)));
        assertEquals(1_203, positionOf(list, id(1_202)));
    }

    @Test
    public void anEditForAnotherIdSearchesForItsOwnSlot() {
        final var list = listOfEvenIds();
        final var rows = WriterPkRows.of(list);
        rows.row(id(100));

        rows.store(row(id(300), 5), true);
        rows.remove(id(400));

        assertEquals(5, positionOf(list, id(300)));
        assertEquals(100, positionOf(list, id(100)));
        assertNull(PkIndexList.rowOf(list, id(400)));
    }

    @Test
    public void aPlainSortedListWorksToo() {
        final var list = new ArrayList<>(List.of(row("a", 1), row("c", 3)));
        final var rows = WriterPkRows.of(list);
        assertNull(rows.row("b"));

        rows.store(row("b", 2), false);

        assertEquals(List.of("a", "b", "c"), idsOf(list));
    }

    @Test
    public void scannedRowsAreReadOnly() throws Exception {
        final var scanned = Map.of("a", row("a", 1));
        final var factory = WriterPkRows.class.getDeclaredMethod("scanned", Map.class);
        factory.setAccessible(true);
        final var rows = (WriterPkRows) factory.invoke(null, scanned);

        rows.store(row("a", 2), true);
        rows.remove("a");

        final var kept = rows.row("a");
        assertNotNull(kept);
        assertEquals(1, kept.getPosition());
        assertNull(rows.editable());
    }
}
