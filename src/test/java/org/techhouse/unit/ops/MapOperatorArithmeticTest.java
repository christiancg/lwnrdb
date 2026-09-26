package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.MapOperatorHelper;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastToType;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.test.TestUtils;

public class MapOperatorArithmeticTest {
    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
    }

    @Test
    public void test_add_field_numeric_operations() {
        JsonObject input = new JsonObject();
        input.addProperty("field1", 10);
        input.addProperty("field2", 20);

        JsonArray operands = new JsonArray();
        operands.add(new JsonString("field1"));
        operands.add(new JsonString("field2"));

        ArrayParamMidOperator sumOperator = new ArrayParamMidOperator(MidOperationType.SUM, operands);
        AddFieldMapOperator addSumField = new AddFieldMapOperator("sum_result", null, sumOperator);

        JsonObject result = MapOperatorHelper.processOperator(addSumField, input);

        assertEquals(30.0, result.get("sum_result").asJsonNumber().getValue());

        ArrayParamMidOperator multiplyOperator = new ArrayParamMidOperator(MidOperationType.MULTIPLY, operands);
        AddFieldMapOperator addMultiplyField = new AddFieldMapOperator("multiply_result", null, multiplyOperator);

        result = MapOperatorHelper.processOperator(addMultiplyField, result);

        assertEquals(200.0, result.get("multiply_result").asJsonNumber().getValue());

        ArrayParamMidOperator avgOperator = new ArrayParamMidOperator(MidOperationType.AVG, operands);
        AddFieldMapOperator addAvgField = new AddFieldMapOperator("avg_result", null, avgOperator);

        result = MapOperatorHelper.processOperator(addAvgField, result);

        assertEquals(15.0, result.get("avg_result").asJsonNumber().getValue());
    }

    @Test
    public void test_calculate_math_operations() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("number", -4);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("number"));
        operands.add(new JsonNumber(2));
        ArrayParamMidOperator powOperator = new ArrayParamMidOperator(MidOperationType.POW, operands);
        AddFieldMapOperator powMapOperator = new AddFieldMapOperator("powResult", null, powOperator);

        JsonObject powResult = MapOperatorHelper.processOperator(powMapOperator, jsonObject);
        assertEquals(16.0, powResult.get("powResult").asJsonNumber().getValue().doubleValue());

        OneParamMidOperator absOperator = new OneParamMidOperator(MidOperationType.ABS, "number");
        AddFieldMapOperator absMapOperator = new AddFieldMapOperator("absResult", null, absOperator);

        JsonObject absResult = MapOperatorHelper.processOperator(absMapOperator, jsonObject);
        assertEquals(4.0, absResult.get("absResult").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_division_by_zero_answers_null() {
        assertNull(foldOf(MidOperationType.DIVIDE, new JsonString("a"), new JsonString("z")),
                "Infinity is not a JSON number, so a divide by zero has no answer to give");
    }

    @Test
    public void test_size_operation_on_strings_and_arrays() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("stringField", "hello");
        JsonArray arr = new JsonArray();
        arr.add(new JsonString("one"));
        arr.add(new JsonString("two"));
        jsonObject.add("arrayField", arr);

        OneParamMidOperator sizeOperator = new OneParamMidOperator(MidOperationType.SIZE, "stringField");
        AddFieldMapOperator addFieldOperator = new AddFieldMapOperator("stringSize", null, sizeOperator);

        JsonObject result = MapOperatorHelper.processOperator(addFieldOperator, jsonObject);

        assertEquals(5, result.get("stringSize").asJsonNumber().asInteger(),
                "The size of the string 'hello' should be 5.");

        sizeOperator = new OneParamMidOperator(MidOperationType.SIZE, "arrayField");
        addFieldOperator = new AddFieldMapOperator("arraySize", null, sizeOperator);

        result = MapOperatorHelper.processOperator(addFieldOperator, jsonObject);

        assertEquals(2, result.get("arraySize").asJsonNumber().asInteger(), "The size of the array should be 2.");
    }

    @Test
    public void test_sum_multiple_numeric_values() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field1", 10);
        jsonObject.addProperty("field2", 20);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("field1"));
        operands.add(new JsonString("field2"));
        operands.add(new JsonNumber(5));
        ArrayParamMidOperator midOperator = new ArrayParamMidOperator(MidOperationType.SUM, operands);
        AddFieldMapOperator operator = new AddFieldMapOperator("result", null, midOperator);
        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);
        assertEquals(35, result.get("result").asJsonNumber().asInteger());
    }

    @Test
    public void test_calculate_average_mixed_fields_and_numbers() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field1", 10);
        jsonObject.addProperty("field2", 20);
        JsonArray operands = new JsonArray();
        operands.add(new JsonNumber(30));
        operands.add(new JsonString("field1"));
        operands.add(new JsonString("field2"));
        ArrayParamMidOperator midOperator = new ArrayParamMidOperator(MidOperationType.AVG, operands);
        AddFieldMapOperator operator = new AddFieldMapOperator("average", null, midOperator);

        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);

        assertEquals(20.0, result.get("average").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_handle_empty_operand_list_in_numeric_operations() {
        JsonObject jsonObject = new JsonObject();
        JsonArray operands = new JsonArray();
        ArrayParamMidOperator midOperator = new ArrayParamMidOperator(MidOperationType.SUM, operands);
        AddFieldMapOperator operator = new AddFieldMapOperator("sum", null, midOperator);

        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);

        assertTrue(result.has("sum"));
        assertTrue(result.get("sum").isJsonNull());
    }

    @Test
    public void test_abs_of_a_missing_field_is_a_json_null() {
        JsonObject input = new JsonObject();

        OneParamMidOperator absOperator = new OneParamMidOperator(MidOperationType.ABS, "missing");
        AddFieldMapOperator operator = new AddFieldMapOperator("absResult", null, absOperator);

        JsonObject result = MapOperatorHelper.processOperator(operator, input);

        assertSame(JsonNull.INSTANCE, result.get("absResult"));
    }

    @Test
    public void test_size_of_a_missing_field_is_a_json_null() {
        JsonObject input = new JsonObject();

        OneParamMidOperator sizeOperator = new OneParamMidOperator(MidOperationType.SIZE, "missing");
        AddFieldMapOperator operator = new AddFieldMapOperator("sizeResult", null, sizeOperator);

        JsonObject result = MapOperatorHelper.processOperator(operator, input);

        assertSame(JsonNull.INSTANCE, result.get("sizeResult"));
    }

    @Test
    public void test_avg_over_no_valid_operand_is_a_json_null() {
        JsonObject input = new JsonObject();
        input.addProperty("label", "not a number");

        JsonArray operands = new JsonArray();
        operands.add(new JsonString("label"));
        ArrayParamMidOperator avgOperator = new ArrayParamMidOperator(MidOperationType.AVG, operands);
        AddFieldMapOperator operator = new AddFieldMapOperator("average", null, avgOperator);

        JsonObject result = MapOperatorHelper.processOperator(operator, input);

        assertSame(JsonNull.INSTANCE, result.get("average"));
    }

    @Test
    public void test_a_null_fold_result_and_a_null_cast_result_are_the_same_element() {
        JsonObject input = new JsonObject();
        input.addProperty("label", "not a number");

        JsonArray operands = new JsonArray();
        operands.add(new JsonString("label"));
        AddFieldMapOperator fold = new AddFieldMapOperator("folded", null,
                new ArrayParamMidOperator(MidOperationType.AVG, operands));
        AddFieldMapOperator cast = new AddFieldMapOperator("casted", null,
                new CastMidOperator("label", CastToType.NUMBER));

        JsonObject result = MapOperatorHelper.processOperator(cast, MapOperatorHelper.processOperator(fold, input));

        assertSame(JsonNull.INSTANCE, result.get("casted"));
        assertSame(JsonNull.INSTANCE, result.get("folded"));
    }

    @Test
    public void test_add_field_with_false_condition_skips_operation() {
        JsonObject input = new JsonObject();
        input.addProperty("score", 5);

        FieldOperator falseCondition = new FieldOperator(FieldOperatorType.EQUALS, "score", new JsonNumber(99));
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("score"));
        operands.add(new JsonNumber(2));
        ArrayParamMidOperator multiplyOp = new ArrayParamMidOperator(MidOperationType.MULTIPLY, operands);
        AddFieldMapOperator op = new AddFieldMapOperator("doubled", falseCondition, multiplyOp);

        JsonObject result = MapOperatorHelper.processOperator(op, input);

        assertFalse(result.has("doubled"));
    }

    @Test
    public void test_xor_condition_one_match_adds_field() {
        JsonObject input = new JsonObject();
        input.addProperty("x", 5);
        input.addProperty("y", 10);

        List<BaseOperator> ops = List.of(new FieldOperator(FieldOperatorType.EQUALS, "x", new JsonNumber(5)),
                new FieldOperator(FieldOperatorType.EQUALS, "y", new JsonNumber(99)));
        ConjunctionOperator xorCond = new ConjunctionOperator(ConjunctionOperatorType.XOR, ops);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("x"));
        AddFieldMapOperator op = new AddFieldMapOperator("result", xorCond,
                new ArrayParamMidOperator(MidOperationType.SUM, operands));

        JsonObject result = MapOperatorHelper.processOperator(op, input);
        assertTrue(result.has("result"));
    }

    private static JsonObject sample() {
        final var document = new JsonObject();
        document.addProperty("a", 10);
        document.addProperty("b", 2);
        document.addProperty("z", 0);
        document.addProperty("negativeZero", -0.0);
        document.addProperty("big", 3000000000d);
        document.add("nullField", JsonNull.INSTANCE);
        return document;
    }

    private static Double foldOf(MidOperationType type, JsonBaseElement... operands) {
        return foldOf(sample(), type, operands);
    }

    private static Double foldOf(JsonObject document, MidOperationType type, JsonBaseElement... operands) {
        final var operandArray = new JsonArray();
        for (final var operand : operands) {
            operandArray.add(operand);
        }
        final var operator = new AddFieldMapOperator("folded", null, new ArrayParamMidOperator(type, operandArray));
        final var folded = MapOperatorHelper.processOperator(operator, document).get("folded");
        return folded.isJsonNull() ? null : folded.asJsonNumber().getValue().doubleValue();
    }

    @Test
    public void test_literal_operands_fold_like_field_operands() {
        final var folds = List.of(MidOperationType.MULTIPLY, MidOperationType.SUBS, MidOperationType.DIVIDE,
                MidOperationType.POW, MidOperationType.ROOT);
        for (final var type : folds) {
            assertEquals(foldOf(type, new JsonString("a"), new JsonString("b")),
                    foldOf(type, new JsonNumber(10), new JsonNumber(2)),
                    type + " must answer the same whether an operand is a literal or a field reference");
        }
        assertEquals(20.0, foldOf(MidOperationType.MULTIPLY, new JsonNumber(10), new JsonNumber(2)));
        assertEquals(8.0, foldOf(MidOperationType.SUBS, new JsonNumber(10), new JsonNumber(2)));
        assertEquals(5.0, foldOf(MidOperationType.DIVIDE, new JsonNumber(10), new JsonNumber(2)));
        assertEquals(100.0, foldOf(MidOperationType.POW, new JsonNumber(10), new JsonNumber(2)));
    }

    @Test
    public void test_operand_order_does_not_change_a_commutative_fold() {
        assertEquals(foldOf(MidOperationType.MULTIPLY, new JsonString("a"), new JsonNumber(2)),
                foldOf(MidOperationType.MULTIPLY, new JsonNumber(2), new JsonString("a")));
        assertEquals(20.0, foldOf(MidOperationType.MULTIPLY, new JsonNumber(2), new JsonString("a")));
    }

    @Test
    public void test_a_field_holding_zero_does_not_restart_the_fold() {
        assertEquals(0.0,
                foldOf(MidOperationType.MULTIPLY, new JsonString("a"), new JsonString("z"), new JsonString("b")));
        assertEquals(0.0,
                foldOf(MidOperationType.MULTIPLY, new JsonString("z"), new JsonString("a"), new JsonString("b")));
    }

    @Test
    public void test_a_negative_zero_operand_seeds_the_fold_unchanged() {
        assertEquals(-0.0, foldOf(MidOperationType.MULTIPLY, new JsonString("negativeZero"), new JsonString("a")));
    }

    @Test
    public void test_subtraction_and_division_keep_left_to_right_order() {
        assertEquals(7.0, foldOf(MidOperationType.SUBS, new JsonNumber(10), new JsonNumber(2), new JsonNumber(1)));
        assertEquals(10.0, foldOf(MidOperationType.DIVIDE, new JsonNumber(100), new JsonNumber(5), new JsonNumber(2)));
    }

    @Test
    public void test_pow_and_root_seed_from_the_first_operand() {
        assertEquals(1024.0, foldOf(MidOperationType.POW, new JsonNumber(2), new JsonNumber(10)));
        assertEquals(2.0, foldOf(MidOperationType.ROOT, new JsonNumber(1024), new JsonNumber(10)));
    }

    @Test
    public void test_min_and_max_do_not_leak_a_sentinel() {
        assertEquals(3000000000d, foldOf(MidOperationType.MIN, new JsonString("big")));
        assertEquals(3000000000d, foldOf(MidOperationType.MAX, new JsonString("big")));
        assertEquals(-0.0, foldOf(MidOperationType.MIN, new JsonString("negativeZero"), new JsonString("a")));
    }

    @Test
    public void test_min_and_max_answer_null_with_no_valid_operand() {
        assertNull(foldOf(MidOperationType.MIN, new JsonString("missing")));
        assertNull(foldOf(MidOperationType.MAX, new JsonString("missing")));
        assertNull(foldOf(MidOperationType.MIN, new JsonString("nullField")));
    }

    @Test
    public void test_avg_answers_null_with_no_valid_operand() {
        assertNull(foldOf(MidOperationType.AVG, new JsonString("missing")));
        assertNull(foldOf(MidOperationType.AVG, new JsonString("nullField")));
    }

    @Test
    public void test_non_finite_intermediate_results_answer_null() {
        assertNull(foldOf(MidOperationType.POW, new JsonNumber(Double.MAX_VALUE), new JsonNumber(2)));
        assertNull(
                foldOf(MidOperationType.MULTIPLY, new JsonNumber(Double.MAX_VALUE), new JsonNumber(Double.MAX_VALUE)));
    }

    @Test
    public void test_a_fold_over_only_non_numeric_operands_answers_null() {
        final var arrayOperand = new JsonArray();
        arrayOperand.add(new JsonNumber(1));
        assertNull(foldOf(MidOperationType.SUM, new JsonBoolean(true), arrayOperand, new JsonObject(),
                new JsonString("missing")));
    }

    @Test
    public void test_a_single_operand_fold_answers_that_operand() {
        assertEquals(10.0, foldOf(MidOperationType.MULTIPLY, new JsonString("a")));
        assertEquals(10.0, foldOf(MidOperationType.MAX, new JsonString("a")));
        assertEquals(10.0, foldOf(MidOperationType.SUBS, new JsonString("a")));
    }

    private static String concatOf(JsonArray operands) {
        final var operator = new AddFieldMapOperator("joined", null,
                new ArrayParamMidOperator(MidOperationType.CONCAT, operands));
        return MapOperatorHelper.processOperator(operator, new JsonObject()).get("joined").asJsonString().getValue();
    }

    @Test
    public void test_concat_formats_an_array_element_like_a_scalar_operand() {
        final var pastTheIntRange = new JsonNumber(3000000000d);

        final var asScalarOperand = new JsonArray();
        asScalarOperand.add(pastTheIntRange);

        final var insideAnArrayOperand = new JsonArray();
        final var nested = new JsonArray();
        nested.add(pastTheIntRange);
        insideAnArrayOperand.add(nested);

        assertEquals(concatOf(asScalarOperand), concatOf(insideAnArrayOperand),
                "both CONCAT branches must spell a number the same way");
        assertEquals("3000000000", concatOf(insideAnArrayOperand));
    }
}
