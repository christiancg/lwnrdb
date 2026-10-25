package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.ChunkedList;

public class ChunkedListTest {
    private static final Comparator<String> ORDER = Comparator.naturalOrder();
    private static final int OPERATIONS = 100_000;
    private static final int ID_POOL = 20_000;
    private static final int FRESH_ID_POOL = 2_000_000;

    private record Tagged(int key, String tag) {
    }

    private static List<String> sortedIds(int count) {
        final var ids = new ArrayList<String>(count);
        for (var i = 0; i < count; i++) {
            ids.add(String.format("id-%06d", i));
        }
        return ids;
    }

    private static void insertSorted(List<String> ids, String id) {
        final var at = Collections.binarySearch(ids, id);
        if (at < 0) {
            ids.add(-at - 1, id);
        } else {
            ids.set(at, id);
        }
    }

    private static void removeSorted(List<String> ids, String id) {
        final var at = Collections.binarySearch(ids, id);
        if (at >= 0) {
            ids.remove(at);
        }
    }

    @Test
    public void test_it_behaves_like_a_sorted_array_list_under_random_inserts_and_removes() {
        final var random = new Random(42);
        final var oracle = new ArrayList<>(sortedIds(3_000));
        final var list = ChunkedList.copyOf(oracle);
        for (var operation = 0; operation < OPERATIONS; operation++) {
            final var id = String.format("id-%06d", random.nextInt(ID_POOL));
            switch (random.nextInt(10)) {
                case 0, 1, 2, 3 -> {
                    insertSorted(oracle, id);
                    insertSorted(list, id);
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
                    oracle.removeIf(entry -> entry.endsWith("7"));
                    list.removeIf(entry -> entry.endsWith("7"));
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
        final var list = ChunkedList.<String>copyOf(List.of());
        final var oracle = new ArrayList<String>();
        for (var i = 0; i < 5_000; i++) {
            final var id = String.format("id-%06d", i);
            list.add(id);
            oracle.add(id);
        }
        for (var i = 0; i < 2_000; i++) {
            list.add(1_000, "id-001000-" + i);
            oracle.add(1_000, "id-001000-" + i);
        }
        assertEquals(oracle, list);
        while (!oracle.isEmpty()) {
            final var at = oracle.size() / 3;
            assertEquals(oracle.remove(at), list.remove(at));
        }
        assertTrue(list.isEmpty());
        list.addFirst("again");
        assertEquals(List.of("again"), list);
    }

    @Test
    public void test_an_empty_list_and_a_single_chunk() {
        final var empty = ChunkedList.<String>copyOf(List.of());
        assertEquals(0, empty.size());
        assertEquals(-1, Collections.binarySearch(empty, "anything"));
        assertThrows(IndexOutOfBoundsException.class, () -> empty.get(empty.size()));
        assertThrows(IndexOutOfBoundsException.class, () -> empty.add(1, "x"));

        final var small = ChunkedList.copyOf(sortedIds(3));
        small.set(1, "id-000001");
        small.clear();
        small.addAll(sortedIds(2));
        assertEquals(sortedIds(2), small);
        assertThrows(IndexOutOfBoundsException.class, () -> small.remove(2));
    }

    @Test
    public void test_add_all_into_an_empty_list_chunks_in_one_pass_and_appends_otherwise() {
        final var list = ChunkedList.<String>copyOf(List.of());
        final var empty = list.iterator();
        assertFalse(list.addAll(List.of()));
        assertFalse(empty.hasNext());

        assertTrue(list.addAll(sortedIds(5_000)));
        assertThrows(ConcurrentModificationException.class, empty::next);
        assertEquals(sortedIds(5_000), list);
        for (var i = 0; i < 5_000; i += 511) {
            assertEquals(i, Collections.binarySearch(list, String.format("id-%06d", i)));
        }

        assertTrue(list.addAll(List.of("zz-1", "zz-2")));
        assertEquals(5_002, list.size());
        assertEquals("zz-2", list.getLast());

        final var fromSet = ChunkedList.<String>copyOf(List.of());
        fromSet.addAll(new LinkedHashSet<>(sortedIds(1_500)));
        assertEquals(sortedIds(1_500), fromSet);
        fromSet.clear();
        fromSet.addAll(sortedIds(3));
        assertEquals(sortedIds(3), fromSet);
    }

    private static List<String> freshIds(Random random, int count, List<String> existing) {
        final var taken = new HashSet<>(existing);
        final var fresh = new ArrayList<String>(count);
        while (fresh.size() < count) {
            final var id = String.format("id-%07d", random.nextInt(FRESH_ID_POOL));
            if (taken.add(id)) {
                fresh.add(id);
            }
        }
        fresh.sort(ORDER);
        return fresh;
    }

    @Test
    public void test_insert_sorted_matches_a_sorted_array_list_for_random_batches() {
        final var random = new Random(7);
        for (final var initial : new int[]{0, 1, 511, 512, 513, 3_000}) {
            final var oracle = new ArrayList<>(sortedIds(initial));
            final var list = ChunkedList.copyOf(oracle);
            for (var batch = 0; batch < 60; batch++) {
                final var fresh = freshIds(random, 1 + random.nextInt(batch % 10 == 0 ? 3_000 : 400), oracle);
                oracle.addAll(fresh);
                oracle.sort(ORDER);
                list.insertSorted(fresh, ORDER);
                assertEquals(oracle.size(), list.size());
                assertEquals(oracle, new ArrayList<>(list));
                final var probe = oracle.get(random.nextInt(oracle.size()));
                assertEquals(Collections.binarySearch(oracle, probe), Collections.binarySearch(list, probe));
                final var extra = String.format("id-%07d", 5_000_000 + batch);
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
        final var existing = new ArrayList<String>();
        for (var i = 0; i < 4_000; i++) {
            existing.add(String.format("m-%06d", i * 2));
        }
        final var list = ChunkedList.copyOf(existing);
        final var added = new ArrayList<String>();
        for (var i = 0; i < 700; i++) {
            added.add(String.format("a-%06d", i));
        }
        for (var i = 0; i < 700; i++) {
            added.add(String.format("m-%06d", i * 11 + 1));
        }
        for (var i = 0; i < 2_500; i++) {
            added.add(String.format("z-%06d", i));
        }
        final var oracle = new ArrayList<>(existing);
        oracle.addAll(added);
        oracle.sort(ORDER);

        list.insertSorted(added.stream().sorted(ORDER).toList(), ORDER);

        assertEquals(oracle, list);
        for (var i = 0; i < oracle.size(); i += 97) {
            assertEquals(oracle.get(i), list.get(i));
        }
    }

    @Test
    public void test_insert_sorted_places_an_equal_row_after_the_existing_ones() {
        final Comparator<Tagged> byKey = Comparator.comparingInt(Tagged::key);
        final var existing = new ArrayList<Tagged>();
        for (var i = 0; i < 3_000; i++) {
            existing.add(new Tagged(i / 3, "old" + i));
        }
        final var list = ChunkedList.copyOf(existing);
        final var added = new ArrayList<Tagged>();
        for (var key = 0; key < 1_000; key += 7) {
            added.add(new Tagged(key, "new" + key));
        }
        final var oracle = new ArrayList<>(existing);
        for (final var row : added) {
            var at = 0;
            while (at < oracle.size() && byKey.compare(oracle.get(at), row) <= 0) {
                at++;
            }
            oracle.add(at, row);
        }

        list.insertSorted(added, byKey);

        assertEquals(oracle, list);
    }

    @Test
    public void test_insert_sorted_into_an_empty_list_and_with_an_empty_batch() {
        final var list = ChunkedList.<String>copyOf(List.of());
        list.insertSorted(List.of(), ORDER);
        assertTrue(list.isEmpty());

        list.insertSorted(sortedIds(5_000), ORDER);

        assertEquals(sortedIds(5_000), list);
        assertEquals(5_000, list.size());
    }

    @Test
    public void test_insert_sorted_invalidates_live_iterators_but_an_empty_batch_does_not() {
        final var list = ChunkedList.copyOf(sortedIds(10));
        final var survivor = list.iterator();
        list.insertSorted(List.of(), ORDER);
        survivor.next();

        final var invalidated = list.iterator();
        list.insertSorted(List.of("zzz"), ORDER);

        assertThrows(ConcurrentModificationException.class, invalidated::next);
        assertEquals(11, list.size());
    }

    private static final class FailingIds extends AbstractList<String> {
        private final List<String> ids;
        private final int failAt;

        private FailingIds(List<String> ids, int failAt) {
            this.ids = ids;
            this.failAt = failAt;
        }

        @Override
        public String get(int index) {
            if (index == failAt) {
                throw new IllegalStateException("injected failure");
            }
            return ids.get(index);
        }

        @Override
        public int size() {
            return ids.size();
        }
    }

    @Test
    public void test_a_failed_insert_sorted_leaves_a_consistent_list_that_still_works() {
        final var original = new ArrayList<String>();
        for (var i = 0; i < 4_000; i++) {
            original.add(String.format("m-%06d", i * 2));
        }
        final var list = ChunkedList.copyOf(original);
        final var fresh = new ArrayList<String>();
        for (var i = 0; i < 4_000; i += 40) {
            fresh.add(String.format("m-%06d", i * 2 + 1));
        }
        final var failing = new FailingIds(fresh, fresh.size() - 1);

        assertThrows(IllegalStateException.class, () -> list.insertSorted(failing, ORDER));

        final var seen = new ArrayList<>(list);
        assertEquals(seen.size(), list.size());
        assertTrue(seen.containsAll(original));
        assertTrue(fresh.containsAll(seen.stream().filter(entry -> !original.contains(entry)).toList()));
        for (var i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i - 1).compareTo(seen.get(i)) < 0);
        }
        for (var i = 0; i < seen.size(); i += 53) {
            assertEquals(seen.get(i), list.get(i));
            assertEquals(i, Collections.binarySearch(list, seen.get(i)));
        }
        list.addFirst("a-first");
        list.insertSorted(List.of("zzz-last"), ORDER);
        assertEquals(seen.size() + 2, list.size());
        assertEquals("a-first", list.getFirst());
        assertEquals("zzz-last", list.getLast());
    }

    @Test
    public void test_a_sub_list_walks_the_chunks_and_equals_the_plain_window() {
        final var ids = sortedIds(3_000);
        final var chunked = ChunkedList.copyOf(ids);
        final var random = new Random(7);
        final var edges = List.of(0, 1, 511, 512, 513, 1023, 1024, 1025, 2047, 2048, 2999, 3000);
        for (final var from : edges) {
            for (final var to : edges) {
                if (from <= to) {
                    assertEquals(ids.subList(from, to), new ArrayList<>(chunked.subList(from, to)), from + ".." + to);
                }
            }
        }
        for (var round = 0; round < 200; round++) {
            final var from = random.nextInt(ids.size() + 1);
            final var to = from + random.nextInt(ids.size() - from + 1);
            final var window = chunked.subList(from, to);
            assertEquals(ids.subList(from, to), new ArrayList<>(window));
            if (to > from) {
                assertEquals(ids.get(to - 1), window.get(to - from - 1));
            }
        }
        final var empty = chunked.subList(5, 5).iterator();
        assertFalse(empty.hasNext());
        assertThrows(NoSuchElementException.class, empty::next);
        assertThrows(IndexOutOfBoundsException.class, () -> chunked.subList(2, 3_001));
        assertThrows(UnsupportedOperationException.class, () -> chunked.subList(0, 3).set(0, "x"));
    }
}
