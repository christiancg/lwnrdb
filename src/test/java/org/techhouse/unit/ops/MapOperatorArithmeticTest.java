package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.MapOperatorHelper;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
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

    // Process MapOperator with ADD_FIELD type and valid numeric operations (sum, multiply, avg)
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

    // Calculate mathematical operations (pow, root, abs) on numeric fields
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

    // Handle division by zero in numeric operations
    @Test
    public void test_division_by_zero() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("value1", 10);
        jsonObject.addProperty("value2", 0);

        JsonArray operands = new JsonArray();
        operands.add(new JsonString("value1"));
        operands.add(new JsonString("value2"));

        ArrayParamMidOperator midOperator = new ArrayParamMidOperator(MidOperationType.DIVIDE, operands);
        AddFieldMapOperator operator = new AddFieldMapOperator("result", null, midOperator);

        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);

        assertTrue(Double.isInfinite(result.get("result").asJsonNumber().getValue().doubleValue()),
                "Result should be infinite due to division by zero");
    }

    // Process size operation on strings and arrays
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

    // Sum multiple numeric values from different fields and constants
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

    // Calculate average of mixed field references and direct numbers
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

    // Handle empty operand lists in numeric operations
    @Test
    public void test_handle_empty_operand_list_in_numeric_operations() {
        JsonObject jsonObject = new JsonObject();
        JsonArray operands = new JsonArray();
        ArrayParamMidOperator midOperator = new ArrayParamMidOperator(MidOperationType.SUM, operands);
        AddFieldMapOperator operator = new AddFieldMapOperator("sum", null, midOperator);

        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);

        assertTrue(result.has("sum"));
        assertEquals(0.0, result.get("sum").asJsonNumber().getValue().doubleValue());
    }

    // ADD_FIELD with a false condition does not add the field
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

    // Condition with XOR conjunction matching exactly one operator adds the field
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
}
