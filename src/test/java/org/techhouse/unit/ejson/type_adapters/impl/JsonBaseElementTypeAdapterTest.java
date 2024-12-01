package org.techhouse.unit.ejson.type_adapters.impl;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.type_adapters.impl.JsonBaseElementTypeAdapter;

import static org.junit.jupiter.api.Assertions.*;

public class JsonBaseElementTypeAdapterTest {
    // Convert JsonBoolean element to JSON string representation
    @Test
    public void test_boolean_element_to_json_string() {
        new EJson();
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();

        JsonBoolean boolElement = new JsonBoolean(true);

        String result = adapter.toJson(boolElement);

        assertEquals("true", result);
    }

    // Handle null input value
    @Test
    public void test_null_input_value() {
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();

        assertThrows(NullPointerException.class, () -> adapter.toJson(null));
    }

    // Convert JsonNull element to "null" string
    @Test
    public void test_json_null_element_converts_to_null_string() {
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();
        JsonNull jsonNull = new JsonNull();

        String result = adapter.toJson(jsonNull);

        assertEquals("null", result);
    }

    // Handle null JsonBaseElement input
    @Test
    public void test_null_input_throws_exception() {
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();

        assertThrows(NullPointerException.class, () -> adapter.toJson(null));
    }

    // Return same JsonBaseElement instance when passed as input
    @Test
    public void test_returns_same_json_base_element_instance() {
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();

        JsonBaseElement input = new JsonObject();

        JsonBaseElement result = adapter.fromJson(input);

        assertSame(input, result);
    }

    // Pass null value as input parameter
    @Test
    public void test_accepts_null_input() {
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();

        JsonBaseElement result = adapter.fromJson(null);

        assertNull(result);
    }
}