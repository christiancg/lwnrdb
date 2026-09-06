package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ScriptMidOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The three surfaces end to end, through OperationProcessor, against a populated collection.
 */
public class AggregationScriptIntegrationTest {
    private static final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        for (var i = 1; i <= 5; i++) {
            save("doc" + i, i * 10, i);
        }
    }

    @AfterAll
    public static void tearDownAll() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void save(String id, double price, double qty) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("price", new JsonNumber(price));
        object.add("qty", new JsonNumber(qty));
        request.setObject(object);
        processor.processMessage(request);
    }

    private static AggregateRequest aggregate(BaseAggregationStep... steps) {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return request;
    }

    private static List<JsonObject> run(BaseAggregationStep... steps) {
        final var response = processor.processMessage(aggregate(steps));
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        return ((AggregateResponse) response).getResults();
    }

    @Test
    public void test_computed_field_end_to_end() {
        final var map = new MapAggregationStep(List.of(new AddFieldMapOperator("total", null,
                new ScriptMidOperator("export default (doc) => doc.price * doc.qty;"))));
        final var results = run(map);
        assertEquals(5, results.size());
        for (final var result : results) {
            final var expected = result.get("price").asJsonNumber().getValue().doubleValue()
                    * result.get("qty").asJsonNumber().getValue().doubleValue();
            assertEquals(expected, result.get("total").asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_script_predicate_end_to_end() {
        final var filter = new FilterAggregationStep(
                new ScriptOperator("export default (doc) => doc.price > 25 && doc.qty % 2 === 0;"));
        final var results = run(filter);
        assertEquals(1, results.size());
        assertEquals("doc4", results.getFirst().get("_id").asJsonString().getValue());
    }

    @Test
    public void test_reduce_end_to_end() {
        final var reduce = new ReduceAggregationStep("export default (acc, doc) => acc + doc.price * doc.qty;",
                new JsonNumber(0), "total");
        final var results = run(reduce);
        assertEquals(1, results.size());
        assertEquals(550d, results.getFirst().get("total").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_pipeline_combining_filter_then_script_predicate_then_reduce() {
        final var indexed = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.GREATER_THAN, "price", new JsonNumber(15)));
        final var scripted = new FilterAggregationStep(new ScriptOperator("export default (doc) => doc.qty < 5;"));
        final var reduce = new ReduceAggregationStep("export default (acc, doc) => acc + 1;", new JsonNumber(0), "n");
        final var results = run(indexed, scripted, reduce);
        assertEquals(3d, results.getFirst().get("n").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_analyze_reports_script_counters_and_the_no_index_suggestion() {
        final var request = aggregate(new FilterAggregationStep(new ScriptOperator("export default (doc) => true;")));
        request.setAnalyze(true);
        final var response = (AggregateAnalyzeResponse) processor.processMessage(request);
        assertEquals(5, response.getAnalyzeResult().getScriptInvocations());
        assertTrue(response.getAnalyzeResult().getScriptMillis() >= 0);
        assertTrue(
                response.getAnalyzeResult().getSuggestions().stream()
                        .anyMatch(s -> s.contains("can never use an index")),
                response.getAnalyzeResult().getSuggestions().toString());
    }

    @Test
    public void test_analyze_suggests_filtering_before_a_reduce() {
        final var request = aggregate(
                new ReduceAggregationStep("export default (acc, doc) => acc + 1;", new JsonNumber(0), "n"));
        request.setAnalyze(true);
        final var response = (AggregateAnalyzeResponse) processor.processMessage(request);
        assertTrue(response.getAnalyzeResult().getSuggestions().stream()
                .anyMatch(s -> s.contains("consumes the whole upstream stream")));
    }

    // The stream is lazy, so the callable is invoked while toList() runs - a context closed at the end of
    // the loop instead of around the terminal operation fails here on the first document.
    @Test
    public void test_context_stays_open_through_a_lazy_stream() {
        final var map = new MapAggregationStep(List.of(new AddFieldMapOperator("total", null,
                new ScriptMidOperator("export default (doc) => doc.price * 2;"))));
        final var results = run(map, new LimitAggregationStep(1));
        assertEquals(1, results.size());
        assertNotNull(results.getFirst().get("total"));
    }

    @Test
    public void test_budget_spans_the_whole_pipeline_not_each_document() throws Exception {
        final var configuration = Configuration.getInstance();
        final var previous = configuration.getAggregationScriptInstructionBudget();
        try {
            TestUtils.setPrivateField(configuration, "aggregationScriptInstructionBudget", 3000L);
            final var source = "export default (doc) => { let n = 0; for (let i = 0; i < 1000; i++) { n += i; }"
                    + " return true; };";
            // One document fits inside the budget; the same script over all five does not - which is the
            // difference between a per-document budget and a per-pipeline one.
            final var single = processor.processMessage(
                    aggregate(new LimitAggregationStep(1), new FilterAggregationStep(new ScriptOperator(source))));
            assertEquals(OperationStatus.OK, single.getStatus(), single.getMessage());
            final var response = processor
                    .processMessage(aggregate(new FilterAggregationStep(new ScriptOperator(source))));
            assertEquals("400-11", response.getErrorCode(), response.getMessage());
        } finally {
            TestUtils.setPrivateField(configuration, "aggregationScriptInstructionBudget", previous);
        }
    }

    @Test
    public void test_timeout_returns_408() throws Exception {
        final var configuration = Configuration.getInstance();
        final var previous = configuration.getAggregationScriptTimeoutMs();
        try {
            TestUtils.setPrivateField(configuration, "aggregationScriptTimeoutMs", 20L);
            final var filter = new FilterAggregationStep(new ScriptOperator(
                    "export default (doc) => { let n = 0; for (let i = 0; i < 3000000; i++) { n += i; }"
                            + " return true; };"));
            final var response = processor.processMessage(aggregate(filter));
            assertEquals("408-1", response.getErrorCode(), response.getMessage());
        } finally {
            TestUtils.setPrivateField(configuration, "aggregationScriptTimeoutMs", previous);
        }
    }

    @Test
    public void test_throwing_script_returns_400_9() {
        final var filter = new FilterAggregationStep(
                new ScriptOperator("export default (doc) => { throw new Error('boom'); };"));
        final var response = processor.processMessage(aggregate(filter));
        assertEquals("400-9", response.getErrorCode());
        assertEquals("boom", response.getMessage());
    }

    @Test
    public void test_script_exporting_nothing_returns_400_9() {
        final var filter = new FilterAggregationStep(new ScriptOperator("const x = 1;"));
        final var response = processor.processMessage(aggregate(filter));
        assertEquals("400-9", response.getErrorCode());
    }

    @Test
    public void test_step_after_reduce_operates_on_the_single_document() {
        final var reduce = new ReduceAggregationStep("export default (acc, doc) => acc + 1;", new JsonNumber(0), "n");
        final var map = new MapAggregationStep(List.of(
                new AddFieldMapOperator("doubled", null, new ScriptMidOperator("export default (doc) => doc.n * 2;"))));
        final var results = run(reduce, map);
        assertEquals(1, results.size());
        assertEquals(10d, results.getFirst().get("doubled").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_one_source_used_in_filter_and_map_shares_a_callable() {
        final var source = "export default (doc) => doc.price;";
        final var filter = new FilterAggregationStep(new ScriptOperator(source));
        final var map = new MapAggregationStep(
                List.of(new AddFieldMapOperator("p", null, new ScriptMidOperator(source))));
        assertEquals(5, run(filter, map).size());
    }

    @Test
    public void test_script_has_no_database_access() {
        final var filter = new FilterAggregationStep(
                new ScriptOperator("import db from 'db'; export default (doc) => true;"));
        final var response = processor.processMessage(aggregate(filter));
        assertEquals("400-9", response.getErrorCode());
    }
}
