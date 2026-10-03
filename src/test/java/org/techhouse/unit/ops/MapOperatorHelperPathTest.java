package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.MapOperatorHelper;
import org.techhouse.ops.PipelineScriptContext;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastToType;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.ScriptMidOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.JsonUtils;

public class MapOperatorHelperPathTest {
    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
    }

    private static JsonObject document() {
        final var inner = new JsonObject();
        inner.add("b", new JsonNumber(-3));
        inner.add("text", new JsonString("hi"));
        final var document = new JsonObject();
        document.add("a", inner);
        return document;
    }

    private static JsonArray operands(String... fields) {
        final var operands = new JsonArray();
        for (final var field : fields) {
            operands.add(new JsonString(field));
        }
        return operands;
    }

    private static double numberAt(JsonObject obj, String path) {
        return JsonUtils.getFromPath(obj, path).asJsonNumber().getValue().doubleValue();
    }

    @Test
    public void test_add_field_with_a_dotted_name_writes_nested() {
        var result = MapOperatorHelper.processOperator(new AddFieldMapOperator("a.sum", null,
                new ArrayParamMidOperator(MidOperationType.SUM, operands("a.b", "a.b"))), document());
        result = MapOperatorHelper.processOperator(
                new AddFieldMapOperator("a.abs", null, new OneParamMidOperator(MidOperationType.ABS, "a.b")), result);
        result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out.joined", null,
                new ArrayParamMidOperator(MidOperationType.CONCAT, operands("a.text", "-!"))), result);
        result = MapOperatorHelper.processOperator(
                new AddFieldMapOperator("out.cast", null, new CastMidOperator("a.b", CastToType.STRING)), result);

        assertEquals(-6d, numberAt(result, "a.sum"));
        assertEquals(3d, numberAt(result, "a.abs"));
        assertEquals(-3d, numberAt(result, "a.b"));
        assertEquals("hi!", JsonUtils.getFromPath(result, "out.joined").asJsonString().getValue());
        assertEquals("-3", JsonUtils.getFromPath(result, "out.cast").asJsonString().getValue());
        assertFalse(result.has("a.sum"));
        assertFalse(result.has("out.joined"));
    }

    @Test
    public void test_a_script_result_with_a_dotted_name_writes_nested() {
        try (var context = new PipelineScriptContext()) {
            final var operator = new AddFieldMapOperator("a.doubled", null,
                    new ScriptMidOperator("export default (doc) => doc.a.b * 2;"));
            final var result = MapOperatorHelper.processOperator(operator, document(), context);
            assertEquals(-6d, numberAt(result, "a.doubled"));
        }
    }

    @Test
    public void test_a_dotted_result_is_readable_by_a_later_condition() {
        final var mapped = MapOperatorHelper.processOperator(
                new AddFieldMapOperator("a.abs", null, new OneParamMidOperator(MidOperationType.ABS, "a.b")),
                document());
        final var condition = new FieldOperator(FieldOperatorType.EQUALS, "a.abs", new JsonNumber(3));
        final var result = MapOperatorHelper.processOperator(
                new AddFieldMapOperator("flag", condition, new OneParamMidOperator(MidOperationType.SIZE, "a.text")),
                mapped);
        assertEquals(2d, numberAt(result, "flag"));
    }

    @Test
    public void test_remove_field_with_a_dotted_name_removes_the_nested_key() {
        final var result = MapOperatorHelper.processOperator(new RemoveFieldMapOperator("a.b", null), document());
        assertFalse(JsonUtils.hasInPath(result, "a.b"));
        assertTrue(JsonUtils.hasInPath(result, "a.text"));
    }

    @Test
    public void test_a_null_fold_result_is_written_as_json_null_at_the_path() {
        final var result = MapOperatorHelper.processOperator(new AddFieldMapOperator("x.sum", null,
                new ArrayParamMidOperator(MidOperationType.SUM, operands("missing"))), document());
        assertEquals(JsonNull.INSTANCE, JsonUtils.resolvePath(result, "x.sum"));
    }
}
