package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.utils.SearchUtils;

public class SearchUtilsRangeTest {
    // Returns IDs of entries greater than numeric value using GREATER_THAN operator
    @Test
    public void test_greater_than_operator() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 20, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 30, Set.of("id3")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN, 15);
        assertEquals(Set.of("id2", "id3"), result);
    }

    // Returns IDs of entries greater or equal to value using GREATER_THAN_EQUALS
    @Test
    public void test_greater_than_equals_operator() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 20, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 30, Set.of("id3")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN_EQUALS, 20);
        assertEquals(Set.of("id2", "id3"), result);
    }

    // Returns IDs of entries less than numeric value using SMALLER_THAN operator
    @Test
    public void test_finding_by_operator_smaller_than() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 5, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 10, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 15, Set.of("id3")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, 12);
        assertEquals(Set.of("id1", "id2"), result);
    }

    // Returns IDs of entries less or equal to value using SMALLER_THAN_EQUALS
    @Test
    public void test_finding_by_operator_smaller_than_equals() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 5, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 10, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 15, Set.of("id3")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN_EQUALS, 10);
        assertEquals(Set.of("id1", "id2"), result);
    }

    // GREATER_THAN with a non-Number non-JsonCustom value (e.g. String) returns empty set
    @Test
    public void test_greater_than_string_value_returns_empty() {
        List<FieldIndexEntry<String>> entries = List.of(new FieldIndexEntry<>("db1", "col1", "apple", Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", "banana", Set.of("id2")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN, "apple");
        assertTrue(result.isEmpty());
    }

    // SMALLER_THAN with a non-Number non-JsonCustom value returns empty set
    @Test
    public void test_smaller_than_string_value_returns_empty() {
        List<FieldIndexEntry<String>> entries = List.of(new FieldIndexEntry<>("db1", "col1", "apple", Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", "banana", Set.of("id2")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, "banana");
        assertTrue(result.isEmpty());
    }

    // Single-element list returns empty for GREATER_THAN (end == 0 branch)
    @Test
    public void test_greater_than_single_element_list_returns_empty() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN, 5);
        assertTrue(result.isEmpty());
    }

    // Single-element list returns empty for SMALLER_THAN (end == 0 branch)
    @Test
    public void test_smaller_than_single_element_list_returns_empty() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, 20);
        assertTrue(result.isEmpty());
    }

    // GREATER_THAN when value is less than the first entry (early-return start branch)
    @Test
    public void test_greater_than_value_below_first_entry_returns_all() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 20, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 30, Set.of("id3")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN, 5);
        assertEquals(Set.of("id1", "id2", "id3"), result);
    }

    // SMALLER_THAN when value is greater than the last entry (early-return end branch)
    @Test
    public void test_smaller_than_value_above_last_entry_returns_all() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 20, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 30, Set.of("id3")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, 40);
        assertEquals(Set.of("id1", "id2", "id3"), result);
    }

    // SMALLER_THAN_EQUALS when value equals last entry (early-return end branch)
    @Test
    public void test_smaller_than_equals_value_equals_last_returns_all() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 20, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 30, Set.of("id3")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN_EQUALS, 30);
        assertEquals(Set.of("id1", "id2", "id3"), result);
    }

    // GREATER_THAN_EQUALS when value equals the first entry (early-return start branch)
    @Test
    public void test_greater_than_equals_value_equals_first_returns_all() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 20, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 30, Set.of("id3")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN_EQUALS, 10);
        assertEquals(Set.of("id1", "id2", "id3"), result);
    }

    // GREATER_THAN returns empty when no value is greater (L92)
    @Test
    public void test_greater_than_no_match_returns_empty() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db", "col", 5.0, Set.of("id1")),
                new FieldIndexEntry<>("db", "col", 10.0, Set.of("id2")));
        // Value is >= max, so no entries are greater than it
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN, 10);
        assertTrue(result.isEmpty());
    }

    // SMALLER_THAN returns empty when no value is smaller (L103)
    @Test
    public void test_smaller_than_no_match_returns_empty_number() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db", "col", 5.0, Set.of("id1")),
                new FieldIndexEntry<>("db", "col", 10.0, Set.of("id2")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, 5);
        assertTrue(result.isEmpty());
    }

    // SMALLER_THAN_EQUALS returns empty when nothing is smaller or equal (L126)
    @Test
    public void test_smaller_than_equals_no_match_returns_empty_number() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db", "col", 10.0, Set.of("id1")),
                new FieldIndexEntry<>("db", "col", 20.0, Set.of("id2")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN_EQUALS, 5);
        assertTrue(result.isEmpty());
    }

    // SMALLER_THAN with JsonCustom returns matching IDs (L103-106)
    @Test
    public void test_smaller_than_custom_type_returns_matching() {
        JsonTime t1 = new JsonTime("#time(08:00:00)");
        JsonTime t2 = new JsonTime("#time(10:00:00)");
        JsonTime t3 = new JsonTime("#time(12:00:00)");
        List<FieldIndexEntry<JsonCustom<?>>> entries = Arrays.asList(
                new FieldIndexEntry<>("db", "col", t1, Set.of("id1")),
                new FieldIndexEntry<>("db", "col", t2, Set.of("id2")),
                new FieldIndexEntry<>("db", "col", t3, Set.of("id3")));
        JsonTime searchTime = new JsonTime("#time(11:00:00)");
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, searchTime);
        assertTrue(result.contains("id1"));
        assertTrue(result.contains("id2"));
        assertFalse(result.contains("id3"));
    }

    // SMALLER_THAN_EQUALS with JsonCustom (L120-123)
    @Test
    public void test_smaller_than_equals_custom_type_returns_matching() {
        JsonTime t1 = new JsonTime("#time(08:00:00)");
        JsonTime t2 = new JsonTime("#time(10:00:00)");
        JsonTime t3 = new JsonTime("#time(12:00:00)");
        List<FieldIndexEntry<JsonCustom<?>>> entries = Arrays.asList(
                new FieldIndexEntry<>("db", "col", t1, Set.of("id1")),
                new FieldIndexEntry<>("db", "col", t2, Set.of("id2")),
                new FieldIndexEntry<>("db", "col", t3, Set.of("id3")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN_EQUALS, t2);
        assertTrue(result.contains("id1"));
        assertTrue(result.contains("id2"));
        assertFalse(result.contains("id3"));
    }

    // Single-element CustomType list returns empty (end==0 early return, L172)
    @Test
    public void test_single_element_custom_type_smaller_than_returns_empty() {
        JsonTime t1 = new JsonTime("#time(10:00:00)");
        List<FieldIndexEntry<JsonCustom<?>>> entries = List.of(new FieldIndexEntry<>("db", "col", t1, Set.of("id1")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, t1);
        assertTrue(result.isEmpty());
    }

    // SMALLER_THAN where value exceeds last entry triggers early return (L178-180)
    @Test
    public void test_smaller_than_custom_value_exceeds_max_returns_all() {
        JsonTime t1 = new JsonTime("#time(08:00:00)");
        JsonTime t2 = new JsonTime("#time(10:00:00)");
        JsonTime tBig = new JsonTime("#time(23:00:00)");
        List<FieldIndexEntry<JsonCustom<?>>> entries = Arrays.asList(
                new FieldIndexEntry<>("db", "col", t1, Set.of("id1")),
                new FieldIndexEntry<>("db", "col", t2, Set.of("id2")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, tBig);
        assertEquals(Set.of("id1", "id2"), result);
    }

    // SMALLER_THAN_EQUALS where value >= last entry triggers early return (L183-186)
    @Test
    public void test_smaller_than_equals_custom_value_equals_max_returns_all() {
        JsonTime t1 = new JsonTime("#time(08:00:00)");
        JsonTime t2 = new JsonTime("#time(10:00:00)");
        List<FieldIndexEntry<JsonCustom<?>>> entries = Arrays.asList(
                new FieldIndexEntry<>("db", "col", t1, Set.of("id1")),
                new FieldIndexEntry<>("db", "col", t2, Set.of("id2")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN_EQUALS, t2);
        assertEquals(Set.of("id1", "id2"), result);
    }

    // GREATER_THAN_EQUALS with CustomType returns empty when no match (L92 equivalent)
    @Test
    public void test_greater_than_equals_custom_no_match_returns_empty() {
        JsonTime t1 = new JsonTime("#time(08:00:00)");
        JsonTime t2 = new JsonTime("#time(10:00:00)");
        JsonTime tBig = new JsonTime("#time(23:00:00)");
        List<FieldIndexEntry<JsonCustom<?>>> entries = Arrays.asList(
                new FieldIndexEntry<>("db", "col", t1, Set.of("id1")),
                new FieldIndexEntry<>("db", "col", t2, Set.of("id2")));
        // tBig > t2, so no entries >= tBig
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN_EQUALS, tBig);
        assertTrue(result.isEmpty());
    }

    // SMALLER_THAN with CustomType returns empty when value is smaller than all entries (L103 empty)
    @Test
    public void test_smaller_than_custom_value_below_min_returns_empty() {
        JsonTime t1 = new JsonTime("#time(10:00:00)");
        JsonTime t2 = new JsonTime("#time(12:00:00)");
        JsonTime tSmall = new JsonTime("#time(05:00:00)");
        List<FieldIndexEntry<JsonCustom<?>>> entries = Arrays.asList(
                new FieldIndexEntry<>("db", "col", t1, Set.of("id1")),
                new FieldIndexEntry<>("db", "col", t2, Set.of("id2")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, tSmall);
        assertTrue(result.isEmpty());
    }
    // The scalar and JsonCustom range paths share one binary search and differ only in how two values
    // compare, so identically-ordered data must answer identically through either.
    @Test
    public void test_custom_and_double_ranges_agree() {
        final List<FieldIndexEntry<JsonCustom<?>>> customEntries = List.of(
                new FieldIndexEntry<>("db", "col", new JsonTime("#time(10:00:00)"), Set.of("a")),
                new FieldIndexEntry<>("db", "col", new JsonTime("#time(11:00:00)"), Set.of("b")),
                new FieldIndexEntry<>("db", "col", new JsonTime("#time(12:00:00)"), Set.of("c")),
                new FieldIndexEntry<>("db", "col", new JsonTime("#time(13:00:00)"), Set.of("d")));
        final List<FieldIndexEntry<Double>> doubleEntries = List.of(
                new FieldIndexEntry<>("db", "col", 10d, Set.of("a")),
                new FieldIndexEntry<>("db", "col", 11d, Set.of("b")),
                new FieldIndexEntry<>("db", "col", 12d, Set.of("c")),
                new FieldIndexEntry<>("db", "col", 13d, Set.of("d")));
        final JsonCustom<?> customOperand = new JsonTime("#time(12:00:00)");

        for (final var operator : List.of(FieldOperatorType.GREATER_THAN, FieldOperatorType.GREATER_THAN_EQUALS,
                FieldOperatorType.SMALLER_THAN, FieldOperatorType.SMALLER_THAN_EQUALS)) {
            assertEquals(SearchUtils.findingByOperator(doubleEntries, operator, 12d),
                    SearchUtils.findingByOperator(customEntries, operator, customOperand),
                    "the custom and scalar range paths disagree for " + operator);
        }
    }
}
