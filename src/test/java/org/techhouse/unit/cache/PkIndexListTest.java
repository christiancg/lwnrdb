package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.PkIndexList;
import org.techhouse.data.PkIndexEntry;

public class PkIndexListTest {
    private static final String DB = "pkListDb";
    private static final String COLL = "pkListColl";
    private static final int OPERATIONS = 100_000;
    private static final int ID_POOL = 20_000;

    private static PkIndexEntry row(String id) {
        return new PkIndexEntry(DB, COLL, id, id.length(), 1, 0);
    }

    private static List<PkIndexEntry> sortedRows(int count) {
        final var rows = new ArrayList<PkIndexEntry>(count);
        for (var i = 0; i < count; i++) {
            rows.add(row(String.format("id-%06d", i)));
        }
        return rows;
    }

    private static void insertSorted(List<PkIndexEntry> rows, PkIndexEntry row) {
        final var at = Collections.binarySearch(rows, row.getValue());
        if (at < 0) {
            rows.add(-at - 1, row);
        } else {
            rows.set(at, row);
        }
    }

    private static void removeSorted(List<PkIndexEntry> rows, String id) {
        final var at = Collections.binarySearch(rows, id);
        if (at >= 0) {
            rows.remove(at);
        }
    }

    @Test
    public void test_it_behaves_like_a_sorted_array_list_under_random_inserts_and_removes() {
        final var random = new Random(42);
        final var oracle = new ArrayList<>(sortedRows(3_000));
        final var list = PkIndexList.of(oracle);
        for (var operation = 0; operation < OPERATIONS; operation++) {
            final var id = String.format("id-%06d", random.nextInt(ID_POOL));
            switch (random.nextInt(10)) {
                case 0, 1, 2, 3 -> {
                    insertSorted(oracle, row(id));
                    insertSorted(list, row(id));
                }
                case 4, 5, 6 -> {
                    removeSorted(oracle, id);
                    removeSorted(list, id);
                }
                case 7 -> assertEquals(Collections.binarySearch(oracle, id), Collections.binarySearch(list, id));
                case 8 -> {
                    if (!oracle.isEmpty()) {
                        final var at = random.nextInt(oracle.size());
                        assertEquals(oracle.get(at), list.get(at));
                    }
                }
                default -> {
                    oracle.removeIf(entry -> entry.getValue().endsWith("7"));
                    list.removeIf(entry -> entry.getValue().endsWith("7"));
                }
            }
            assertEquals(oracle.size(), list.size());
        }
        assertEquals(oracle, list);
        assertEquals(oracle, new ArrayList<>(list));
        assertEquals(oracle.subList(10, 200), list.subList(10, 200));
    }

    @Test
    public void test_a_chunk_splits_and_merges_at_its_bounds() {
        final var list = PkIndexList.of(List.of());
        final var oracle = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 5_000; i++) {
            final var id = String.format("id-%06d", i);
            list.add(row(id));
            oracle.add(row(id));
        }
        for (var i = 0; i < 2_000; i++) {
            list.add(1_000, row("id-001000-" + i));
            oracle.add(1_000, row("id-001000-" + i));
        }
        assertEquals(oracle, list);
        while (!oracle.isEmpty()) {
            final var at = oracle.size() / 3;
            assertEquals(oracle.remove(at), list.remove(at));
        }
        assertTrue(list.isEmpty());
        list.add(0, row("again"));
        assertEquals(List.of(row("again")), list);
    }

    @Test
    public void test_an_empty_list_and_a_single_chunk() {
        final var empty = PkIndexList.of(List.of());
        assertEquals(0, empty.size());
        assertEquals(-1, Collections.binarySearch(empty, "anything"));
        assertThrows(IndexOutOfBoundsException.class, () -> empty.get(empty.size()));
        assertThrows(IndexOutOfBoundsException.class, () -> empty.add(1, row("x")));

        final var small = PkIndexList.of(sortedRows(3));
        small.set(1, row("id-000001"));
        small.clear();
        small.addAll(sortedRows(2));
        assertEquals(sortedRows(2), small);
        assertThrows(IndexOutOfBoundsException.class, () -> small.remove(2));
    }
}
