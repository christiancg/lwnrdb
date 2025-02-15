package org.techhouse.unit.ejson.type_adapters.impl;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.NumberTypeAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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