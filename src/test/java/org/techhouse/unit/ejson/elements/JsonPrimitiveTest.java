package org.techhouse.unit.ejson.elements;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.*;

import static org.junit.jupiter.api.Assertions.*;

public class JsonPrimitiveTest {
    // Set and get primitive values of different types (String, Number, Boolean)
    @Test
    public void test_set_get_primitive_values() {
        JsonString stringPrimitive = new JsonString("test");
        JsonNumber numberPrimitive = new JsonNumber(42);
        JsonBoolean booleanPrimitive = new JsonBoolean(true);

        assertEquals("test", stringPrimitive.getValue());
        assertEquals(42, numberPrimitive.getValue());
        assertEquals(true, booleanPrimitive.getValue());

        stringPrimitive.setValue("updated");
        numberPrimitive.setValue(99);
        booleanPrimitive.setValue(false);

        assertEquals("updated", stringPrimitive.getValue());
        assertEquals(99, numberPrimitive.getValue());
        assertEquals(false, booleanPrimitive.getValue());
    }

    // Compare null values in equals method
    @Test
    public void test_equals_with_null_values() {
        JsonString primitive1 = new JsonString();
        primitive1.setValue(null);
    
        JsonString primitive2 = new JsonString();
        primitive2.setValue(null);
    
        JsonString primitive3 = new JsonString();
        primitive3.setValue("not null");

        assertEquals(primitive1, primitive2);
        assertNotEquals(primitive1, primitive3);
        assertNotEquals(null, primitive1);
        assertEquals(primitive1, primitive1);
    }

    // Returns hashCode of the underlying value for non-null, non-numeric values
    @Test
    public void test_hashcode_returns_value_hashcode_for_string() {
        String testValue = "test";
        JsonPrimitive<String> primitive = new JsonString(testValue);

        int expectedHashCode = testValue.hashCode();
        int actualHashCode = primitive.hashCode();

        assertEquals(expectedHashCode, actualHashCode);
    }

    // Returns 31 when value is null
    @Test
    public void test_hashcode_returns_31_for_null_value() {
        JsonPrimitive<String> primitive = new JsonString(null);

        int hashCode = primitive.hashCode();

        assertEquals(31, hashCode);
    }

    // Compare two JsonPrimitive objects with same non-null values returns true
    @Test
    public void test_equal_primitives_with_same_values_returns_true() {
        JsonPrimitive<String> primitive1 = new JsonString("test");
        JsonPrimitive<String> primitive2 = new JsonString("test");

        boolean result = primitive1.equals(primitive2);

        assertTrue(result);
    }

    // Compare with null object returns false
    @Test
    public void test_equals_with_null_returns_false() {
        JsonPrimitive<String> primitive = new JsonString("test");

        boolean result = primitive.equals(null);

        assertFalse(result);
    }

    // Copying JsonNumber returns new JsonNumber instance with same numeric value
    @Test
    public void test_json_number_deep_copy_creates_new_instance_with_same_value() {
        JsonNumber original = new JsonNumber(42);

        JsonBaseElement copy = original.deepCopy();

        assertAll(
                () -> assertInstanceOf(JsonNumber.class, copy),
                () -> assertNotSame(original, copy),
                () -> {
                    assert copy instanceof JsonNumber;
                    assertEquals(original.getValue(), ((JsonNumber)copy).getValue());
                }
        );
    }
}