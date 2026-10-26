package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.ChunkedList;
import org.techhouse.test.TestUtils;

public class ChunkedListPartitionTest {
    private static final int OPERATIONS = 20_000;
    private static final int KEY_POOL = 50_000;

    private static List<Integer> evens(int count) {
        final var keys = new ArrayList<Integer>(count);
        for (var i = 0; i < count; i++) {
            keys.add(i * 2);
        }
        return keys;
    }

    private static int oraclePartition(List<Integer> sorted, int cut) {
        var point = 0;
        while (point < sorted.size() && sorted.get(point) < cut) {
            point++;
        }
        return point;
    }

    private static long oracleSum(List<Integer> sorted, int from, int to) {
        var total = 0L;
        for (var index = from; index < to; index++) {
            total += sorted.get(index);
        }
        return total;
    }

    @Test
    public void test_partition_point_matches_an_array_list() {
        for (final var count : List.of(1, 511, 512, 513, 1_025, 5_000)) {
            final var oracle = evens(count);
            final var list = ChunkedList.copyOf(oracle);
            for (var cut = -1; cut <= count * 2 + 1; cut += Math.max(1, count / 50)) {
                final var bound = cut;
                assertEquals(oraclePartition(oracle, bound), list.partitionPoint(key -> key < bound),
                        "count " + count + ", cut " + bound);
            }
        }
    }

    @Test
    public void test_partition_point_matches_after_inserts_and_removes_split_and_merge_chunks() {
        final var random = new Random(7);
        final var oracle = new ArrayList<>(evens(3_000));
        final var list = ChunkedList.copyOf(oracle);
        for (var operation = 0; operation < OPERATIONS; operation++) {
            final var key = random.nextInt(KEY_POOL);
            final var at = Collections.binarySearch(oracle, key);
            if (random.nextBoolean() && at < 0) {
                oracle.add(-at - 1, key);
                list.add(-at - 1, key);
            } else if (at >= 0) {
                oracle.remove(at);
                list.remove(at);
            }
            if (operation % 500 == 0 && !oracle.isEmpty()) {
                final var cut = random.nextInt(KEY_POOL);
                assertEquals(oraclePartition(oracle, cut), list.partitionPoint(value -> value < cut));
            }
        }
    }

    @Test
    public void test_partition_point_on_an_empty_list_is_zero() {
        assertEquals(0, ChunkedList.<Integer>copyOf(List.of()).partitionPoint(_ -> true));
    }

    @Test
    public void test_partition_point_past_the_end_is_size() {
        final var list = ChunkedList.copyOf(evens(2_000));

        assertEquals(2_000, list.partitionPoint(_ -> true));
        assertEquals(0, list.partitionPoint(_ -> false));
    }

    @Test
    public void test_sum_of_matches_a_loop() {
        final var random = new Random(11);
        final var oracle = evens(4_000);
        final var list = ChunkedList.copyOf(oracle);
        for (var round = 0; round < 500; round++) {
            final var from = random.nextInt(oracle.size() + 1);
            final var to = from + random.nextInt(oracle.size() - from + 1);
            assertEquals(oracleSum(oracle, from, to), list.sumOf(from, to, Integer::longValue),
                    "from " + from + " to " + to);
        }
    }

    @Test
    public void test_sum_of_an_empty_range_is_zero() {
        final var list = ChunkedList.copyOf(evens(1_000));

        assertEquals(0L, list.sumOf(700, 700, Integer::longValue));
        assertEquals(0L, ChunkedList.<Integer>copyOf(List.of()).sumOf(0, 0, Integer::longValue));
    }

    @Test
    public void test_sum_of_refuses_a_range_outside_the_list() {
        final var list = ChunkedList.copyOf(evens(10));

        assertThrows(IndexOutOfBoundsException.class, () -> list.sumOf(5, 11, Integer::longValue));
        assertThrows(IndexOutOfBoundsException.class, () -> list.sumOf(6, 5, Integer::longValue));
    }

    @Test
    public void test_partition_point_leaves_the_chunk_hint_alone() throws Exception {
        final var list = ChunkedList.copyOf(evens(5_000));
        assertEquals(20, list.get(10));
        final int before = TestUtils.getPrivateField(list, "lastChunk", Integer.class);

        list.partitionPoint(key -> key < 9_000);
        list.sumOf(4_000, 4_900, Integer::longValue);

        assertEquals(before, TestUtils.getPrivateField(list, "lastChunk", Integer.class));
    }
}
