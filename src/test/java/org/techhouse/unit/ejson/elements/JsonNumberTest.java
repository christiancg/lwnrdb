package org.techhouse.unit.ejson.elements;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class JsonNumberTest {
    // Constructor with Number value creates JsonNumber with correct value and string length
    @Test
    public void test_number_constructor_sets_value_and_length() {
        Integer testValue = 12345;
        JsonNumber jsonNumber = new JsonNumber(testValue);

        assertEquals(testValue, jsonNumber.getValue());
        assertEquals(5, jsonNumber.getStrLength());
    }

    // Constructor handles null Number input
    @Test
    public void test_number_constructor_handles_null() {
        JsonNumber jsonNumber = new JsonNumber((Number)null);

        assertNull(jsonNumber.getValue());
        assertEquals(0, jsonNumber.getStrLength());
    }

    // Constructor initializes value to 0
    @Test
    public void test_constructor_initializes_value_to_zero() {
        JsonNumber number = new JsonNumber();

        assertEquals(0, number.getValue());
    }

    // Value remains 0 even when called multiple times
    @Test
    public void test_constructor_value_remains_zero_on_multiple_calls() {
        JsonNumber number1 = new JsonNumber();
        JsonNumber number2 = new JsonNumber();
        JsonNumber number3 = new JsonNumber();

        assertEquals(0, number1.getValue());
        assertEquals(0, number2.getValue());
        assertEquals(0, number3.getValue());
    }

    // Constructor correctly assigns Number value to internal value field
    @Test
    public void test_constructor_assigns_number_value() {
        Number inputValue = 42;
        JsonNumber jsonNumber = new JsonNumber(inputValue);

        assertEquals(inputValue, jsonNumber.getValue());
        assertEquals(2, jsonNumber.getStrLength());
    }

    // Constructor handles null input by not modifying default values
    @Test
    public void test_constructor_handles_null_input() {
        JsonNumber jsonNumber = new JsonNumber((Number)null);

        assertNull(jsonNumber.getValue());
        assertEquals(0, jsonNumber.getStrLength());
    }

    // String value representing an integer is correctly parsed and stored as Integer
    @Test
    public void test_integer_string_parsed_as_integer() {
        JsonNumber jsonNumber = new JsonNumber("42");

        assertEquals(Integer.class, jsonNumber.getValue().getClass());
        assertEquals(42, jsonNumber.getValue());
        assertEquals(2, jsonNumber.getStrLength());
    }

    // Null input value results in null fields
    @Test
    public void test_null_input_results_in_null_value() {
        JsonNumber jsonNumber = new JsonNumber((String)null);

        assertNull(jsonNumber.getValue());
        assertEquals(0, jsonNumber.getStrLength());
    }

    // Return integer value when JsonNumber contains a valid number
    @Test
    public void test_valid_number_returns_integer() {
        JsonNumber jsonNumber = new JsonNumber(42);

        Integer result = jsonNumber.asInteger();

        assertEquals(42, result);
    }

    // Handle maximum Integer value (Integer.MAX_VALUE)
    @Test
    public void test_max_integer_value() {
        JsonNumber jsonNumber = new JsonNumber(Integer.MAX_VALUE);

        Integer result = jsonNumber.asInteger();

        assertEquals(Integer.MAX_VALUE, result);
    }
}