package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.filter.FieldPredicateFactory;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.JsonUtils;

public class FieldPathCallersTest extends FilterResolutionSupport {
    @BeforeEach
    public void seedNested() throws IOException {
        store(document("d1", nested(3, "hello world", "#geo(40.0,-74.0)", "#vector(1.0,0.0)"), "x"));
        store(document("d2", nested(1, "say hello", "#geo(10.0,10.0)", "#vector(0.0,1.0)"), "y"));
        store(document("d3", nested(2, "nothing", null, null), "x"));
        store(document("d4", new JsonString("scalar"), "z"));
        store(document("d5", null, "z"));
    }

    private static JsonObject nested(int n, String s, String geo, String vector) {
        final var inner = new JsonObject();
        inner.add("n", new JsonNumber(n));
        inner.add("s", new JsonString(s));
        if (geo != null) {
            inner.add("loc", new JsonGeo(geo));
        }
        if (vector != null) {
            inner.add("v", new JsonVector(vector));
        }
        return inner;
    }

    private static JsonObject document(String id, JsonBaseElement a, String k) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        if (a != null) {
            object.add("a", a);
        }
        object.add("k", new JsonString(k));
        return object;
    }

    private void store(JsonObject object) throws IOException {
        final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private static FieldOperator op(FieldOperatorType type, String field, JsonBaseElement value) {
        return new FieldOperator(type, field, value);
    }

    @Test
    public void test_a_filter_on_a_nested_path_resolves_through_the_compiled_path() throws IOException {
        assertEquals(Set.of("d3"), matched(op(FieldOperatorType.EQUALS, "a.n", new JsonNumber(2))));
        assertEquals(Set.of("d1", "d3"), matched(op(FieldOperatorType.GREATER_THAN, "a.n", new JsonNumber(1))));
        assertEquals(Set.of("d1", "d2", "d3"), matched(op(FieldOperatorType.NOT_EQUALS, "a.n", new JsonNumber(7))));
    }

    @Test
    public void test_contains_reuses_one_needle_across_rows() throws IOException {
        assertEquals(Set.of("d1", "d2"), matched(op(FieldOperatorType.CONTAINS, "a.s", new JsonString("hello"))));
        final var tester = FieldPredicateFactory.getTester(op(FieldOperatorType.CONTAINS, "s", new JsonString("abab")),
                FieldOperatorType.CONTAINS);
        final var expected = List.of(true, false, true, false, true);
        final var haystacks = List.of("xxababx", "abaab", "aababab", "aba", "abab");
        for (var i = 0; i < haystacks.size(); i++) {
            final var row = new JsonObject();
            row.add("s", new JsonString(haystacks.get(i)));
            assertEquals(expected.get(i), tester.test(row, "s"), haystacks.get(i));
        }
        final var empty = FieldPredicateFactory.getTester(op(FieldOperatorType.CONTAINS, "s", new JsonString("")),
                FieldOperatorType.CONTAINS);
        final var row = new JsonObject();
        row.add("s", new JsonString("anything"));
        assertTrue(empty.test(row, "s"));
        assertTrue(empty.test(row, "s"));
    }

    @Test
    public void test_a_tester_called_with_another_field_resolves_that_field() {
        final var tester = FieldPredicateFactory.getTester(op(FieldOperatorType.EQUALS, "a.n", new JsonString("X")),
                FieldOperatorType.EQUALS);
        final var row = document("r", nested(1, "s", null, null), "x");
        assertTrue(tester.test(row, "k"));
        assertFalse(tester.test(row, "a.n"));
        assertFalse(tester.test(row, "a.k"));
    }

    @Test
    public void test_primary_key_exactness_follows_the_field_the_tester_is_called_with() {
        final var tester = FieldPredicateFactory.getTester(op(FieldOperatorType.EQUALS, "_id", new JsonString("X")),
                FieldOperatorType.EQUALS);
        final var row = document("x", null, "x");
        assertFalse(tester.test(row, "_id"));
        assertFalse(tester.test(row, "_id."));
        assertTrue(tester.test(row, "k"));
        final var onK = FieldPredicateFactory.getTester(op(FieldOperatorType.EQUALS, "k", new JsonString("X")),
                FieldOperatorType.EQUALS);
        assertTrue(onK.test(row, "k"));
        assertFalse(onK.test(row, "_id"));
    }

    @Test
    public void test_membership_resolves_the_nested_path_and_falls_back_for_another_field() throws IOException {
        final var list = arrayOf(new JsonNumber(1), new JsonNumber(2));
        assertEquals(Set.of("d2", "d3"), matched(op(FieldOperatorType.IN, "a.n", list)));
        assertEquals(Set.of("d1"), matched(op(FieldOperatorType.NOT_IN, "a.n", list)));
        final var tester = FieldPredicateFactory.getTester(op(FieldOperatorType.IN, "a.n", list), FieldOperatorType.IN);
        final var row = new JsonObject();
        row.add("m", new JsonNumber(2));
        assertTrue(tester.test(row, "m"));
        assertFalse(tester.test(row, "a.n"));
    }

    @Test
    public void test_a_custom_predicate_on_a_nested_path() throws IOException {
        final var target = new JsonGeo("#geo(40.0,-74.0)");
        final var args = new JsonObject();
        args.add("value", target);
        args.add("comparator", new JsonString("SMALLER_THAN"));
        args.add("distance", new JsonNumber(1000.0));
        final var operator = new CustomOperator("distance", "a.loc", target, args);
        assertEquals(List.of("d1"), ids(new FilterAggregationStep(operator)));
        final var tester = FilterOperatorHelper.getCustomTester(operator);
        final var row = document("r", nested(1, "s", "#geo(40.0,-74.0)", null), "x");
        assertTrue(tester.test(row, "a.loc"));
        assertFalse(tester.test(row, "k"));
    }

    @Test
    public void test_a_ranking_on_a_nested_path() throws IOException {
        final var query = new JsonVector("#vector(1.0,0.0)");
        final var args = new JsonObject();
        args.add("value", query);
        args.add("k", new JsonNumber(1));
        args.add("exact", JsonBoolean.of(true));
        assertEquals(List.of("d1"), ids(new FilterAggregationStep(new CustomOperator("nearest", "a.v", query, args))));
    }

    @Test
    public void test_sort_on_a_nested_path_bounded_and_full() throws IOException {
        final var present = filter(FieldOperatorType.GREATER_THAN, "a.n", new JsonNumber(0));
        assertEquals(List.of("d1", "d3", "d2"), idsInOrder(present, new SortAggregationStep("a.n", false)));
        assertEquals(List.of("d2", "d3"),
                idsInOrder(new SortAggregationStep("a.n", true), present, new LimitAggregationStep(2)));
        assertEquals(List.of("d1", "d3"),
                idsInOrder(present, new SortAggregationStep("a.n", false), new LimitAggregationStep(2)));
    }

    @Test
    public void test_group_by_and_distinct_skip_rows_the_nested_path_misses() throws IOException {
        final var groups = rows(new GroupByAggregationStep("a.n"));
        assertEquals(3, groups.size());
        for (final var group : groups) {
            assertTrue(JsonUtils.getFromPath(group, "a.n").isJsonNumber());
            assertEquals(1, group.get("group").asJsonArray().size());
        }
        assertEquals(List.of("{\"a\":{\"n\":1}}", "{\"a\":{\"n\":2}}", "{\"a\":{\"n\":3}}"),
                sortedJson(rows(new DistinctAggregationStep("a.n"))));
    }

    @Test
    public void test_map_operators_of_one_step_share_operand_paths() throws IOException {
        final var sum = new AddFieldMapOperator("out.total", null,
                new ArrayParamMidOperator(MidOperationType.SUM, arrayOf(new JsonString("a.n"), new JsonString("a.n"))));
        final var size = new AddFieldMapOperator("out.len", null,
                new OneParamMidOperator(MidOperationType.SIZE, "a.s"));
        final var remove = new RemoveFieldMapOperator("a.s", null);
        final var mapped = rows(filter(FieldOperatorType.EQUALS, Globals.PK_FIELD, new JsonString("d1")),
                new MapAggregationStep(List.of(sum, size, remove)));
        assertEquals(1, mapped.size());
        final var row = mapped.getFirst();
        assertEquals(6d, JsonUtils.getFromPath(row, "out.total").asJsonNumber().doubleValue());
        assertEquals(11d, JsonUtils.getFromPath(row, "out.len").asJsonNumber().doubleValue());
        assertFalse(JsonUtils.hasInPath(row, "a.s"));
        assertTrue(JsonUtils.hasInPath(row, "a.n"));
    }
}
