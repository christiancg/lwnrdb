package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.MapOperatorHelper;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.test.TestUtils;

public class MapOperatorHelperTest {
    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
    }

    // Handle null or missing fields in mathematical operations
    @Test
    public void test_handle_null_missing_fields() {
        JsonObject input = new JsonObject();
        input.addProperty("field1", 10);
        input.add("field2", JsonNull.INSTANCE);

        JsonArray operands = new JsonArray();
        operands.add(new JsonString("field1"));
        operands.add(new JsonString("field2"));
        operands.add(new JsonString("non_existent_field"));

        ArrayParamMidOperator sumOperator = new ArrayParamMidOperator(MidOperationType.SUM, operands);
        AddFieldMapOperator addSumField = new AddFieldMapOperator("sum_result", null, sumOperator);

        JsonObject result = MapOperatorHelper.processOperator(addSumField, input);

        assertEquals(10.0, result.get("sum_result").asJsonNumber().getValue());

        ArrayParamMidOperator avgOperator = new ArrayParamMidOperator(MidOperationType.AVG, operands);
        AddFieldMapOperator addAvgField = new AddFieldMapOperator("avg_result", null, avgOperator);

        result = MapOperatorHelper.processOperator(addAvgField, result);

        assertEquals(10.0, result.get("avg_result").asJsonNumber().getValue());
    }

    // Process MapOperator with REMOVE_FIELD type to delete existing fields
    @Test
    public void test_remove_field_map_operator() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("fieldToRemove", "value");
        jsonObject.addProperty("anotherField", "anotherValue");

        RemoveFieldMapOperator removeFieldMapOperator = new RemoveFieldMapOperator("fieldToRemove", null);

        JsonObject result = MapOperatorHelper.processOperator(removeFieldMapOperator, jsonObject);

        assertFalse(result.has("fieldToRemove"));
        assertTrue(result.has("anotherField"));
    }

    // Concatenate strings and field values with proper prefix handling
    @Test
    public void test_concatenate_strings_and_fields() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field1", "Hello");
        jsonObject.addProperty("field2", "World");
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("field1"));
        operands.add(new JsonString("field2"));
        ArrayParamMidOperator midOperator = new ArrayParamMidOperator(MidOperationType.CONCAT, operands);
        AddFieldMapOperator operator = new AddFieldMapOperator("result", null, midOperator);

        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);

        assertEquals("HelloWorld", result.get("result").asJsonString().getValue());
    }

    // Process nested field paths that don't exist
    @Test
    public void test_non_existent_nested_field_paths() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("existingField", 5);

        FieldOperator condition = new FieldOperator(FieldOperatorType.EQUALS, "non.existent.path", new JsonNumber(5));
        RemoveFieldMapOperator operator = new RemoveFieldMapOperator("existingField", condition);

        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);

        assertTrue(result.has("existingField"), "Field should not be removed as the condition path does not exist");
    }

    // Handle string literals in concatenation
    @Test
    public void test_string_literals_in_concatenation() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field1", "value1");
        jsonObject.addProperty("field2", "value2");

        JsonArray operands = new JsonArray();
        operands.add(new JsonString("-literal1"));
        operands.add(new JsonString("field1"));
        operands.add(new JsonString("-literal2"));

        ArrayParamMidOperator concatOperator = new ArrayParamMidOperator(MidOperationType.CONCAT, operands);
        AddFieldMapOperator addFieldOperator = new AddFieldMapOperator("concatenated", null, concatOperator);

        JsonObject result = MapOperatorHelper.processOperator(addFieldOperator, jsonObject);

        assertEquals("literal1value1literal2", result.get("concatenated").asJsonString().getValue(),
                "Concatenation should handle string literals correctly.");
    }

    // Track number of valid steps in average calculation
    @Test
    public void test_average_calculation_valid_steps() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("value1", 10);
        jsonObject.addProperty("value2", 20);
        jsonObject.addProperty("value3", "not a number");

        JsonArray operands = new JsonArray();
        operands.add(new JsonString("value1"));
        operands.add(new JsonString("value2"));
        operands.add(new JsonString("value3"));

        ArrayParamMidOperator midOperator = new ArrayParamMidOperator(MidOperationType.AVG, operands);
        AddFieldMapOperator addFieldMapOperator = new AddFieldMapOperator("average", null, midOperator);

        JsonObject result = MapOperatorHelper.processOperator(addFieldMapOperator, jsonObject);

        assertEquals(15.0, result.get("average").asJsonNumber().getValue().doubleValue());
    }

    // Remove existing field from JSON object
    @Test
    public void test_remove_existing_field() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("fieldToRemove", "value");
        RemoveFieldMapOperator operator = new RemoveFieldMapOperator("fieldToRemove", null);
        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);
        assertFalse(result.has("fieldToRemove"));
    }

    // MIN operator picks the minimum of field references and constants
    @Test
    public void test_min_operation() {
        JsonObject input = new JsonObject();
        input.addProperty("a", 5);
        input.addProperty("b", 15);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("a"));
        operands.add(new JsonString("b"));
        operands.add(new JsonNumber(10));
        ArrayParamMidOperator minOp = new ArrayParamMidOperator(MidOperationType.MIN, operands);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("min", null, minOp), input);
        assertEquals(5.0, result.get("min").asJsonNumber().getValue().doubleValue());
    }

    // MAX operator picks the maximum of field references and constants
    @Test
    public void test_max_operation() {
        JsonObject input = new JsonObject();
        input.addProperty("a", 5);
        input.addProperty("b", 15);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("a"));
        operands.add(new JsonString("b"));
        operands.add(new JsonNumber(10));
        ArrayParamMidOperator maxOp = new ArrayParamMidOperator(MidOperationType.MAX, operands);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("max", null, maxOp), input);
        assertEquals(15.0, result.get("max").asJsonNumber().getValue().doubleValue());
    }

    // SUBS operator subtracts all values from the first operand
    @Test
    public void test_subs_operation() {
        JsonObject input = new JsonObject();
        input.addProperty("base", 100);
        input.addProperty("sub", 30);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("base"));
        operands.add(new JsonString("sub"));
        operands.add(new JsonNumber(10));
        ArrayParamMidOperator subsOp = new ArrayParamMidOperator(MidOperationType.SUBS, operands);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("subs", null, subsOp), input);
        assertEquals(60.0, result.get("subs").asJsonNumber().getValue().doubleValue());
    }

    // ROOT operator computes nth root
    @Test
    public void test_root_operation() {
        JsonObject input = new JsonObject();
        input.addProperty("base", 27);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("base"));
        operands.add(new JsonNumber(3));
        ArrayParamMidOperator rootOp = new ArrayParamMidOperator(MidOperationType.ROOT, operands);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("root", null, rootOp), input);
        assertEquals(3.0, result.get("root").asJsonNumber().getValue().doubleValue(), 0.0001);
    }

    // Condition with OR conjunction where no sub-operators match (orConjunction returns false)
    @Test
    public void test_or_condition_all_false_skips_field() {
        JsonObject input = new JsonObject();
        input.addProperty("x", 1);

        List<BaseOperator> ops = List.of(new FieldOperator(FieldOperatorType.EQUALS, "x", new JsonNumber(99)),
                new FieldOperator(FieldOperatorType.EQUALS, "x", new JsonNumber(98)));
        ConjunctionOperator orCond = new ConjunctionOperator(ConjunctionOperatorType.OR, ops);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("x"));
        AddFieldMapOperator op = new AddFieldMapOperator("result", orCond,
                new ArrayParamMidOperator(MidOperationType.SUM, operands));

        JsonObject result = MapOperatorHelper.processOperator(op, input);
        assertFalse(result.has("result"));
    }

    // Condition with NOR returns false (not supported in MAP conditions)
    @Test
    public void test_nor_condition_returns_false_skips_field() {
        JsonObject input = new JsonObject();
        input.addProperty("x", 5);

        List<BaseOperator> ops = List.of(new FieldOperator(FieldOperatorType.EQUALS, "x", new JsonNumber(99)));
        ConjunctionOperator norCond = new ConjunctionOperator(ConjunctionOperatorType.NOR, ops);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("x"));
        AddFieldMapOperator op = new AddFieldMapOperator("result", norCond,
                new ArrayParamMidOperator(MidOperationType.SUM, operands));

        JsonObject result = MapOperatorHelper.processOperator(op, input);
        assertFalse(result.has("result"));
    }

    // CONCAT with a numeric field reference uses toJson for non-string element (L270)
    @Test
    public void test_concat_non_string_field_uses_to_json() {
        JsonObject input = new JsonObject();
        input.addProperty("score", 42);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("score"));
        ArrayParamMidOperator op = new ArrayParamMidOperator(MidOperationType.CONCAT, operands);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, op), input);
        assertTrue(result.has("out"));
        assertTrue(result.get("out").asJsonString().getValue().contains("42"));
    }

    // CONCAT with a JsonNumber operand directly uses toJson (L275)
    @Test
    public void test_concat_json_number_operand_uses_to_json() {
        JsonObject input = new JsonObject();
        JsonArray operands = new JsonArray();
        operands.add(new JsonNumber(99));
        ArrayParamMidOperator op = new ArrayParamMidOperator(MidOperationType.CONCAT, operands);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, op), input);
        assertTrue(result.has("out"));
    }

    // CONCAT with a JsonArray operand appends its primitive elements (L278-282)
    @Test
    public void test_concat_json_array_operand() {
        JsonObject input = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(new JsonString("x"));
        arr.add(new JsonString("y"));
        JsonArray operands = new JsonArray();
        operands.add(arr);
        ArrayParamMidOperator op = new ArrayParamMidOperator(MidOperationType.CONCAT, operands);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, op), input);
        assertTrue(result.get("out").asJsonString().getValue().contains("x"));
    }

    // CONCAT with a JsonNull operand appends null string (L284)
    @Test
    public void test_concat_json_null_operand() {
        JsonObject input = new JsonObject();
        JsonArray operands = new JsonArray();
        operands.add(JsonNull.INSTANCE);
        ArrayParamMidOperator op = new ArrayParamMidOperator(MidOperationType.CONCAT, operands);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, op), input);
        assertTrue(result.has("out"));
    }

    // CONCAT with a DateTime field reference uses stringDataValue
    @Test
    public void test_concat_datetime_field_uses_string_data_value() {
        JsonObject input = new JsonObject();
        input.add("ts", new JsonDateTime(LocalDateTime.of(2024, 1, 15, 10, 30, 0)));
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("ts"));
        ArrayParamMidOperator op = new ArrayParamMidOperator(MidOperationType.CONCAT, operands);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, op), input);
        assertEquals("2024-01-15T10:30", result.get("out").asJsonString().getValue());
    }

    // CONCAT with a Time field reference uses stringDataValue
    @Test
    public void test_concat_time_field_uses_string_data_value() {
        JsonObject input = new JsonObject();
        input.add("t", new JsonTime(LocalTime.of(10, 30, 0)));
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("t"));
        ArrayParamMidOperator op = new ArrayParamMidOperator(MidOperationType.CONCAT, operands);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, op), input);
        assertEquals("10:30", result.get("out").asJsonString().getValue());
    }

    // Attempt to remove non-existent field
    @Test
    public void test_remove_non_existent_field() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("existingField", "value");
        RemoveFieldMapOperator operator = new RemoveFieldMapOperator("nonExistentField", null);

        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);

        assertTrue(result.has("existingField"));
        assertEquals("value", result.get("existingField").asJsonString().getValue());
    }
}
