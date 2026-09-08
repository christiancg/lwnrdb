package org.techhouse.unit.ejson.type_adapters.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.NumberTypeAdapter;

public class NumberTypeAdapterTest {
    // Convert integer numbers to JSON string representation without decimal points
    @Test
    public void test_integer_number_converts_to_json_without_decimals() {
        NumberTypeAdapter adapter = new NumberTypeAdapter();
        Integer input = 42;
        String result = adapter.toJson(input);
        assertEquals("42", result);
    }

    // Handle null input value in toJson method
    @Test
    public void test_null_input_converts_to_null_string() {
        NumberTypeAdapter adapter = new NumberTypeAdapter();
        Number input = null;
        String result = adapter.toJson(input);
        assertEquals("null", result);
    }

    // Convert integer number to JSON string representation
    @Test
    public void test_integer_to_json_string() {
        NumberTypeAdapter adapter = new NumberTypeAdapter();
        Integer value = 42;
        String result = adapter.toJson(value);
        assertEquals("42", result);
    }

    // Handle null input by returning "null" string
    @Test
    public void test_null_input_returns_null_string() {
        NumberTypeAdapter adapter = new NumberTypeAdapter();
        String result = adapter.toJson(null);
        assertEquals("null", result);
    }

    // A double past the long range keeps its digits instead of clamping to Long.MAX_VALUE
    @Test
    public void test_large_double_is_not_clamped() {
        NumberTypeAdapter adapter = new NumberTypeAdapter();
        assertEquals("100000000000000000000", adapter.toJson(1e20));
        assertEquals("1e+21", adapter.toJson(1e21));
    }

    // A small double uses the JS exponential form rather than the Java one
    @Test
    public void test_small_double_uses_js_form() {
        NumberTypeAdapter adapter = new NumberTypeAdapter();
        assertEquals("1e-7", adapter.toJson(1e-7));
        assertEquals("0.000001", adapter.toJson(1e-6));
    }

    // Integral boxed types render their exact digits, past the double precision limit
    @Test
    public void test_long_keeps_exact_digits() {
        NumberTypeAdapter adapter = new NumberTypeAdapter();
        assertEquals("9007199254740993", adapter.toJson(9007199254740993L));
        assertEquals("123456789012345678901234567890",
                adapter.toJson(new java.math.BigInteger("123456789012345678901234567890")));
        assertEquals("1.50", adapter.toJson(new java.math.BigDecimal("1.50")));
    }

    // Ordinary doubles keep their existing rendering
    @Test
    public void test_integral_double_unchanged() {
        NumberTypeAdapter adapter = new NumberTypeAdapter();
        assertEquals("1", adapter.toJson(1.0));
        assertEquals("42", adapter.toJson(42.0));
        assertEquals("1.5", adapter.toJson(1.5));
        assertEquals("0", adapter.toJson(-0.0));
    }

    // Input JsonNumber returns its numeric value
    @Test
    public void test_json_number_returns_numeric_value() {
        NumberTypeAdapter adapter = new NumberTypeAdapter();
        JsonNumber jsonNumber = new JsonNumber(42);

        Number result = adapter.fromJson(jsonNumber);

        assertEquals(42, result.intValue());
    }

    // Input non-NUMBER JsonType returns null
    @Test
    public void test_non_number_type_returns_null() {
        NumberTypeAdapter adapter = new NumberTypeAdapter();
        JsonString jsonString = new JsonString("not a number");

        Number result = adapter.fromJson(jsonString);

        assertNull(result);
    }
}
