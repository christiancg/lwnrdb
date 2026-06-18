package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.utils.SearchUtils;

public class SearchUtilsTest {
    // Binary search correctly finds exact matches for EQUALS operator
    @Test
    public void test_binary_search_finds_exact_match() {
        List<FieldIndexEntry<Integer>> entries = new ArrayList<>();
        entries.add(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")));
        entries.add(new FieldIndexEntry<>("db1", "col1", 20, Set.of("id2")));
        entries.add(new FieldIndexEntry<>("db1", "col1", 30, Set.of("id3")));

        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.EQUALS, 20);

        assertEquals(Set.of("id2"), result);
    }

    // Empty list of entries returns empty result set
    @Test
    public void test_empty_entries_returns_empty_set() {
        List<FieldIndexEntry<Integer>> entries = new ArrayList<>();

        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.EQUALS, 10);

        assertTrue(result.isEmpty());
    }

    // Returns matching IDs when using EQUALS operator with existing value
    @Test
    public void test_equals_operator_returns_matching_ids() {
        Set<String> ids1 = Set.of("id1", "id2");
        Set<String> ids2 = Set.of("id3");

        List<FieldIndexEntry<String>> entries = Arrays.asList(new FieldIndexEntry<>("db1", "col1", "value1", ids1),
                new FieldIndexEntry<>("db1", "col1", "value2", ids2));

        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.EQUALS, "value1");

        assertEquals(ids1, result);
    }

    // Empty entries list returns empty set for all operators
    @Test
    public void test_empty_entries_returns_empty_set2() {
        List<FieldIndexEntry<String>> emptyEntries = Collections.emptyList();

        Set<String> equalsResult = SearchUtils.findingByOperator(emptyEntries, FieldOperatorType.EQUALS, "value");
        Set<String> greaterResult = SearchUtils.findingByOperator(emptyEntries, FieldOperatorType.GREATER_THAN,
                "value");
        Set<String> containsResult = SearchUtils.findingByOperator(emptyEntries, FieldOperatorType.CONTAINS, "value");

        assertTrue(equalsResult.isEmpty());
        assertTrue(greaterResult.isEmpty());
        assertTrue(containsResult.isEmpty());
    }

    // Returns all non-matching IDs when using NOT_EQUALS operator
    @Test
    public void test_not_equals_operator() {
        List<FieldIndexEntry<String>> entries = Stream
                .of(new FieldIndexEntry<>("db1", "col1", "value1", Set.of("id1", "id2")),
                        new FieldIndexEntry<>("db1", "col1", "value2", Set.of("id3")),
                        new FieldIndexEntry<>("db1", "col1", "value3", Set.of("id4")))
                .toList();
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.NOT_EQUALS, "value2");
        assertEquals(Set.of("id1", "id2", "id4"), result);
    }

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

    // Returns IDs of string entries containing substring using CONTAINS operator
    @Test
    public void test_finding_by_operator_contains() {
        List<FieldIndexEntry<String>> entries = List.of(
                new FieldIndexEntry<>("db1", "col1", "hello world", Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", "world peace", Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", "hello universe", Set.of("id3")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.CONTAINS, "world");
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

    // Throws UnsupportedOperationException for IN operator
    @Test
    public void test_unsupported_operation_in_operator() {
        List<FieldIndexEntry<String>> entries = List.of(
                new FieldIndexEntry<>("db1", "collection1", "value1", Set.of("id1")),
                new FieldIndexEntry<>("db1", "collection1", "value2", Set.of("id2")));
        assertThrows(UnsupportedOperationException.class,
                () -> SearchUtils.findingByOperator(entries, FieldOperatorType.IN, "value"));
    }

    // Create a happy path test of the finding by operator method using the operator types IN and NOT_IN
    @Test
    public void test_in_and_not_in_operators_throw_exception() {
        Set<String> ids1 = Set.of("id1", "id2");
        Set<String> ids2 = Set.of("id3");

        List<FieldIndexEntry<String>> entries = Arrays.asList(new FieldIndexEntry<>("db1", "col1", "value1", ids1),
                new FieldIndexEntry<>("db1", "col1", "value2", ids2));

        assertThrows(UnsupportedOperationException.class,
                () -> SearchUtils.findingByOperator(entries, FieldOperatorType.IN, "value1"));

        assertThrows(UnsupportedOperationException.class,
                () -> SearchUtils.findingByOperator(entries, FieldOperatorType.NOT_IN, "value2"));
    }

    // IN operator returns matching IDs when values exist in entries
    @Test
    public void test_in_operator_returns_matching_ids() {
        Set<String> ids1 = new HashSet<>(Arrays.asList("1", "2"));
        Set<String> ids2 = new HashSet<>(Arrays.asList("3", "4"));

        List<FieldIndexEntry<String>> entries = Arrays.asList(new FieldIndexEntry<>("db", "col", "value1", ids1),
                new FieldIndexEntry<>("db", "col", "value2", ids2));

        List<String> searchValues = Arrays.asList("value1", "value2");

        Set<String> result = SearchUtils.findingInNotIn(entries, FieldOperatorType.IN, searchValues);

        assertEquals(4, result.size());
        assertTrue(result.containsAll(Arrays.asList("1", "2", "3", "4")));
    }

    // Null entries list throws NullPointerException
    @Test
    public void test_null_entries_throws_npe() {
        List<String> searchValues = List.of("value1");

        assertThrows(NullPointerException.class,
                () -> SearchUtils.findingInNotIn(null, FieldOperatorType.IN, searchValues));
    }

    // NOT_IN operator returns IDs for entries not in the value list
    @Test
    public void test_not_in_operator_returns_ids_not_in_value_list() {
        List<FieldIndexEntry<String>> entries = List.of(
                new FieldIndexEntry<>("db1", "col1", "value1", Set.of("id1", "id2")),
                new FieldIndexEntry<>("db1", "col1", "value2", Set.of("id3")),
                new FieldIndexEntry<>("db1", "col1", "value3", Set.of("id4")));
        List<String> value = List.of("value1", "value3");
        Set<String> result = SearchUtils.findingInNotIn(entries, FieldOperatorType.NOT_IN, value);
        assertEquals(Set.of("id3"), result);
    }

    // Empty value list with IN operator returns empty set
    @Test
    public void test_empty_value_list_with_in_operator_returns_empty_set() {
        List<FieldIndexEntry<String>> entries = List.of(new FieldIndexEntry<>("db1", "col1", "value1", Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", "value2", Set.of("id2")));
        List<String> value = Collections.emptyList();
        Set<String> result = SearchUtils.findingInNotIn(entries, FieldOperatorType.IN, value);
        assertTrue(result.isEmpty());
    }

    // Empty value list with NOT_IN operator returns all IDs
    @Test
    public void test_empty_value_list_with_not_in_operator_returns_all_ids() {
        List<FieldIndexEntry<String>> entries = List.of(new FieldIndexEntry<>("db1", "col1", "value1", Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", "value2", Set.of("id2")));
        List<String> value = Collections.emptyList();
        Set<String> result = SearchUtils.findingInNotIn(entries, FieldOperatorType.NOT_IN, value);
        assertEquals(Set.of("id1", "id2"), result);
    }

    // Test the findingByOperator method using JsonDateTime entries and JsonTime entries
    @Test
    public void test_finding_in_not_in_with_json_datetime_and_json_time() {
        Set<String> ids1 = new HashSet<>(Arrays.asList("1", "2"));
        Set<String> ids2 = new HashSet<>(Arrays.asList("3", "4"));

        JsonDateTime jsonDateTime1 = new JsonDateTime("#datetime(2023-10-01T10:00:00)");
        JsonTime jsonTime1 = new JsonTime("#time(10:00:00)");
        JsonTime jsonTime2 = new JsonTime("#time(11:00:00)");

        List<FieldIndexEntry<JsonCustom<?>>> entries = Arrays.asList(
                new FieldIndexEntry<>("db", "col", jsonDateTime1, ids1),
                new FieldIndexEntry<>("db", "col", jsonTime1, ids2));

        List<JsonCustom<?>> searchValues = Arrays.asList(jsonDateTime1, jsonTime2);

        Set<String> resultIn = SearchUtils.findingInNotIn(entries, FieldOperatorType.IN, searchValues);
        assertEquals(2, resultIn.size());
        assertTrue(resultIn.contains("1"));

        Set<String> resultNotIn = SearchUtils.findingInNotIn(entries, FieldOperatorType.NOT_IN, searchValues);
        assertEquals(2, resultNotIn.size());
    }

    // CONTAINS with a non-String value returns empty set
    @Test
    public void test_contains_non_string_value_returns_empty() {
        List<FieldIndexEntry<Number>> entries = List.of(new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 20, Set.of("id2")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.CONTAINS, 10);
        assertTrue(result.isEmpty());
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

    // findingInNotIn throws UnsupportedOperationException for non-IN/NOT_IN operators
    @Test
    public void test_finding_in_not_in_throws_for_non_in_operators() {
        List<FieldIndexEntry<String>> entries = List.of(new FieldIndexEntry<>("db1", "col1", "value1", Set.of("id1")));
        assertThrows(UnsupportedOperationException.class,
                () -> SearchUtils.findingInNotIn(entries, FieldOperatorType.EQUALS, List.of("value1")));
        assertThrows(UnsupportedOperationException.class,
                () -> SearchUtils.findingInNotIn(entries, FieldOperatorType.CONTAINS, List.of("value1")));
        assertThrows(UnsupportedOperationException.class,
                () -> SearchUtils.findingInNotIn(entries, FieldOperatorType.GREATER_THAN, List.of("value1")));
    }

    // Test the method using JsonDateTime and JsonTime entries
    @Test
    public void test_json_datetime_and_json_time_operators() {
        Set<String> ids1 = Set.of("1", "2");
        Set<String> ids2 = Set.of("3", "4");

        JsonDateTime jsonDateTime1 = new JsonDateTime("#datetime(2023-10-01T10:00:00)");
        JsonDateTime jsonDateTime2 = new JsonDateTime("#datetime(2023-10-02T10:00:00)");
        JsonTime jsonTime1 = new JsonTime("#time(10:00:00)");
        JsonTime jsonTime2 = new JsonTime("#time(11:00:00)");

        List<FieldIndexEntry<JsonCustom<?>>> datetimeEntries = Arrays.asList(
                new FieldIndexEntry<>("db1", "coll1", jsonDateTime1, ids1),
                new FieldIndexEntry<>("db1", "coll1", jsonDateTime2, ids2));

        List<FieldIndexEntry<JsonCustom<?>>> timeEntries = Arrays.asList(
                new FieldIndexEntry<>("db1", "coll1", jsonTime1, ids1),
                new FieldIndexEntry<>("db1", "coll1", jsonTime2, ids2));

        Set<String> resultDateTime = SearchUtils.findingByOperator(datetimeEntries, FieldOperatorType.GREATER_THAN,
                jsonDateTime1);
        Set<String> resultTime = SearchUtils.findingByOperator(timeEntries, FieldOperatorType.GREATER_THAN_EQUALS,
                jsonTime1);

        assertEquals(ids2, resultDateTime);
        final var allIdSet = new HashSet<>(ids1);
        allIdSet.addAll(ids2);
        assertEquals(allIdSet, resultTime);
    }

    // SearchUtils instantiation covers implicit default constructor (L14)
    @Test
    public void test_search_utils_instantiation() {
        assertNotNull(new SearchUtils());
    }

    // NOT_EQUALS where value is NOT in entries → else branch returns all (L43)
    @Test
    public void test_not_equals_value_not_in_entries_returns_all() {
        List<FieldIndexEntry<String>> entries = List.of(new FieldIndexEntry<>("db", "col", "a", Set.of("id1")),
                new FieldIndexEntry<>("db", "col", "b", Set.of("id2")));
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.NOT_EQUALS, "z");
        assertEquals(Set.of("id1", "id2"), result);
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
}
