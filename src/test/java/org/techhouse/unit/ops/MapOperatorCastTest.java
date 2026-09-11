package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.MapOperatorHelper;
import org.techhouse.ops.req.agg.mid_operators.CastMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastToType;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.test.TestUtils;

public class MapOperatorCastTest {
    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
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

    // CAST number to boolean (0 → false, non-zero → true)
    @Test
    public void test_cast_number_to_boolean() {
        JsonObject input = new JsonObject();
        input.addProperty("zero", 0);
        input.addProperty("nonzero", 5);
        CastMidOperator castZero = new CastMidOperator("zero", CastToType.BOOLEAN);
        CastMidOperator castNonZero = new CastMidOperator("nonzero", CastToType.BOOLEAN);
        JsonObject r1 = MapOperatorHelper.processOperator(new AddFieldMapOperator("boolZero", null, castZero), input);
        JsonObject r2 = MapOperatorHelper.processOperator(new AddFieldMapOperator("boolNonZero", null, castNonZero),
                input);
        assertFalse(r1.get("boolZero").asJsonBoolean().getValue());
        assertTrue(r2.get("boolNonZero").asJsonBoolean().getValue());
    }

    // CAST boolean to number is not supported — returns JsonNull
    @Test
    public void test_cast_boolean_to_number_returns_null() {
        JsonObject input = new JsonObject();
        input.add("t", new JsonBoolean(true));
        JsonObject result = MapOperatorHelper.processOperator(
                new AddFieldMapOperator("n", null, new CastMidOperator("t", CastToType.NUMBER)), input);
        assertTrue(result.get("n").isJsonNull());
    }

    // CAST NUMBER when field is already a number of returns the number (L302)
    @Test
    public void test_cast_number_to_number_returns_same() {
        JsonObject input = new JsonObject();
        input.addProperty("n", 42);
        CastMidOperator cast = new CastMidOperator("n", CastToType.NUMBER);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals(42, result.get("out").asJsonNumber().asInteger());
    }

    // CAST NUMBER from a parseable string (L305)
    @Test
    public void test_cast_string_to_number_parseable() {
        JsonObject input = new JsonObject();
        input.addProperty("s", "3.14");
        CastMidOperator cast = new CastMidOperator("s", CastToType.NUMBER);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals(3.14, result.get("out").asJsonNumber().getValue().doubleValue(), 0.001);
    }

    // CAST STRING when field is already a string returns it (L313)
    @Test
    public void test_cast_string_to_string_returns_same() {
        JsonObject input = new JsonObject();
        input.addProperty("s", "hello");
        CastMidOperator cast = new CastMidOperator("s", CastToType.STRING);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals("hello", result.get("out").asJsonString().getValue());
    }

    // CAST STRING from boolean (L318)
    @Test
    public void test_cast_boolean_to_string() {
        JsonObject input = new JsonObject();
        input.add("flag", new JsonBoolean(true));
        CastMidOperator cast = new CastMidOperator("flag", CastToType.STRING);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals("true", result.get("out").asJsonString().getValue());
    }

    // CAST BOOLEAN when field is already boolean returns it (L324)
    @Test
    public void test_cast_boolean_to_boolean_returns_same() {
        JsonObject input = new JsonObject();
        input.add("flag", new JsonBoolean(false));
        CastMidOperator cast = new CastMidOperator("flag", CastToType.BOOLEAN);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertFalse(result.get("out").asJsonBoolean().getValue());
    }

    // CAST to NUMBER when field is JsonNull returns JsonNull (L309)
    @Test
    public void test_cast_null_field_to_number_returns_null() {
        JsonObject input = new JsonObject();
        input.add("n", JsonNull.INSTANCE);
        CastMidOperator cast = new CastMidOperator("n", CastToType.NUMBER);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertTrue(result.get("out").isJsonNull());
    }

    // CAST string to a custom type (datetime) via JSON_CUSTOM
    @Test
    public void test_cast_string_to_datetime_via_json_custom() {
        JsonObject input = new JsonObject();
        input.add("ts", new JsonString("2024-01-15T10:30:00"));
        CastMidOperator cast = new CastMidOperator("ts", "datetime");
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertInstanceOf(JsonDateTime.class, result.get("out"));
        assertEquals("2024-01-15T10:30:00", ((JsonCustom<?>) result.get("out")).stringDataValue());
    }

    // CAST invalid string to a custom type returns null
    @Test
    public void test_cast_invalid_string_to_custom_type_returns_null() {
        JsonObject input = new JsonObject();
        input.add("ts", new JsonString("not-a-date"));
        CastMidOperator cast = new CastMidOperator("ts", "datetime");
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertTrue(result.get("out").isJsonNull());
    }

    // CAST custom type to same custom type returns the same value
    @Test
    public void test_cast_custom_type_to_same_custom_type_returns_same() {
        JsonObject input = new JsonObject();
        input.add("ts", new JsonDateTime(LocalDateTime.of(2024, 1, 15, 10, 30, 0)));
        CastMidOperator cast = new CastMidOperator("ts", "datetime");
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertInstanceOf(JsonDateTime.class, result.get("out"));
    }

    // CAST string to time custom type via JSON_CUSTOM
    @Test
    public void test_cast_string_to_time_via_json_custom() {
        JsonObject input = new JsonObject();
        input.add("t", new JsonString("10:30:00"));
        CastMidOperator cast = new CastMidOperator("t", "time");
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertInstanceOf(JsonTime.class, result.get("out"));
        assertEquals("10:30:00", ((JsonCustom<?>) result.get("out")).stringDataValue());
    }

    // CAST custom type to STRING yields the data portion only
    @Test
    public void test_cast_datetime_to_string_yields_data_value() {
        JsonObject input = new JsonObject();
        input.add("ts", new JsonDateTime(LocalDateTime.of(2024, 1, 15, 10, 30, 0)));
        CastMidOperator cast = new CastMidOperator("ts", CastToType.STRING);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals("2024-01-15T10:30", result.get("out").asJsonString().getValue());
    }

    // CAST time custom type to STRING yields the data portion only
    @Test
    public void test_cast_time_to_string_yields_data_value() {
        JsonObject input = new JsonObject();
        input.add("t", new JsonTime(LocalTime.of(10, 30, 0)));
        CastMidOperator cast = new CastMidOperator("t", CastToType.STRING);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals("10:30", result.get("out").asJsonString().getValue());
    }
}
