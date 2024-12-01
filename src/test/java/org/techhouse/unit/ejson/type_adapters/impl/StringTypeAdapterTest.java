package org.techhouse.unit.ejson.type_adapters.impl;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.StringTypeAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class StringTypeAdapterTest {
    // Convert non-null string to JSON format with surrounding quotes
    @Test
    public void test_to_json_adds_quotes_to_string() {
        StringTypeAdapter adapter = new StringTypeAdapter();
        String input = "test string";
        String result = adapter.toJson(input);
        assertEquals("\"test string\"", result);
    }

    // Handle null input in toJson by returning "null" string
    @Test
    public void test_to_json_handles_null_input() {
        StringTypeAdapter adapter = new StringTypeAdapter();
        String result = adapter.toJson(null);
        assertEquals("null", result);
    }

    // Convert non-null string to JSON format by adding double quotes
    @Test
    public void test_string_value_converted_to_json_format() {
        StringTypeAdapter adapter = new StringTypeAdapter();
        String input = "test";
        String result = adapter.toJson(input);
        assertEquals("\"test\"", result);
    }

    // Convert null value to "null" string
    @Test
    public void test_null_value_converted_to_null_string() {
        StringTypeAdapter adapter = new StringTypeAdapter();
        String result = adapter.toJson(null);
        assertEquals("null", result);
    }

    // Input JsonString element returns its string value
    @Test
    public void test_json_string_returns_value() {
        JsonString jsonString = new JsonString("test value");
        StringTypeAdapter converter = new StringTypeAdapter();

        String result = converter.fromJson(jsonString);

        assertEquals("test value", result);
    }

    // Input null JsonBaseElement returns null
    @Test
    public void test_null_json_element_returns_null() {
        JsonNumber jsonNumber = new JsonNumber(123);
        StringTypeAdapter converter = new StringTypeAdapter();

        String result = converter.fromJson(jsonNumber);

        assertNull(result);
    }
}