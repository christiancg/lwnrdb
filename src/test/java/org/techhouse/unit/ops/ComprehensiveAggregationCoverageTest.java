package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ComprehensiveAggregationCoverageTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    private void seed(String coll, String id, int n, String s, String... tags) {
        final var obj = new JsonObject();
        obj.addProperty("_id", id);
        obj.addProperty("n", n);
        obj.addProperty("s", s);
        final var tagArr = new JsonArray();
        for (final var t : tags) {
            tagArr.add(new JsonString(t));
        }
        obj.add("tags", tagArr);
        final var request = new SaveRequest(TestGlobals.DB, coll);
        request.setObject(obj);
        processor.processMessage(request);
    }

    private static JsonArray strings(String... values) {
        final var arr = new JsonArray();
        for (final var v : values) {
            arr.add(new JsonString(v));
        }
        return arr;
    }

    private List<JsonObject> run(BaseAggregationStep step) throws Exception {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(step));
        return AggregationOperationHelper.processAggregation(request);
    }

    private List<JsonObject> filter(BaseOperator operator) throws Exception {
        return run(new FilterAggregationStep(operator));
    }

    private List<JsonObject> field(FieldOperatorType type, String f, JsonBaseElement v) throws Exception {
        return filter(new FieldOperator(type, f, v));
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
        seed(TestGlobals.COLL, "d1", 10, "a", "x", "y");
        seed(TestGlobals.COLL, "d2", 20, "b", "y", "z");
        seed(TestGlobals.COLL, "d3", 20, "c", "x");
        seed(TestGlobals.COLL, "d4", 30, "a", "z");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_count_sees_all_seeded_documents() throws Exception {
        assertEquals(4, run(new CountAggregationStep()).getFirst().get("count").asJsonNumber().asInteger());
    }

    @Test
    public void test_scalar_field_operators() throws Exception {
        assertEquals(2, field(FieldOperatorType.EQUALS, "n", new JsonNumber(20)).size());
        assertEquals(2, field(FieldOperatorType.NOT_EQUALS, "n", new JsonNumber(20)).size());
        assertEquals(3, field(FieldOperatorType.GREATER_THAN, "n", new JsonNumber(10)).size());
        assertEquals(3, field(FieldOperatorType.GREATER_THAN_EQUALS, "n", new JsonNumber(20)).size());
        assertEquals(3, field(FieldOperatorType.SMALLER_THAN, "n", new JsonNumber(30)).size());
        assertEquals(3, field(FieldOperatorType.SMALLER_THAN_EQUALS, "n", new JsonNumber(20)).size());
    }

    @Test
    public void test_in_not_in_and_contains_operators() throws Exception {
        assertEquals(3, field(FieldOperatorType.IN, "s", strings("a", "c")).size());
        assertEquals(1, field(FieldOperatorType.NOT_IN, "s", strings("a", "c")).size());
        assertEquals(2, field(FieldOperatorType.CONTAINS, "tags", new JsonString("x")).size());
    }

    @Test
    public void test_conjunction_operators() throws Exception {
        final var geq20 = new FieldOperator(FieldOperatorType.GREATER_THAN_EQUALS, "n", new JsonNumber(20));
        final var sIsA = new FieldOperator(FieldOperatorType.EQUALS, "s", new JsonString("a"));
        assertEquals(1, filter(new ConjunctionOperator(ConjunctionOperatorType.AND, List.of(geq20, sIsA))).size());
        assertTrue(filter(new ConjunctionOperator(ConjunctionOperatorType.OR, List.of(geq20, sIsA))).size() >= 3);
        assertNotNull(filter(new ConjunctionOperator(ConjunctionOperatorType.NOR, List.of(geq20, sIsA))));
        assertNotNull(filter(new ConjunctionOperator(ConjunctionOperatorType.NAND, List.of(geq20, sIsA))));
    }

    @Test
    public void test_map_remove_field() throws Exception {
        final var result = run(new MapAggregationStep(List.of(new RemoveFieldMapOperator("s", null))));
        assertEquals(4, result.size());
    }

    @Test
    public void test_group_sort_distinct_limit_skip() throws Exception {
        assertEquals(3, run(new GroupByAggregationStep("s")).size());
        assertEquals(4, run(new SortAggregationStep("n", true)).size());
        assertEquals("d4", run(new SortAggregationStep("n", false)).getFirst().get("_id").asJsonString().getValue());
        assertEquals(3, run(new DistinctAggregationStep("s")).size());
        assertEquals(2, run(new LimitAggregationStep(2)).size());
        assertEquals(3, run(new SkipAggregationStep(1)).size());
    }

    @Test
    public void test_join_with_matching_remote_documents() throws Exception {
        final var remote = new JsonObject();
        remote.addProperty("_id", "r1");
        remote.addProperty("key", "a");
        final var joinSave = new SaveRequest(TestGlobals.DB, TestGlobals.JOIN_COLL);
        joinSave.setObject(remote);
        processor.processMessage(joinSave);

        final var result = run(new JoinAggregationStep(TestGlobals.JOIN_COLL, "s", "key", "joined"));
        assertEquals(4, result.size());
    }
}
