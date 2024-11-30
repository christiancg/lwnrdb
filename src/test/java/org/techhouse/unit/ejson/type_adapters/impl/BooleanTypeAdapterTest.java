package org.techhouse.unit.ejson.type_adapters.impl;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.BooleanTypeAdapter;

import static org.junit.jupiter.api.Assertions.*;

public class BooleanTypeAdapterTest {
    // Converting true boolean value to JSON string returns "true"
    @Test
    public void test_true_false_value_converts_to_json_string() {
        BooleanTypeAdapter adapter = new BooleanTypeAdapter();
        String resultTrue = adapter.toJson(true);
        assertEquals("true", resultTrue);
        String resultFalse = adapter.toJson(false);
        assertEquals("false", resultFalse);
    }

    // Converting null boolean value to JSON string
    @Test
    public void test_null_value_converts_to_json_string() {
        BooleanTypeAdapter adapter = new BooleanTypeAdapter();
    
        assertThrows(NullPointerException.class, () -> adapter.toJson(null));
    }

    // Convert JsonBoolean element to Boolean value
    @Test
    public void test_convert_json_boolean_to_boolean() {
        BooleanTypeAdapter adapter = new BooleanTypeAdapter();
        JsonBoolean jsonBoolean = new JsonBoolean(true);

        Boolean result = adapter.fromJson(jsonBoolean);

        assertEquals(true, result);
    }

    // Convert JsonString element with 'true' value to Boolean
    @Test
    public void test_convert_jsonstring_true_to_boolean() {
        JsonString jsonString = new JsonString("true");
        BooleanTypeAdapter adapter = new BooleanTypeAdapter();
        Boolean result = adapter.fromJson(jsonString);
        assertTrue(result);
    }
}