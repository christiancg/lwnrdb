package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.PipelineContext;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class RankedConjunctionTest {
    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject doc(String id, String vector, int group) {
        final var obj = new JsonObject();
        obj.add("_id", new JsonString(id));
        obj.add("embedding", new JsonVector(vector));
        obj.add("group", new JsonNumber(group));
        return obj;
    }

    private static List<JsonObject> rows() {
        return List.of(doc("a", "#vector(1.0,0.0)", 1), doc("b", "#vector(0.9,0.1)", 2),
                doc("c", "#vector(0.0,1.0)", 1), doc("d", "#vector(0.7,0.3)", 2), doc("e", "#vector(-1.0,0.0)", 1));
    }

    private static CustomOperator nearest(int k) {
        final var query = new JsonVector("#vector(1.0,0.0)");
        final var args = new JsonObject();
        args.add("value", query);
        args.add("k", new JsonNumber(k));
        return new CustomOperator("nearest", "embedding", query, args);
    }

    private static FieldOperator group(int value) {
        return new FieldOperator(FieldOperatorType.EQUALS, "group", new JsonNumber(value));
    }

    private static FilterAggregationStep filter(ConjunctionOperatorType type, BaseOperator... legs) {
        return new FilterAggregationStep(new ConjunctionOperator(type, List.of(legs)));
    }

    private static List<String> sortedIds(List<JsonObject> rows) {
        return rows.stream().map(row -> row.get("_id").asJsonString().getValue()).sorted().toList();
    }

    private static List<String> overInput(BaseAggregationStep step) throws Exception {
        return sortedIds(AggregationOperationHelper.processStepsOnStream(List.of(step), rows().stream()));
    }

    private static List<String> overCollection(BaseAggregationStep step) {
        final var processor = IocContainer.get(OperationProcessor.class);
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(new ArrayList<>(rows()));
        processor.processMessage(bulk);
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(step));
        final var response = processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        return sortedIds(((AggregateResponse) response).getResults());
    }

    @Test
    public void test_a_ranking_leg_over_an_input_ranks_the_buffered_rows_once() throws Exception {
        assertEquals(List.of("a"), overInput(filter(ConjunctionOperatorType.AND, nearest(2), group(1))));
        assertEquals(List.of("a", "b", "c", "e"), overInput(filter(ConjunctionOperatorType.OR, nearest(2), group(1))));
        assertEquals(List.of("b", "c", "e"), overInput(filter(ConjunctionOperatorType.XOR, nearest(2), group(1))));
    }

    @Test
    public void test_a_ranking_leg_at_the_source_ranks_the_collection_once() {
        assertEquals(List.of("c", "e"), overCollection(filter(ConjunctionOperatorType.NOR, nearest(2), group(2))));
    }

    @Test
    public void test_a_nested_ranking_leg_resolves_against_the_same_rows() throws Exception {
        final var nested = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of(nearest(3), group(2)));

        assertEquals(List.of("a", "b", "d"), overInput(filter(ConjunctionOperatorType.XOR, nested, nearest(1))));
    }

    @Test
    public void test_a_pipeline_can_resume_from_a_later_step_over_a_given_source() throws Exception {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SkipAggregationStep(100), new LimitAggregationStep(2)));
        try (var context = PipelineContext.of(0L, Long.MAX_VALUE, null)) {
            final var resumed = AggregationOperationHelper.processAggregation(request, rows().stream(), 1, null,
                    context);

            assertEquals(List.of("a", "b"), sortedIds(resumed));
        }
    }
}
