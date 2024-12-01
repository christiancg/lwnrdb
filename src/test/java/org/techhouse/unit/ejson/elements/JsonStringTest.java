package org.techhouse.unit.ejson.elements;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonString;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class JsonStringTest {
    // Create JsonString with empty constructor should initialize with empty string value
    @Test
    public void test_empty_constructor_initializes_empty_string() {
        JsonString jsonString = new JsonString();
    
        assertEquals("", jsonString.getValue());
    }

    // Create JsonString with null value
    @Test
    public void test_constructor_with_null_value() {
        JsonString jsonString = new JsonString(null);
    
        assertNull(jsonString.getValue());
    }

    // Constructor should set the value field to the provided string parameter
    @Test
    public void test_constructor_sets_value_field() {
        String testValue = "test string";
        JsonString jsonString = new JsonString(testValue);

        assertEquals(testValue, jsonString.getValue());
    }

    // Constructor should handle null input value
    @Test
    public void test_constructor_handles_null_input() {
        JsonString jsonString = new JsonString(null);

        assertNull(jsonString.getValue());
    }
}