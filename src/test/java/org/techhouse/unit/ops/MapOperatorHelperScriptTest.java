package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.MapOperatorHelper;
import org.techhouse.ops.PipelineScriptContext;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ScriptMidOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.test.TestUtils;

public class MapOperatorHelperScriptTest {
    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
    }

    private static JsonObject document(double price, double qty) {
        final var document = new JsonObject();
        document.add("price", new JsonNumber(price));
        document.add("qty", new JsonNumber(qty));
        return document;
    }

    @Test
    public void test_adds_computed_field() {
        try (var context = new PipelineScriptContext()) {
            final var operator = new AddFieldMapOperator("total", null,
                    new ScriptMidOperator("export default (doc) => doc.price * doc.qty;"));
            final var result = MapOperatorHelper.processOperator(operator, document(3, 4), context);
            assertEquals(12d, result.get("total").asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_computed_field_under_a_condition() {
        try (var context = new PipelineScriptContext()) {
            final var condition = new FieldOperator(FieldOperatorType.GREATER_THAN, "price", new JsonNumber(10));
            final var operator = new AddFieldMapOperator("total", condition,
                    new ScriptMidOperator("export default (doc) => doc.price * doc.qty;"));
            assertNull(MapOperatorHelper.processOperator(operator, document(3, 4), context).get("total"));
            assertEquals(80d, MapOperatorHelper.processOperator(operator, document(20, 4), context).get("total")
                    .asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_script_condition_gates_the_field() {
        try (var context = new PipelineScriptContext()) {
            final var condition = new ScriptOperator("export default (doc) => doc.qty > 2;");
            final var operator = new AddFieldMapOperator("total", condition,
                    new ScriptMidOperator("export default (doc) => doc.price * doc.qty;"));
            assertNull(MapOperatorHelper.processOperator(operator, document(3, 1), context).get("total"));
            assertEquals(12d, MapOperatorHelper.processOperator(operator, document(3, 4), context).get("total")
                    .asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_script_returning_undefined_omits_the_field() {
        try (var context = new PipelineScriptContext()) {
            final var operator = new AddFieldMapOperator("total", null,
                    new ScriptMidOperator("export default (doc) => undefined;"));
            assertNull(MapOperatorHelper.processOperator(operator, document(3, 4), context).get("total"));
        }
    }

    @Test
    public void test_script_throwing_surfaces_script_failed() {
        try (var context = new PipelineScriptContext()) {
            final var operator = new AddFieldMapOperator("total", null,
                    new ScriptMidOperator("export default (doc) => { throw new Error('boom'); };"));
            final var error = assertThrows(ScriptCallableException.class,
                    () -> MapOperatorHelper.processOperator(operator, document(3, 4), context));
            assertEquals("Error", error.getErrorName());
            assertEquals("boom", error.getMessage());
        }
    }

    @Test
    public void test_custom_type_value_round_trips() {
        try (var context = new PipelineScriptContext()) {
            final var operator = new AddFieldMapOperator("where", null,
                    new ScriptMidOperator("export default (doc) => Geo.from({ lat: 1, lng: 2 });"));
            final var value = MapOperatorHelper.processOperator(operator, document(3, 4), context).get("where");
            assertTrue(value.isJsonCustom());
            assertEquals("geo", value.asJsonCustom().getCustomTypeName());
        }
    }

    @Test
    public void test_object_and_array_results_are_stored() {
        try (var context = new PipelineScriptContext()) {
            final var objectOperator = new AddFieldMapOperator("meta", null,
                    new ScriptMidOperator("export default (doc) => ({ a: 1, b: [1, 2] });"));
            final var result = MapOperatorHelper.processOperator(objectOperator, document(3, 4), context);
            assertEquals(1d, result.get("meta").asJsonObject().get("a").asJsonNumber().getValue().doubleValue());
            assertEquals(2, result.get("meta").asJsonObject().get("b").asJsonArray().size());
        }
    }

    @Test
    public void test_string_result_is_stored() {
        try (var context = new PipelineScriptContext()) {
            final var operator = new AddFieldMapOperator("label", null,
                    new ScriptMidOperator("export default (doc) => `p${doc.price}`;"));
            final var result = MapOperatorHelper.processOperator(operator, document(3, 4), context);
            assertEquals(new JsonString("p3").getValue(), result.get("label").asJsonString().getValue());
        }
    }

    // The script sees a converted copy, so a mutation inside it can never reach the stored document.
    @Test
    public void test_script_cannot_mutate_the_source_document() {
        try (var context = new PipelineScriptContext()) {
            final var operator = new AddFieldMapOperator("total", null,
                    new ScriptMidOperator("export default (doc) => { doc.price = 999; return doc.price; };"));
            final var document = document(3, 4);
            final var result = MapOperatorHelper.processOperator(operator, document, context);
            assertEquals(999d, result.get("total").asJsonNumber().getValue().doubleValue());
            assertEquals(3d, result.get("price").asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_missing_context_is_an_internal_error() {
        final var operator = new AddFieldMapOperator("total", null,
                new ScriptMidOperator("export default (doc) => 1;"));
        assertThrows(ScriptCallableException.class, () -> MapOperatorHelper.processOperator(operator, document(1, 1)));
    }
}
