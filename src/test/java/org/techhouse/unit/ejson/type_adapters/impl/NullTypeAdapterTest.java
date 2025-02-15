package org.techhouse.unit.ejson.type_adapters.impl;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.NullTypeAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class NullTypeAdapterTest {
    // toJson returns 'null' string for any input object
    @Test
    public void test_to_json_returns_null_string_for_regular_objects() {
        NullTypeAdapter adapter = new NullTypeAdapter();

        String result1 = adapter.toJson("test string");
        String result2 = adapter.toJson(42);
        String result3 = adapter.toJson(new Object());

        assertEquals("null", result1);
        assertEquals("null", result2); 
        assertEquals("null", result3);
    }

    // toJson behavior with undefined or special object types
    @Test
    public void test_to_json_returns_null_for_edge_cases() {
        NullTypeAdapter adapter = new NullTypeAdapter();

        String resultNull = adapter.toJson(null);
        String resultUndefined = adapter.toJson(Double.NaN);
        String resultInfinity = adapter.toJson(Double.POSITIVE_INFINITY);

        assertEquals("null", resultNull);
        assertEquals("null", resultUndefined);
        assertEquals("null", resultInfinity);
    }

    // Method returns string "null" when given a null input
    @Test
    public void test_null_input_returns_null_string() {
        NullTypeAdapter adapter = new NullTypeAdapter();

        String result = adapter.toJson(null);

        assertEquals("null", result);
    }

    // Method behavior with undefined/empty input
    @Test
    public void test_empty_input_returns_null_string() {
        NullTypeAdapter adapter = new NullTypeAdapter();

        String result = adapter.toJson("");

        assertEquals("null", result);
    }

    // Method returns null for any JsonBaseElement input
    @Test
    public void test_returns_null_for_json_base_element() {
        NullTypeAdapter adapter = new NullTypeAdapter();
        JsonString jsonString = new JsonString("test");

        Object result = adapter.fromJson(jsonString);

        assertNull(result);
    }

    // Method returns null when input is null
    @Test
    public void test_returns_null_for_null_input() {
        NullTypeAdapter adapter = new NullTypeAdapter();

        Object result = adapter.fromJson(null);

        assertNull(result);
    }
}