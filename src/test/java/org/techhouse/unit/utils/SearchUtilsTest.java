package org.techhouse.unit.utils;

import org.junit.jupiter.api.Test;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.utils.SearchUtils;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

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

        List<FieldIndexEntry<String>> entries = Arrays.asList(
                new FieldIndexEntry<>("db1", "col1", "value1", ids1),
                new FieldIndexEntry<>("db1", "col1", "value2", ids2)
        );

        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.EQUALS, "value1");

        assertEquals(ids1, result);
    }

    // Empty entries list returns empty set for all operators
    @Test
    public void test_empty_entries_returns_empty_set2() {
        List<FieldIndexEntry<String>> emptyEntries = Collections.emptyList();

        Set<String> equalsResult = SearchUtils.findingByOperator(emptyEntries, FieldOperatorType.EQUALS, "value");
        Set<String> greaterResult = SearchUtils.findingByOperator(emptyEntries, FieldOperatorType.GREATER_THAN, "value");
        Set<String> containsResult = SearchUtils.findingByOperator(emptyEntries, FieldOperatorType.CONTAINS, "value");

        assertTrue(equalsResult.isEmpty());
        assertTrue(greaterResult.isEmpty());
        assertTrue(containsResult.isEmpty());
    }

    // Returns all non-matching IDs when using NOT_EQUALS operator
    @Test
    public void test_not_equals_operator() {
        List<FieldIndexEntry<String>> entries = Stream.of(
                new FieldIndexEntry<>("db1", "col1", "value1", Set.of("id1", "id2")),
                new FieldIndexEntry<>("db1", "col1", "value2", Set.of("id3")),
                new FieldIndexEntry<>("db1", "col1", "value3", Set.of("id4"))
        ).toList();
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.NOT_EQUALS, "value2");
        assertEquals(Set.of("id1", "id2", "id4"), result);
    }

    // Returns IDs of entries greater than numeric value using GREATER_THAN operator
    @Test
    public void test_greater_than_operator() {
        List<FieldIndexEntry<Number>> entries = List.of(
                new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 20, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 30, Set.of("id3"))
        );
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN, 15);
        assertEquals(Set.of("id2", "id3"), result);
    }

    // Returns IDs of entries greater or equal to value using GREATER_THAN_EQUALS
    @Test
    public void test_greater_than_equals_operator() {
        List<FieldIndexEntry<Number>> entries = List.of(
                new FieldIndexEntry<>("db1", "col1", 10, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 20, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 30, Set.of("id3"))
        );
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.GREATER_THAN_EQUALS, 20);
        assertEquals(Set.of("id2", "id3"), result);
    }

    // Returns IDs of entries less than numeric value using SMALLER_THAN operator
    @Test
    public void test_finding_by_operator_smaller_than() {
        List<FieldIndexEntry<Number>> entries = List.of(
                new FieldIndexEntry<>("db1", "col1", 5, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 10, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 15, Set.of("id3"))
        );
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN, 12);
        assertEquals(Set.of("id1", "id2"), result);
    }

    // Returns IDs of string entries containing substring using CONTAINS operator
    @Test
    public void test_finding_by_operator_contains() {
        List<FieldIndexEntry<String>> entries = List.of(
                new FieldIndexEntry<>("db1", "col1", "hello world", Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", "world peace", Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", "hello universe", Set.of("id3"))
        );
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.CONTAINS, "world");
        assertEquals(Set.of("id1", "id2"), result);
    }

    // Returns IDs of entries less or equal to value using SMALLER_THAN_EQUALS
    @Test
    public void test_finding_by_operator_smaller_than_equals() {
        List<FieldIndexEntry<Number>> entries = List.of(
                new FieldIndexEntry<>("db1", "col1", 5, Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", 10, Set.of("id2")),
                new FieldIndexEntry<>("db1", "col1", 15, Set.of("id3"))
        );
        Set<String> result = SearchUtils.findingByOperator(entries, FieldOperatorType.SMALLER_THAN_EQUALS, 10);
        assertEquals(Set.of("id1", "id2"), result);
    }

    // Throws UnsupportedOperationException for IN operator
    @Test
    public void test_unsupported_operation_in_operator() {
        List<FieldIndexEntry<String>> entries = List.of(
                new FieldIndexEntry<>("db1", "collection1", "value1", Set.of("id1")),
                new FieldIndexEntry<>("db1", "collection1", "value2", Set.of("id2"))
        );
        assertThrows(UnsupportedOperationException.class, () -> SearchUtils.findingByOperator(entries, FieldOperatorType.IN, "value"));
    }

    // Create a happy path test of the findingbyoperator method using the operator types IN and NOT_IN
    @Test
    public void test_in_and_not_in_operators_throw_exception() {
        Set<String> ids1 = Set.of("id1", "id2");
        Set<String> ids2 = Set.of("id3");

        List<FieldIndexEntry<String>> entries = Arrays.asList(
                new FieldIndexEntry<>("db1", "col1", "value1", ids1),
                new FieldIndexEntry<>("db1", "col1", "value2", ids2)
        );

        assertThrows(UnsupportedOperationException.class, () -> SearchUtils.findingByOperator(entries, FieldOperatorType.IN, "value1"));

        assertThrows(UnsupportedOperationException.class, () -> SearchUtils.findingByOperator(entries, FieldOperatorType.NOT_IN, "value2"));
    }

    // IN operator returns matching IDs when values exist in entries
    @Test
    public void test_in_operator_returns_matching_ids() {
        Set<String> ids1 = new HashSet<>(Arrays.asList("1", "2"));
        Set<String> ids2 = new HashSet<>(Arrays.asList("3", "4"));

        List<FieldIndexEntry<String>> entries = Arrays.asList(
                new FieldIndexEntry<>("db", "col", "value1", ids1),
                new FieldIndexEntry<>("db", "col", "value2", ids2)
        );

        List<String> searchValues = Arrays.asList("value1", "value2");

        Set<String> result = SearchUtils.findingInNotIn(entries, FieldOperatorType.IN, searchValues);

        assertEquals(4, result.size());
        assertTrue(result.containsAll(Arrays.asList("1", "2", "3", "4")));
    }

    // Null entries list throws NullPointerException
    @Test
    public void test_null_entries_throws_npe() {
        List<String> searchValues = List.of("value1");

        assertThrows(NullPointerException.class, () -> SearchUtils.findingInNotIn(null, FieldOperatorType.IN, searchValues));
    }

    // NOT_IN operator returns IDs for entries not in the value list
    @Test
    public void test_not_in_operator_returns_ids_not_in_value_list() {
        List<FieldIndexEntry<String>> entries = List.of(
                new FieldIndexEntry<>("db1", "col1", "value1", Set.of("id1", "id2")),
                new FieldIndexEntry<>("db1", "col1", "value2", Set.of("id3")),
                new FieldIndexEntry<>("db1", "col1", "value3", Set.of("id4"))
        );
        List<String> value = List.of("value1", "value3");
        Set<String> result = SearchUtils.findingInNotIn(entries, FieldOperatorType.NOT_IN, value);
        assertEquals(Set.of("id3"), result);
    }

    // Empty value list with IN operator returns empty set
    @Test
    public void test_empty_value_list_with_in_operator_returns_empty_set() {
        List<FieldIndexEntry<String>> entries = List.of(
                new FieldIndexEntry<>("db1", "col1", "value1", Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", "value2", Set.of("id2"))
        );
        List<String> value = Collections.emptyList();
        Set<String> result = SearchUtils.findingInNotIn(entries, FieldOperatorType.IN, value);
        assertTrue(result.isEmpty());
    }

    // Empty value list with NOT_IN operator returns all IDs
    @Test
    public void test_empty_value_list_with_not_in_operator_returns_all_ids() {
        List<FieldIndexEntry<String>> entries = List.of(
                new FieldIndexEntry<>("db1", "col1", "value1", Set.of("id1")),
                new FieldIndexEntry<>("db1", "col1", "value2", Set.of("id2"))
        );
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
                new FieldIndexEntry<>("db", "col", jsonTime1, ids2)
        );

        List<JsonCustom<?>> searchValues = Arrays.asList(jsonDateTime1, jsonTime2);

        Set<String> resultIn = SearchUtils.findingInNotIn(entries, FieldOperatorType.IN, searchValues);
        assertEquals(2, resultIn.size());
        assertTrue(resultIn.contains("1"));

        Set<String> resultNotIn = SearchUtils.findingInNotIn(entries, FieldOperatorType.NOT_IN, searchValues);
        assertEquals(2, resultNotIn.size());
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
                new FieldIndexEntry<>("db1", "coll1", jsonDateTime2, ids2)
        );

        List<FieldIndexEntry<JsonCustom<?>>> timeEntries = Arrays.asList(
                new FieldIndexEntry<>("db1", "coll1", jsonTime1, ids1),
                new FieldIndexEntry<>("db1", "coll1", jsonTime2, ids2)
        );

        Set<String> resultDateTime = SearchUtils.findingByOperator(datetimeEntries, FieldOperatorType.GREATER_THAN, jsonDateTime1);
        Set<String> resultTime = SearchUtils.findingByOperator(timeEntries, FieldOperatorType.GREATER_THAN_EQUALS, jsonTime1);

        assertEquals(ids2, resultDateTime);
        final var allIdSet = new HashSet<>(ids1);
        allIdSet.addAll(ids2);
        assertEquals(allIdSet, resultTime);
    }
}