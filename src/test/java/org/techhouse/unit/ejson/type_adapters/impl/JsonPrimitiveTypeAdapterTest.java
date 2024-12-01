package org.techhouse.unit.ejson.type_adapters.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonPrimitive;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.JsonPrimitiveTypeAdapter;

import static org.junit.jupiter.api.Assertions.*;

public class JsonPrimitiveTypeAdapterTest {

    @BeforeEach
    public void setUp() {
        new EJson();
    }

    // Convert JsonPrimitive boolean value to JSON string
    @Test
    public void test_boolean_primitive_to_json() {
        JsonPrimitiveTypeAdapter adapter = new JsonPrimitiveTypeAdapter();
    
        JsonBoolean booleanValue = new JsonBoolean(true);
    
        String result = adapter.toJson(booleanValue);
    
        assertEquals("true", result);
    }

    // Convert JsonPrimitive with string value to JSON string
    @Test
    public void test_json_primitive_string_value_conversion() {
        JsonPrimitiveTypeAdapter adapter = new JsonPrimitiveTypeAdapter();
        JsonPrimitive<String> primitive = new JsonString("test");

        String result = adapter.toJson(primitive);

        assertEquals("\"test\"", result);
    }

    // Handle null JsonPrimitive value
    @Test
    public void test_null_json_primitive_handling() {
        JsonPrimitiveTypeAdapter adapter = new JsonPrimitiveTypeAdapter();
        JsonPrimitive<?> primitive = null;

        String result = adapter.toJson(primitive);

        assertEquals("null", result);
    }

    // Converting JsonBoolean element returns correct JsonPrimitive
    @Test
    public void test_boolean_element_converts_to_primitive() {
        JsonPrimitiveTypeAdapter adapter = new JsonPrimitiveTypeAdapter();
        JsonBoolean boolElement = new JsonBoolean(true);

        JsonPrimitive<?> result = adapter.fromJson(boolElement);

        assertNotNull(result);
        assertTrue(result.isJsonBoolean());
        assertEquals(true, result.getValue());
    }

    // Passing null as input value
    @Test
    public void test_null_input_returns_null() {
        JsonPrimitiveTypeAdapter adapter = new JsonPrimitiveTypeAdapter();

        JsonPrimitive<?> result = adapter.fromJson(null);

        assertNull(result);
    }
}