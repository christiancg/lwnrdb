package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.NumberFormatter;
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

    @Test
    public void test_cast_invalid_values() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field", "not_a_number");

        CastMidOperator midOperator = new CastMidOperator("field", CastToType.NUMBER);
        AddFieldMapOperator operator = new AddFieldMapOperator("result", null, midOperator);

        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);

        assertTrue(result.get("result").isJsonNull(), "Result should be null due to invalid cast");
    }

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

    @Test
    public void test_cast_boolean_to_number_returns_null() {
        JsonObject input = new JsonObject();
        input.add("t", new JsonBoolean(true));
        JsonObject result = MapOperatorHelper.processOperator(
                new AddFieldMapOperator("n", null, new CastMidOperator("t", CastToType.NUMBER)), input);
        assertTrue(result.get("n").isJsonNull());
    }

    @Test
    public void test_cast_number_to_number_returns_same() {
        JsonObject input = new JsonObject();
        input.addProperty("n", 42);
        CastMidOperator cast = new CastMidOperator("n", CastToType.NUMBER);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals(42, result.get("out").asJsonNumber().asInteger());
    }

    @Test
    public void test_cast_string_to_number_parseable() {
        JsonObject input = new JsonObject();
        input.addProperty("s", "3.14");
        CastMidOperator cast = new CastMidOperator("s", CastToType.NUMBER);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals(3.14, result.get("out").asJsonNumber().getValue().doubleValue(), 0.001);
    }

    @Test
    public void test_cast_string_to_string_returns_same() {
        JsonObject input = new JsonObject();
        input.addProperty("s", "hello");
        CastMidOperator cast = new CastMidOperator("s", CastToType.STRING);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals("hello", result.get("out").asJsonString().getValue());
    }

    @Test
    public void test_cast_boolean_to_string() {
        JsonObject input = new JsonObject();
        input.add("flag", new JsonBoolean(true));
        CastMidOperator cast = new CastMidOperator("flag", CastToType.STRING);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals("true", result.get("out").asJsonString().getValue());
    }

    @Test
    public void test_cast_boolean_to_boolean_returns_same() {
        JsonObject input = new JsonObject();
        input.add("flag", new JsonBoolean(false));
        CastMidOperator cast = new CastMidOperator("flag", CastToType.BOOLEAN);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertFalse(result.get("out").asJsonBoolean().getValue());
    }

    @Test
    public void test_cast_null_field_to_number_returns_null() {
        JsonObject input = new JsonObject();
        input.add("n", JsonNull.INSTANCE);
        CastMidOperator cast = new CastMidOperator("n", CastToType.NUMBER);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertTrue(result.get("out").isJsonNull());
    }

    @Test
    public void test_cast_string_to_datetime_via_json_custom() {
        JsonObject input = new JsonObject();
        input.add("ts", new JsonString("2024-01-15T10:30:00"));
        CastMidOperator cast = new CastMidOperator("ts", "datetime");
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertInstanceOf(JsonDateTime.class, result.get("out"));
        assertEquals("2024-01-15T10:30:00", ((JsonCustom<?>) result.get("out")).stringDataValue());
    }

    @Test
    public void test_cast_invalid_string_to_custom_type_returns_null() {
        JsonObject input = new JsonObject();
        input.add("ts", new JsonString("not-a-date"));
        CastMidOperator cast = new CastMidOperator("ts", "datetime");
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertTrue(result.get("out").isJsonNull());
    }

    @Test
    public void test_cast_custom_type_to_same_custom_type_returns_same() {
        JsonObject input = new JsonObject();
        input.add("ts", new JsonDateTime(LocalDateTime.of(2024, 1, 15, 10, 30, 0)));
        CastMidOperator cast = new CastMidOperator("ts", "datetime");
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertInstanceOf(JsonDateTime.class, result.get("out"));
    }

    @Test
    public void test_cast_string_to_time_via_json_custom() {
        JsonObject input = new JsonObject();
        input.add("t", new JsonString("10:30:00"));
        CastMidOperator cast = new CastMidOperator("t", "time");
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertInstanceOf(JsonTime.class, result.get("out"));
        assertEquals("10:30:00", ((JsonCustom<?>) result.get("out")).stringDataValue());
    }

    @Test
    public void test_cast_datetime_to_string_yields_data_value() {
        JsonObject input = new JsonObject();
        input.add("ts", new JsonDateTime(LocalDateTime.of(2024, 1, 15, 10, 30, 0)));
        CastMidOperator cast = new CastMidOperator("ts", CastToType.STRING);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals("2024-01-15T10:30", result.get("out").asJsonString().getValue());
    }

    @Test
    public void test_cast_time_to_string_yields_data_value() {
        JsonObject input = new JsonObject();
        input.add("t", new JsonTime(LocalTime.of(10, 30, 0)));
        CastMidOperator cast = new CastMidOperator("t", CastToType.STRING);
        JsonObject result = MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input);
        assertEquals("10:30", result.get("out").asJsonString().getValue());
    }

    @Test
    public void test_cast_infinity_string_to_number_returns_null() {
        assertTrue(castToNumber("Infinity").isJsonNull());
        assertTrue(castToNumber("-Infinity").isJsonNull());
    }

    @Test
    public void test_cast_nan_string_to_number_returns_null() {
        assertTrue(castToNumber("NaN").isJsonNull());
    }

    @Test
    public void test_cast_hex_float_string_to_number_returns_null() {
        assertTrue(castToNumber("0x1p3").isJsonNull());
    }

    @Test
    public void test_cast_java_suffix_string_to_number_returns_null() {
        assertTrue(castToNumber("1d").isJsonNull());
        assertTrue(castToNumber("1f").isJsonNull());
    }

    @Test
    public void test_cast_padded_numeric_string_to_number_returns_null() {
        assertTrue(castToNumber(" 1 ").isJsonNull());
    }

    @Test
    public void test_cast_leading_plus_string_to_number_returns_null() {
        assertTrue(castToNumber("+1").isJsonNull());
    }

    @Test
    public void test_cast_malformed_numeric_string_to_number_returns_null() {
        assertTrue(castToNumber("").isJsonNull());
        assertTrue(castToNumber("-").isJsonNull());
        assertTrue(castToNumber(".").isJsonNull());
        assertTrue(castToNumber("--1").isJsonNull());
    }

    @Test
    public void test_cast_overflowing_exponent_string_to_number_returns_null() {
        assertTrue(castToNumber("1e400").isJsonNull());
    }

    @Test
    public void test_cast_exponent_string_to_number_parses() {
        assertEquals(1000, castToNumber("1e3").asJsonNumber().getValue().intValue());
        assertEquals(1000, castToNumber("1e+3").asJsonNumber().getValue().intValue());
        assertEquals(0.001, castToNumber("1e-3").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_cast_integral_string_to_number_narrows_like_the_parser() {
        assertEquals(new JsonNumber("5").getValue(), castToNumber("5").asJsonNumber().getValue());
        assertInstanceOf(Integer.class, castToNumber("5").asJsonNumber().getValue());
        assertEquals(new JsonNumber("2147483647").getValue(), castToNumber("2147483647").asJsonNumber().getValue());
        assertEquals(new JsonNumber("2147483648").getValue(), castToNumber("2147483648").asJsonNumber().getValue());
    }

    @Test
    public void test_cast_non_finite_number_field_to_number_returns_null() {
        final var input = new JsonObject();
        input.add("n", new JsonNumber(Double.POSITIVE_INFINITY));
        final var operator = new AddFieldMapOperator("out", null, new CastMidOperator("n", CastToType.NUMBER));
        assertTrue(MapOperatorHelper.processOperator(operator, input).get("out").isJsonNull());
    }

    @Test
    public void test_cast_unparseable_string_to_boolean_returns_null() {
        assertTrue(castToBoolean("banana").isJsonNull());
        assertTrue(castToBoolean("").isJsonNull());
    }

    @Test
    public void test_cast_false_string_to_boolean_returns_false() {
        assertFalse(castToBoolean("false").asJsonBoolean().getValue());
        assertFalse(castToBoolean("FALSE").asJsonBoolean().getValue());
        assertTrue(castToBoolean("TRUE").asJsonBoolean().getValue());
    }

    private static JsonBaseElement castToNumber(String raw) {
        return castField(raw, new CastMidOperator("value", CastToType.NUMBER));
    }

    private static JsonBaseElement castToBoolean(String raw) {
        return castField(raw, new CastMidOperator("value", CastToType.BOOLEAN));
    }

    private static JsonBaseElement castField(String raw, CastMidOperator cast) {
        final var input = new JsonObject();
        input.add("value", new JsonString(raw));
        return MapOperatorHelper.processOperator(new AddFieldMapOperator("out", null, cast), input).get("out");
    }

    private static String castToString(Number value) {
        final var jsonObject = new JsonObject();
        jsonObject.addProperty("value", value);
        final var operator = new AddFieldMapOperator("asText", null, new CastMidOperator("value", CastToType.STRING));
        return MapOperatorHelper.processOperator(operator, jsonObject).get("asText").asJsonString().getValue();
    }

    @Test
    public void test_cast_to_string_of_a_number_above_the_int_range() {
        assertEquals("3000000000", castToString(3000000000L),
                "an int cast clamps, so a number past Integer.MAX_VALUE must not go through one");
    }

    @Test
    public void test_cast_to_string_matches_the_documents_own_number_text() {
        final var disagreeing = new java.util.ArrayList<String>();
        for (final var value : List.of(0.0d, -0.0d, 123.0d, 3000000000.0d, 9007199254740994.0d, 0.0000001d, 1.0e21d)) {
            final var casted = castToString(value);
            final var documentText = NumberFormatter.toJsString(value);
            if (!documentText.equals(casted)) {
                disagreeing.add(value + " cast to \"" + casted + "\" but serializes as \"" + documentText + "\"");
            }
        }
        assertEquals(List.of(), disagreeing,
                "CAST to STRING must spell a number exactly as the document serializer spells it");
    }
}
