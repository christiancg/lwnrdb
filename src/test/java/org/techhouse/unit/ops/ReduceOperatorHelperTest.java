package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.PipelineScriptContext;
import org.techhouse.ops.ReduceOperatorHelper;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReduceOperatorHelperTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, double price) {
        final var document = new JsonObject();
        document.add("_id", new JsonString(id));
        document.add("price", new JsonNumber(price));
        return document;
    }

    private static Stream<JsonObject> documents() {
        return Stream.of(document("1", 5), document("2", 15), document("3", 25));
    }

    private static JsonObject fold(ReduceAggregationStep step, Stream<JsonObject> source) throws IOException {
        try (var context = new PipelineScriptContext()) {
            final var results = ReduceOperatorHelper
                    .processReduceStep(step, source, TestGlobals.DB, TestGlobals.COLL, context).toList();
            assertEquals(1, results.size());
            return results.getFirst();
        }
    }

    @Test
    public void test_folds_to_a_single_document() throws IOException {
        final var step = new ReduceAggregationStep("export default (acc, doc) => acc + doc.price;", new JsonNumber(0),
                "total");
        assertEquals(45d, fold(step, documents()).get("total").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_uses_default_result_field_value() throws IOException {
        final var step = new ReduceAggregationStep("export default (acc, doc) => acc + doc.price;", new JsonNumber(0),
                null);
        assertEquals(45d, fold(step, documents()).get("value").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_blank_result_field_falls_back_to_the_default() throws IOException {
        final var step = new ReduceAggregationStep("export default (acc, doc) => acc + doc.price;", new JsonNumber(0),
                "  ");
        assertNotNull(fold(step, documents()).get("value"));
    }

    @Test
    public void test_empty_stream_yields_the_initial_value() throws IOException {
        final var step = new ReduceAggregationStep("export default (acc, doc) => acc + doc.price;", new JsonNumber(7),
                "total");
        assertEquals(7d, fold(step, Stream.of()).get("total").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_absent_initial_value_starts_at_json_null() throws IOException {
        final var step = new ReduceAggregationStep("export default (acc, doc) => (acc ?? 0) + doc.price;", null,
                "total");
        assertEquals(45d, fold(step, documents()).get("total").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_object_accumulator() throws IOException {
        final var step = new ReduceAggregationStep(
                "export default (acc, doc) => ({ n: acc.n + 1, sum: acc.sum + doc.price });", initialObject(), "stats");
        final var result = fold(step, documents()).get("stats").asJsonObject();
        assertEquals(3d, result.get("n").asJsonNumber().getValue().doubleValue());
        assertEquals(45d, result.get("sum").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_array_accumulator() throws IOException {
        final var step = new ReduceAggregationStep("export default (acc, doc) => [...acc, doc._id];", new JsonArray(),
                "ids");
        final var result = fold(step, documents()).get("ids").asJsonArray();
        assertEquals(3, result.size());
        assertEquals("1", result.get(0).asJsonString().getValue());
    }

    @Test
    public void test_script_throwing_surfaces_a_script_failure() {
        final var step = new ReduceAggregationStep("export default (acc, doc) => { throw new Error('boom'); };",
                new JsonNumber(0), "total");
        final var error = assertThrows(ScriptCallableException.class, () -> fold(step, documents()));
        assertEquals("boom", error.getMessage());
    }

    @Test
    public void test_undefined_accumulator_becomes_json_null() throws IOException {
        final var step = new ReduceAggregationStep("export default (acc, doc) => undefined;", new JsonNumber(0),
                "total");
        assertTrue(fold(step, documents()).get("total").isJsonNull());
    }

    @Test
    public void test_oversized_accumulator_fails_cleanly() throws Exception {
        final var configuration = org.techhouse.config.Configuration.getInstance();
        final var previous = configuration.getScriptMaxResultBytes();
        try {
            TestUtils.setPrivateField(configuration, "scriptMaxResultBytes", 32L);
            final var step = new ReduceAggregationStep("export default (acc, doc) => [...acc, doc._id, doc._id];",
                    new JsonArray(), "ids");
            final var error = assertThrows(ScriptCallableException.class, () -> fold(step, documents()));
            assertEquals("ScriptResultTooLargeError", error.getErrorName());
        } finally {
            TestUtils.setPrivateField(configuration, "scriptMaxResultBytes", previous);
        }
    }

    private static JsonObject initialObject() {
        final var initial = new JsonObject();
        initial.add("n", new JsonNumber(0));
        initial.add("sum", new JsonNumber(0));
        return initial;
    }
}
