package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.utils.RangeBounds;
import org.techhouse.utils.RangeBounds.Bound;
import org.techhouse.utils.SearchUtils;

public class SearchUtilsBetweenTest {
    private static final List<FieldIndexEntry<Number>> ENTRIES = List.of(entry(10, "a"), entry(20, "b", "c"),
            entry(30, "d"), entry(40, "e"));

    private static FieldIndexEntry<Number> entry(Number value, String... ids) {
        return new FieldIndexEntry<>("db", "coll", value, Set.of(ids));
    }

    private static RangeBounds bounds(Bound... bounds) {
        return new RangeBounds("price", List.of(bounds));
    }

    private static Bound bound(Number value, FieldOperatorType type) {
        return new Bound(value, type);
    }

    @Test
    public void test_inclusive_and_exclusive_bounds_on_both_sides() {
        assertEquals(Set.of("b", "c", "d"),
                SearchUtils.findingBetween(ENTRIES, bounds(bound(20, FieldOperatorType.GREATER_THAN_EQUALS),
                        bound(30, FieldOperatorType.SMALLER_THAN_EQUALS))));
        assertEquals(Set.of("d"), SearchUtils.findingBetween(ENTRIES,
                bounds(bound(20, FieldOperatorType.GREATER_THAN), bound(40, FieldOperatorType.SMALLER_THAN))));
    }

    @Test
    public void test_a_one_sided_bound_matches_the_existing_range_search() {
        for (final var type : List.of(FieldOperatorType.GREATER_THAN, FieldOperatorType.GREATER_THAN_EQUALS,
                FieldOperatorType.SMALLER_THAN, FieldOperatorType.SMALLER_THAN_EQUALS)) {
            for (final var value : List.of(5, 10, 25, 40, 45)) {
                assertEquals(SearchUtils.findingByOperator(ENTRIES, type, value),
                        SearchUtils.findingBetween(ENTRIES, bounds(bound(value, type))), type + " " + value);
            }
        }
    }

    @Test
    public void test_an_empty_range_and_bounds_outside_the_values() {
        assertEquals(Set.of(), SearchUtils.findingBetween(ENTRIES,
                bounds(bound(30, FieldOperatorType.GREATER_THAN), bound(20, FieldOperatorType.SMALLER_THAN))));
        assertEquals(Set.of(), SearchUtils.findingBetween(ENTRIES,
                bounds(bound(20, FieldOperatorType.GREATER_THAN_EQUALS), bound(20, FieldOperatorType.SMALLER_THAN))));
        assertEquals(Set.of(), SearchUtils.findingBetween(ENTRIES, bounds(bound(50, FieldOperatorType.GREATER_THAN))));
        assertEquals(Set.of(), SearchUtils.findingBetween(List.of(), bounds(bound(1, FieldOperatorType.SMALLER_THAN))));
    }

    @Test
    public void test_the_tightest_of_several_bounds_wins() {
        assertEquals(Set.of("d"),
                SearchUtils.findingBetween(ENTRIES, bounds(bound(10, FieldOperatorType.GREATER_THAN),
                        bound(25, FieldOperatorType.GREATER_THAN_EQUALS), bound(40, FieldOperatorType.SMALLER_THAN),
                        bound(35, FieldOperatorType.SMALLER_THAN_EQUALS))));
    }

    @Test
    public void test_duplicate_values_count_every_id_of_the_run() {
        final var withDuplicates = new ArrayList<>(ENTRIES);
        withDuplicates.add(2, entry(20, "f"));

        final var between = bounds(bound(20, FieldOperatorType.GREATER_THAN_EQUALS),
                bound(20, FieldOperatorType.SMALLER_THAN_EQUALS));

        assertEquals(Set.of("b", "c", "f"), SearchUtils.findingBetween(withDuplicates, between));
        assertEquals(3L, SearchUtils.countBetween(withDuplicates, between));
    }

    @Test
    public void test_count_between_equals_the_size_of_finding_between() {
        for (final var low : List.of(0, 10, 15, 30, 50)) {
            for (final var high : List.of(0, 20, 35, 40, 100)) {
                final var between = bounds(bound(low, FieldOperatorType.GREATER_THAN_EQUALS),
                        bound(high, FieldOperatorType.SMALLER_THAN));
                assertEquals(SearchUtils.findingBetween(ENTRIES, between).size(),
                        SearchUtils.countBetween(ENTRIES, between), low + ".." + high);
            }
        }
    }

    @Test
    public void test_the_between_result_is_detached_from_the_index_entries() {
        final var live = new java.util.HashSet<>(Set.of("b", "c"));
        final var entries = List.of(new FieldIndexEntry<Number>("db", "coll", 20, live));

        final var result = SearchUtils.findingBetween(entries,
                bounds(bound(10, FieldOperatorType.GREATER_THAN), bound(30, FieldOperatorType.SMALLER_THAN)));
        live.add("late");

        assertEquals(Set.of("b", "c"), result);
    }
}
