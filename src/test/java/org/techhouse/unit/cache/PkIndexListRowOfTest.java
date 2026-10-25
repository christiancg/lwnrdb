package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.PkIndexList;
import org.techhouse.data.PkIndexEntry;

public class PkIndexListRowOfTest {
    private static final int ROWS = 3_000;

    private static List<PkIndexEntry> sortedRows() {
        final var rows = new ArrayList<PkIndexEntry>(ROWS);
        for (var i = 0; i < ROWS; i++) {
            rows.add(new PkIndexEntry("rowOfDb", "rowOfColl", String.format("id-%05d", 2 * i), i, 1, 0));
        }
        return rows;
    }

    @Test
    public void test_row_of_matches_search() {
        final var rows = sortedRows();
        final var chunked = PkIndexList.of(rows);
        for (var i = 0; i < 6_000; i++) {
            final var id = String.format("id-%05d", i);
            final var expected = i % 2 == 0 ? rows.get(i / 2) : null;

            assertSame(expected, PkIndexList.rowOf(chunked, id), id);
            assertSame(expected, PkIndexList.rowOf(rows, id), id + " in a plain list");
        }
        assertNull(PkIndexList.rowOf(PkIndexList.of(List.of()), "id-00000"));
    }
}
