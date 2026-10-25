package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.ChunkedList;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.utils.RangeBounds;
import org.techhouse.utils.RangeBounds.Bound;
import org.techhouse.utils.SearchUtils;

public class SearchUtilsChunkedTest {
    private static final int VALUES = 2_100;
    private static final List<FieldOperatorType> RANGE_TYPES = List.of(FieldOperatorType.GREATER_THAN,
            FieldOperatorType.GREATER_THAN_EQUALS, FieldOperatorType.SMALLER_THAN,
            FieldOperatorType.SMALLER_THAN_EQUALS);

    private static List<FieldIndexEntry<Number>> numbers() {
        final var entries = new ArrayList<FieldIndexEntry<Number>>(VALUES);
        for (var i = 0; i < VALUES; i++) {
            final var value = i == 0 ? -0.0 : (double) i;
            entries.add(new FieldIndexEntry<>("db", "coll", value, idsOf(i, 1 + i % 3)));
        }
        return entries;
    }

    private static Set<String> idsOf(int value, int count) {
        final var ids = new HashSet<String>();
        for (var copy = 0; copy < count; copy++) {
            ids.add("id-" + value + "-" + copy);
        }
        return ids;
    }

    private static List<FieldIndexEntry<String>> spellings() {
        final var entries = new ArrayList<FieldIndexEntry<String>>();
        entries.add(new FieldIndexEntry<>("db", "coll", "A0000", Set.of("first")));
        for (var i = 0; i < 600; i++) {
            final var key = String.format("%05d", i);
            entries.add(new FieldIndexEntry<>("db", "coll", "V" + key, Set.of("upper-" + i)));
            entries.add(new FieldIndexEntry<>("db", "coll", "v" + key, Set.of("lower-" + i, "other-" + i)));
        }
        return entries;
    }

    private static Set<String> scanned(List<FieldIndexEntry<Number>> entries, RangeBounds bounds) {
        final var ids = new HashSet<String>();
        for (final var entry : entries) {
            final var value = entry.getValue().doubleValue();
            var inside = true;
            for (final var bound : bounds.bounds()) {
                final var limit = bound.value().doubleValue();
                inside &= switch (bound.operatorType()) {
                    case GREATER_THAN -> value > limit;
                    case GREATER_THAN_EQUALS -> value >= limit;
                    case SMALLER_THAN -> value < limit;
                    case SMALLER_THAN_EQUALS -> value <= limit;
                    default -> throw new IllegalArgumentException();
                };
            }
            if (inside) {
                ids.addAll(entry.getIds());
            }
        }
        return ids;
    }

    @Test
    public void test_chunked_and_array_lists_answer_every_bound_like_a_scan() {
        final var plain = numbers();
        final var chunked = ChunkedList.copyOf(plain);
        for (final var type : RANGE_TYPES) {
            for (final var limit : List.of(-1.0, -0.0, 0.0, 0.5, 511.0, 512.0, 1_023.5, 2_099.0, 2_100.0)) {
                final var bounds = new RangeBounds("price", List.of(new Bound(limit, type)));
                final var expected = scanned(plain, bounds);
                assertEquals(expected, SearchUtils.findingBetween(plain, bounds), type + " " + limit);
                assertEquals(expected, SearchUtils.findingBetween(chunked, bounds), type + " " + limit);
                assertEquals(expected.size(), SearchUtils.countBetween(chunked, bounds), type + " " + limit);
                assertEquals(expected.size(), SearchUtils.countBetween(plain, bounds), type + " " + limit);
            }
        }
    }

    @Test
    public void test_a_two_sided_slice_across_several_chunks_counts_every_id() {
        final var chunked = ChunkedList.copyOf(numbers());
        final var bounds = new RangeBounds("price", List.of(new Bound(300, FieldOperatorType.GREATER_THAN_EQUALS),
                new Bound(1_800, FieldOperatorType.SMALLER_THAN)));

        assertEquals(scanned(numbers(), bounds).size(), SearchUtils.countBetween(chunked, bounds));
        assertEquals(scanned(numbers(), bounds), SearchUtils.findingBetween(chunked, bounds));
    }

    @Test
    public void test_an_empty_slice_counts_nothing() {
        final var chunked = ChunkedList.copyOf(numbers());
        final var bounds = new RangeBounds("price", List.of(new Bound(900, FieldOperatorType.GREATER_THAN),
                new Bound(900, FieldOperatorType.SMALLER_THAN)));

        assertEquals(0L, SearchUtils.countBetween(chunked, bounds));
        assertEquals(0L, SearchUtils.countBetween(ChunkedList.copyOf(List.<FieldIndexEntry<Number>>of()), bounds));
    }

    @Test
    public void test_equal_run_spans_a_chunk_boundary() {
        final var plain = spellings();
        final var chunked = ChunkedList.copyOf(plain);
        for (final var key : List.of("v00255", "V00256", "v00000", "v00599", "a0000", "missing")) {
            final var expected = SearchUtils.findingByOperator(plain, FieldOperatorType.EQUALS, key);
            assertEquals(expected, SearchUtils.findingByOperator(chunked, FieldOperatorType.EQUALS, key), key);
            assertEquals(expected.size(), SearchUtils.countEquals(chunked, key), key);
        }
        assertEquals(Set.of("upper-255", "lower-255", "other-255"),
                SearchUtils.findingByOperator(chunked, FieldOperatorType.EQUALS, "v00255"));
    }

    @Test
    public void test_in_and_not_in_agree_on_both_list_kinds() {
        final var plain = spellings();
        final var chunked = ChunkedList.copyOf(plain);
        final var operands = List.of("v00255", "v00510", "nothing");

        assertEquals(SearchUtils.findingInNotIn(plain, FieldOperatorType.IN, operands),
                SearchUtils.findingInNotIn(chunked, FieldOperatorType.IN, operands));
        assertEquals(SearchUtils.findingInNotIn(plain, FieldOperatorType.NOT_IN, operands),
                SearchUtils.findingInNotIn(chunked, FieldOperatorType.NOT_IN, operands));
    }
}
