package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.utils.SearchUtils;

public class SearchUtilsBoundaryTest {
    private static List<FieldIndexEntry<Number>> single() {
        return List.of(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")));
    }

    private static Set<String> find(List<FieldIndexEntry<Number>> entries, FieldOperatorType type, Number value) {
        return SearchUtils.findingByOperator(entries, type, value);
    }

    @Test
    public void test_single_entry_greater_than_below_value_returns_the_entry() {
        assertEquals(Set.of("id1"), find(single(), FieldOperatorType.GREATER_THAN, 5));
    }

    @Test
    public void test_single_entry_greater_than_equal_value_returns_empty() {
        assertEquals(Set.of(), find(single(), FieldOperatorType.GREATER_THAN, 10));
    }

    @Test
    public void test_single_entry_greater_than_above_value_returns_empty() {
        assertEquals(Set.of(), find(single(), FieldOperatorType.GREATER_THAN, 15));
    }

    @Test
    public void test_single_entry_greater_than_equals_at_value_returns_the_entry() {
        assertEquals(Set.of("id1"), find(single(), FieldOperatorType.GREATER_THAN_EQUALS, 10));
    }

    @Test
    public void test_single_entry_greater_than_equals_above_value_returns_empty() {
        assertEquals(Set.of(), find(single(), FieldOperatorType.GREATER_THAN_EQUALS, 15));
    }

    @Test
    public void test_single_entry_smaller_than_above_value_returns_the_entry() {
        assertEquals(Set.of("id1"), find(single(), FieldOperatorType.SMALLER_THAN, 15));
    }

    @Test
    public void test_single_entry_smaller_than_equal_value_returns_empty() {
        assertEquals(Set.of(), find(single(), FieldOperatorType.SMALLER_THAN, 10));
    }

    @Test
    public void test_single_entry_smaller_than_equals_at_value_returns_the_entry() {
        assertEquals(Set.of("id1"), find(single(), FieldOperatorType.SMALLER_THAN_EQUALS, 10));
    }

    @Test
    public void test_single_entry_smaller_than_equals_below_value_returns_empty() {
        assertEquals(Set.of(), find(single(), FieldOperatorType.SMALLER_THAN_EQUALS, 5));
    }

    @Test
    public void test_single_entry_custom_type_greater_than_below_value_returns_the_entry() {
        JsonTime t1 = new JsonTime("#time(10:00:00)");
        JsonTime search = new JsonTime("#time(08:00:00)");
        List<FieldIndexEntry<JsonCustom<?>>> entries = List.of(new FieldIndexEntry<>("db", "col", t1, Set.of("id1")));
        assertEquals(Set.of("id1"), SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN, search));
    }

    @Test
    public void test_single_entry_custom_type_smaller_than_above_value_returns_the_entry() {
        JsonTime t1 = new JsonTime("#time(10:00:00)");
        JsonTime search = new JsonTime("#time(12:00:00)");
        List<FieldIndexEntry<JsonCustom<?>>> entries = List.of(new FieldIndexEntry<>("db", "col", t1, Set.of("id1")));
        assertEquals(Set.of("id1"), SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, search));
    }

    @Test
    public void test_empty_index_range_operators_return_empty_without_throwing() {
        List<FieldIndexEntry<Number>> entries = List.of();
        assertEquals(Set.of(), find(entries, FieldOperatorType.GREATER_THAN, 10));
        assertEquals(Set.of(), find(entries, FieldOperatorType.GREATER_THAN_EQUALS, 10));
        assertEquals(Set.of(), find(entries, FieldOperatorType.SMALLER_THAN, 10));
        assertEquals(Set.of(), find(entries, FieldOperatorType.SMALLER_THAN_EQUALS, 10));
    }

    @Test
    public void test_empty_custom_type_index_returns_empty_without_throwing() {
        List<FieldIndexEntry<JsonCustom<?>>> entries = List.of();
        JsonTime search = new JsonTime("#time(10:00:00)");
        assertEquals(Set.of(), SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN, search));
        assertEquals(Set.of(), SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, search));
    }

    @Test
    public void test_two_entry_index_still_answers_both_directions() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 20, Set.of("id2")));
        assertEquals(Set.of("id2"), find(entries, FieldOperatorType.GREATER_THAN, 15));
        assertEquals(Set.of("id1"), find(entries, FieldOperatorType.SMALLER_THAN, 15));
        assertEquals(Set.of("id1", "id2"), find(entries, FieldOperatorType.GREATER_THAN_EQUALS, 10));
        assertEquals(Set.of("id1", "id2"), find(entries, FieldOperatorType.SMALLER_THAN_EQUALS, 20));
    }
}
