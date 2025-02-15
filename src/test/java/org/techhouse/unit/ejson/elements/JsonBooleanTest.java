package org.techhouse.unit.ejson.elements;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

public class JsonBooleanTest {
    // Default constructor initializes value to false
    @Test
    public void test_default_constructor_sets_false() {
        JsonBoolean jsonBoolean = new JsonBoolean();
    
        assertFalse(jsonBoolean.getValue());
    }

    // Constructor handling of null boolean value
    @Test
    public void test_constructor_accepts_null_value() {
        JsonBoolean jsonBoolean = new JsonBoolean(null);
    
        assertNull(jsonBoolean.getValue());
    }
}