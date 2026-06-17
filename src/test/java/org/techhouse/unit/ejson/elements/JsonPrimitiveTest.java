package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonPrimitive;
import org.techhouse.ejson.elements.JsonString;

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

        assertAll(() -> assertInstanceOf(JsonNumber.class, copy), () -> assertNotSame(original, copy), () -> {
            assert copy instanceof JsonNumber;
            assertEquals(original.getValue(), ((JsonNumber) copy).getValue());
        });
    }

    // deepCopy on JsonBoolean returns new instance with same value (L49)
    @Test
    public void test_json_boolean_deep_copy() {
        JsonBoolean original = new JsonBoolean(true);
        JsonBaseElement copy = original.deepCopy();
        assertInstanceOf(JsonBoolean.class, copy);
        assertNotSame(original, copy);
        assertEquals(true, ((JsonBoolean) copy).getValue());
    }

    @Test
    public void test_equals_two_numbers_same_value_returns_true() {
        JsonNumber n1 = new JsonNumber(3.14);
        JsonNumber n2 = new JsonNumber(3.14);
        assertEquals(n1, n2);
    }

    @Test
    public void test_equals_two_numbers_different_value_returns_false() {
        JsonNumber n1 = new JsonNumber(1.0);
        JsonNumber n2 = new JsonNumber(2.0);
        assertNotEquals(n1, n2);
    }

    @Test
    public void test_equals_nan_values_returns_true() {
        JsonNumber n1 = new JsonNumber(Double.NaN);
        JsonNumber n2 = new JsonNumber(Double.NaN);
        assertEquals(n1, n2);
    }

    @Test
    public void test_equals_different_primitive_types_returns_false() {
        JsonString s = new JsonString("true");
        JsonBoolean b = new JsonBoolean(true);
        assertNotEquals(s, b);
    }

    @Test
    public void test_hashCode_number_returns_value_hashcode() {
        JsonNumber n = new JsonNumber(42);
        assertEquals(n.getValue().hashCode(), n.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        JsonString s = new JsonString("hello");
        assertNotNull(s.toString());
    }
}
