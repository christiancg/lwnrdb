package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.listen.ResultHasher;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;

public class ResultHasherTest {

    @Test
    public void hash_sameResults_returnsSameHash() {
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("abc"));
        final var results = List.of(obj);

        final var hash1 = ResultHasher.hash(results);
        final var hash2 = ResultHasher.hash(results);

        assertEquals(hash1, hash2);
    }

    @Test
    public void hash_emptyResults_returnsNonNullHash() {
        final var hash = ResultHasher.hash(List.of());

        assertNotNull(hash);
        assertFalse(hash.isBlank());
    }

    @Test
    public void hash_differentResults_returnsDifferentHashes() {
        final var obj1 = new JsonObject();
        obj1.add("_id", new JsonString("abc"));
        final var obj2 = new JsonObject();
        obj2.add("_id", new JsonString("xyz"));

        final var hash1 = ResultHasher.hash(List.of(obj1));
        final var hash2 = ResultHasher.hash(List.of(obj2));

        assertNotEquals(hash1, hash2);
    }

    @Test
    public void hash_returnsHexString() {
        final var hash = ResultHasher.hash(List.of());

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    public void hash_differentOrder_returnsDifferentHashes() {
        final var obj1 = new JsonObject();
        obj1.add("_id", new JsonString("a"));
        final var obj2 = new JsonObject();
        obj2.add("_id", new JsonString("b"));

        final var hash1 = ResultHasher.hash(List.of(obj1, obj2));
        final var hash2 = ResultHasher.hash(List.of(obj2, obj1));

        assertNotEquals(hash1, hash2);
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    @Test
    public void test_the_same_set_in_a_different_order_hashes_equal() {
        final var a = document("a");
        final var b = document("b");

        assertEquals(ResultHasher.hash(List.of(a, b), false), ResultHasher.hash(List.of(b, a), false),
                "a pipeline with no SORT has no stable order, so a reshuffle must not read as a change");
    }

    @Test
    public void test_a_changed_document_still_changes_the_hash() {
        final var a = document("a");
        final var b = document("b");

        assertNotEquals(ResultHasher.hash(List.of(a, b), false), ResultHasher.hash(List.of(a, document("c")), false));
    }

    @Test
    public void test_an_ordered_pipeline_keeps_order_sensitivity() {
        final var a = document("a");
        final var b = document("b");

        assertNotEquals(ResultHasher.hash(List.of(a, b), true), ResultHasher.hash(List.of(b, a), true));
    }

    @Test
    public void test_a_pipeline_whose_last_order_determining_step_is_sort_is_order_significant() {
        final var sort = new SortAggregationStep("name", true);
        final var filter = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("x")));

        assertTrue(ResultHasher.ordersResults(List.of(filter, sort)));
        assertTrue(ResultHasher.ordersResults(List.of(sort, filter)));
        assertTrue(ResultHasher.ordersResults(List.of(sort)));
        assertFalse(ResultHasher.ordersResults(List.of(filter)));
        assertFalse(ResultHasher.ordersResults(List.of()));
        assertFalse(ResultHasher.ordersResults(null));
    }

    @Test
    public void test_sort_then_limit_is_order_significant() {
        final var sort = new SortAggregationStep("score", false);
        final var filter = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("x")));

        assertTrue(ResultHasher.ordersResults(List.of(filter, sort, new LimitAggregationStep(10))));
        assertTrue(ResultHasher.ordersResults(List.of(sort, new SkipAggregationStep(5), new LimitAggregationStep(10))));
    }

    @Test
    public void test_sort_followed_by_a_reordering_step_is_not_order_significant() {
        final var sort = new SortAggregationStep("score", false);

        assertFalse(ResultHasher.ordersResults(List.of(sort, new GroupByAggregationStep("category"))));
        assertFalse(ResultHasher.ordersResults(List.of(sort, new DistinctAggregationStep("category"))));
        assertFalse(ResultHasher.ordersResults(List.of(sort, new CountAggregationStep())));
        assertFalse(ResultHasher.ordersResults(List.of(sort, new ReduceAggregationStep("(a, b) => a", null, "total"))));
    }

    @Test
    public void test_a_reorder_inside_a_top_k_changes_the_ordered_hash() {
        final var a = document("a");
        final var b = document("b");

        assertNotEquals(ResultHasher.hash(List.of(a, b), true), ResultHasher.hash(List.of(b, a), true));
        assertEquals(ResultHasher.hash(List.of(a, b), false), ResultHasher.hash(List.of(b, a), false));
    }

    private static CustomOperator nearest() {
        final var args = new JsonObject();
        args.add("k", new JsonNumber(5));
        return new CustomOperator("nearest", "embedding", null, args);
    }

    @Test
    public void test_a_ranking_filter_with_no_trailing_sort_is_order_significant() {
        final var filter = new FilterAggregationStep(nearest());

        assertTrue(ResultHasher.ordersResults(List.of(filter)));
    }

    @Test
    public void test_an_ordinary_filter_with_no_sort_stays_order_insignificant() {
        final var filter = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("x")));

        assertFalse(ResultHasher.ordersResults(List.of(filter)));
    }

    @Test
    public void test_a_sort_before_a_ranking_filter_is_still_order_significant() {
        final var sort = new SortAggregationStep("name", true);
        final var filter = new FilterAggregationStep(nearest());

        assertTrue(ResultHasher.ordersResults(List.of(sort, filter)));
    }

    private static FieldOperator equalsName(String value) {
        return new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString(value));
    }

    @Test
    public void test_orders_results_when_a_ranking_operator_is_nested_in_an_or_conjunction() {
        final var conjunction = new ConjunctionOperator(ConjunctionOperatorType.OR,
                List.of(nearest(), equalsName("x")));
        final var filter = new FilterAggregationStep(conjunction);

        assertTrue(ResultHasher.ordersResults(List.of(filter)));
    }

    @Test
    public void test_a_ranking_operator_nested_in_an_xor_conjunction_hashes_unordered() {
        final var conjunction = new ConjunctionOperator(ConjunctionOperatorType.XOR,
                List.of(nearest(), equalsName("x")));
        final var filter = new FilterAggregationStep(conjunction);

        assertFalse(ResultHasher.ordersResults(List.of(filter)),
                "a source XOR groups its matches, so it does not keep the ranking's order");
    }

    @Test
    public void test_does_not_order_results_when_a_ranking_operator_is_nested_in_an_and_conjunction() {
        final var conjunction = new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(nearest(), equalsName("x")));
        final var filter = new FilterAggregationStep(conjunction);

        assertFalse(ResultHasher.ordersResults(List.of(filter)),
                "AND merges branches through groupingBy, which already scrambles any nested ranking order");
    }

    @Test
    public void test_does_not_order_results_when_a_ranking_operator_is_nested_in_a_nor_or_nand_conjunction() {
        final var nor = new FilterAggregationStep(
                new ConjunctionOperator(ConjunctionOperatorType.NOR, List.of(nearest(), equalsName("x"))));
        final var nand = new FilterAggregationStep(
                new ConjunctionOperator(ConjunctionOperatorType.NAND, List.of(nearest(), equalsName("x"))));

        assertFalse(ResultHasher.ordersResults(List.of(nor)));
        assertFalse(ResultHasher.ordersResults(List.of(nand)));
    }

    @Test
    public void test_orders_results_for_a_nested_conjunction_of_conjunctions() {
        final var inner = new ConjunctionOperator(ConjunctionOperatorType.OR, List.of(nearest(), equalsName("x")));
        final var outer = new ConjunctionOperator(ConjunctionOperatorType.OR, List.of(inner, equalsName("y")));
        final var filter = new FilterAggregationStep(outer);

        assertTrue(ResultHasher.ordersResults(List.of(filter)),
                "the recursion into a conjunction's operands must not be accidentally shallow");
    }
}
