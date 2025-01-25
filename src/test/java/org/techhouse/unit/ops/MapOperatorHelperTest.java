package org.techhouse.unit.ops;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.*;
import org.techhouse.ops.MapOperatorHelper;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.*;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MapOperatorHelperTest {
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


    // Cast field values between different types (number, string, boolean)
    @Test
    public void test_cast_field_values() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("numberField", 123);
        jsonObject.addProperty("stringField", "true");

        CastMidOperator castToString = new CastMidOperator("numberField", CastToType.STRING);
        CastMidOperator castToBoolean = new CastMidOperator("stringField", CastToType.BOOLEAN);

        AddFieldMapOperator addStringField = new AddFieldMapOperator("castedStringField", null, castToString);
        AddFieldMapOperator addBooleanField = new AddFieldMapOperator("castedBooleanField", null, castToBoolean);

        JsonObject result1 = MapOperatorHelper.processOperator(addStringField, jsonObject);
        JsonObject result2 = MapOperatorHelper.processOperator(addBooleanField, jsonObject);

        assertEquals("123", result1.get("castedStringField").asJsonString().getValue());
        assertTrue(result2.get("castedBooleanField").asJsonBoolean().getValue());
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

    // Process empty or invalid conjunction operator lists
    @Test
    public void test_empty_conjunction_operator_list() {
        JsonObject jsonObject = new JsonObject();
        List<BaseOperator> operators = new ArrayList<>();
        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);
        RemoveFieldMapOperator mapOperator = new RemoveFieldMapOperator("nonExistentField", conjunctionOperator);

        JsonObject result = MapOperatorHelper.processOperator(mapOperator, jsonObject);

        assertTrue(result.isEmpty());
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

        assertTrue(Double.isInfinite(result.get("result").asJsonNumber().getValue().doubleValue()), "Result should be infinite due to division by zero");
    }

    // Cast invalid values between incompatible types
    @Test
    public void test_cast_invalid_values() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field", "not_a_number");

        CastMidOperator midOperator = new CastMidOperator("field", CastToType.NUMBER);
        AddFieldMapOperator operator = new AddFieldMapOperator("result", null, midOperator);

        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);

        assertTrue(result.get("result").isJsonNull(), "Result should be null due to invalid cast");
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

        assertEquals(5, result.get("stringSize").asJsonNumber().asInteger(), "The size of the string 'hello' should be 5.");

        sizeOperator = new OneParamMidOperator(MidOperationType.SIZE, "arrayField");
        addFieldOperator = new AddFieldMapOperator("arraySize", null, sizeOperator);

        result = MapOperatorHelper.processOperator(addFieldOperator, jsonObject);

        assertEquals(2, result.get("arraySize").asJsonNumber().asInteger(), "The size of the array should be 2.");
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

        assertEquals("literal1value1literal2", result.get("concatenated").asJsonString().getValue(), "Concatenation should handle string literals correctly.");
    }

    // Support recursive conjunction operator processing
    @Test
    public void test_recursive_conjunction_operator_processing() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field1", true);
        jsonObject.addProperty("field2", false);

        FieldOperator fieldOperator1 = new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonBoolean(true));
        FieldOperator fieldOperator2 = new FieldOperator(FieldOperatorType.EQUALS, "field2", new JsonBoolean(false));
        List<BaseOperator> operators = List.of(fieldOperator1, fieldOperator2);

        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);
        MapOperator mapOperator = new RemoveFieldMapOperator("field1", conjunctionOperator);

        JsonObject result = MapOperatorHelper.processOperator(mapOperator, jsonObject);

        assertFalse(result.has("field1"));
    }

    // Validate operator type compatibility before processing
    @Test
    public void test_operator_type_compatibility() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field1", 10);

        FieldOperator fieldOperator = new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonNumber(10));
        AddFieldMapOperator addFieldMapOperator = new AddFieldMapOperator("newField", fieldOperator, null);

        assertThrows(NullPointerException.class, () -> MapOperatorHelper.processOperator(addFieldMapOperator, jsonObject));
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

    // Remove existing field from JSON object
    @Test
    public void test_remove_existing_field() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("fieldToRemove", "value");
        RemoveFieldMapOperator operator = new RemoveFieldMapOperator("fieldToRemove", null);
        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);
        assertFalse(result.has("fieldToRemove"));
    }

    // Process AND conjunction with all true conditions
    @Test
    public void test_and_conjunction_all_true() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field1", 10);
        jsonObject.addProperty("field2", 20);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("field1"));
        operands.add(new JsonString("field2"));
        operands.add(new JsonNumber(5));
        ArrayParamMidOperator midOperator = new ArrayParamMidOperator(MidOperationType.SUM, operands);

        JsonObject conditionObject = new JsonObject();
        conditionObject.addProperty("field1", 10);
        conditionObject.addProperty("field2", 20);
        FieldOperator fieldOp1 = new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonNumber(10));
        FieldOperator fieldOp2 = new FieldOperator(FieldOperatorType.EQUALS, "field2", new JsonNumber(20));
        List<BaseOperator> operators = List.of(fieldOp1, fieldOp2);
        ConjunctionOperator conjunctionOp = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);

        AddFieldMapOperator operator = new AddFieldMapOperator("result", conjunctionOp, midOperator);
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