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
    private static final int FRESH_ID_POOL = 2_000_000;

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

    private static List<PkIndexEntry> freshRows(Random random, int count, List<PkIndexEntry> existing) {
        final var taken = new java.util.HashSet<String>();
        existing.forEach(entry -> taken.add(entry.getValue()));
        final var fresh = new ArrayList<PkIndexEntry>(count);
        while (fresh.size() < count) {
            final var id = String.format("id-%07d", random.nextInt(FRESH_ID_POOL));
            if (taken.add(id)) {
                fresh.add(row(id));
            }
        }
        fresh.sort(java.util.Comparator.comparing(PkIndexEntry::getValue));
        return fresh;
    }

    @Test
    public void test_insert_sorted_matches_a_sorted_array_list_for_random_batches() {
        final var random = new Random(7);
        for (final var initial : new int[]{0, 1, 511, 512, 513, 3_000}) {
            final var oracle = new ArrayList<>(sortedRows(initial));
            final var list = PkIndexList.of(oracle);
            for (var batch = 0; batch < 60; batch++) {
                final var fresh = freshRows(random, 1 + random.nextInt(batch % 10 == 0 ? 3_000 : 400), oracle);
                oracle.addAll(fresh);
                oracle.sort(java.util.Comparator.comparing(PkIndexEntry::getValue));
                list.insertSorted(fresh);
                assertEquals(oracle.size(), list.size());
                assertEquals(oracle, new ArrayList<>(list));
                final var probe = oracle.get(random.nextInt(oracle.size())).getValue();
                assertEquals(Collections.binarySearch(oracle, probe), Collections.binarySearch(list, probe));
                final var extra = row(String.format("id-%07d", 5_000_000 + batch));
                insertSorted(oracle, extra);
                insertSorted(list, extra);
                removeSorted(oracle, probe);
                removeSorted(list, probe);
                assertEquals(oracle, list);
            }
        }
    }

    @Test
    public void test_insert_sorted_below_between_and_above_every_existing_row() {
        final var existing = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 4_000; i++) {
            existing.add(row(String.format("m-%06d", i * 2)));
        }
        final var list = PkIndexList.of(existing);
        final var added = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 700; i++) {
            added.add(row(String.format("a-%06d", i)));
        }
        for (var i = 0; i < 700; i++) {
            added.add(row(String.format("m-%06d", i * 11 + 1)));
        }
        for (var i = 0; i < 2_500; i++) {
            added.add(row(String.format("z-%06d", i)));
        }
        final var oracle = new ArrayList<>(existing);
        oracle.addAll(added);
        oracle.sort(java.util.Comparator.comparing(PkIndexEntry::getValue));

        list.insertSorted(added.stream().sorted(java.util.Comparator.comparing(PkIndexEntry::getValue)).toList());

        assertEquals(oracle, list);
        for (var i = 0; i < oracle.size(); i += 97) {
            assertEquals(oracle.get(i), list.get(i));
        }
    }

    @Test
    public void test_insert_sorted_into_an_empty_list_and_with_an_empty_batch() {
        final var list = PkIndexList.of(List.of());
        list.insertSorted(List.of());
        assertTrue(list.isEmpty());

        list.insertSorted(sortedRows(5_000));

        assertEquals(sortedRows(5_000), list);
        assertEquals(5_000, list.size());
    }

    @Test
    public void test_insert_sorted_invalidates_live_iterators_but_an_empty_batch_does_not() {
        final var list = PkIndexList.of(sortedRows(10));
        final var survivor = list.iterator();
        list.insertSorted(List.of());
        survivor.next();

        final var invalidated = list.iterator();
        list.insertSorted(List.of(row("zzz")));

        assertThrows(java.util.ConcurrentModificationException.class, invalidated::next);
        assertEquals(11, list.size());
    }

    private static final class FailingRows extends java.util.AbstractList<PkIndexEntry> {
        private final List<PkIndexEntry> rows;
        private final int failAt;

        private FailingRows(List<PkIndexEntry> rows, int failAt) {
            this.rows = rows;
            this.failAt = failAt;
        }

        @Override
        public PkIndexEntry get(int index) {
            if (index == failAt) {
                throw new IllegalStateException("injected failure");
            }
            return rows.get(index);
        }

        @Override
        public int size() {
            return rows.size();
        }
    }

    @Test
    public void test_a_failed_insert_sorted_leaves_a_consistent_list_that_still_works() {
        final var original = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 4_000; i++) {
            original.add(row(String.format("m-%06d", i * 2)));
        }
        final var list = PkIndexList.of(original);
        final var fresh = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 4_000; i += 40) {
            fresh.add(row(String.format("m-%06d", i * 2 + 1)));
        }
        final var failing = new FailingRows(fresh, fresh.size() - 1);

        assertThrows(IllegalStateException.class, () -> list.insertSorted(failing));

        final var seen = new ArrayList<>(list);
        assertEquals(seen.size(), list.size());
        assertTrue(seen.containsAll(original));
        assertTrue(fresh.containsAll(seen.stream().filter(entry -> !original.contains(entry)).toList()));
        for (var i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i - 1).getValue().compareTo(seen.get(i).getValue()) < 0);
        }
        for (var i = 0; i < seen.size(); i += 53) {
            assertEquals(seen.get(i), list.get(i));
            assertEquals(i, Collections.binarySearch(list, seen.get(i).getValue()));
        }
        list.add(0, row("a-first"));
        list.insertSorted(List.of(row("zzz-last")));
        assertEquals(seen.size() + 2, list.size());
        assertEquals("a-first", list.getFirst().getValue());
        assertEquals("zzz-last", list.getLast().getValue());
    }

    private static void assertSameAnswer(List<PkIndexEntry> oracle, PkIndexList list, String id) {
        assertEquals(Collections.binarySearch(oracle, id), list.search(id), "search " + id);
        assertEquals(Collections.binarySearch(oracle, id), PkIndexList.positionOf(list, id), "positionOf " + id);
    }

    @Test
    public void test_search_answers_exactly_like_binary_search_on_a_sorted_list() {
        final var random = new Random(31L);
        for (final var count : new int[]{0, 1, 2, 511, 512, 513, 1_024, 1_025, 5_000, 20_000}) {
            final var oracle = new ArrayList<PkIndexEntry>();
            for (var i = 0; i < count; i++) {
                oracle.add(row(String.format("id-%06d", i * 2)));
            }
            final var list = PkIndexList.of(oracle);
            for (var probe = -2; probe <= count * 2 + 2; probe += 1 + random.nextInt(37)) {
                assertSameAnswer(oracle, list, String.format("id-%06d", Math.max(0, probe)));
            }
            assertSameAnswer(oracle, list, "");
            assertSameAnswer(oracle, list, "zzz");
            assertSameAnswer(oracle, list, "id-");
            for (var i = 0; i < oracle.size(); i += 53) {
                assertSameAnswer(oracle, list, oracle.get(i).getValue());
            }
        }
    }

    @Test
    public void test_search_agrees_at_every_chunk_boundary() {
        final var oracle = new ArrayList<>(sortedRows(5_000));
        final var list = PkIndexList.of(oracle);
        for (var boundary = 0; boundary < oracle.size(); boundary += 512) {
            for (var offset = -2; offset <= 2; offset++) {
                final var at = Math.clamp(boundary + offset, 0, oracle.size() - 1);
                assertSameAnswer(oracle, list, oracle.get(at).getValue());
                assertSameAnswer(oracle, list, oracle.get(at).getValue() + "0");
                assertSameAnswer(oracle, list, oracle.get(at).getValue().substring(0, 8));
            }
        }
    }

    @Test
    public void test_search_stays_exact_while_the_list_is_edited() {
        final var random = new Random(77L);
        final var oracle = new ArrayList<>(sortedRows(2_000));
        final var list = PkIndexList.of(oracle);
        for (var operation = 0; operation < 30_000; operation++) {
            final var id = String.format("id-%06d", random.nextInt(8_000));
            if (random.nextBoolean()) {
                insertSorted(oracle, row(id));
                insertSorted(list, row(id));
            } else {
                removeSorted(oracle, id);
                removeSorted(list, id);
            }
            assertSameAnswer(oracle, list, String.format("id-%06d", random.nextInt(8_000)));
        }
    }

    @Test
    public void test_position_of_falls_back_to_binary_search_for_any_other_list() {
        final var plain = new ArrayList<>(sortedRows(100));

        assertEquals(Collections.binarySearch(plain, "id-000042"), PkIndexList.positionOf(plain, "id-000042"));
        assertEquals(Collections.binarySearch(plain, "missing"), PkIndexList.positionOf(plain, "missing"));
        assertEquals(-1, PkIndexList.positionOf(new ArrayList<>(), "anything"));
    }
}
