package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;

public class JsonNumberTest {
    @Test
    public void test_number_constructor_sets_value_and_length() {
        Integer testValue = 12345;
        JsonNumber jsonNumber = new JsonNumber(testValue);

        assertEquals(testValue, jsonNumber.getValue());
        assertEquals(5, jsonNumber.getStrLength());
    }

    @Test
    public void test_number_constructor_handles_null() {
        JsonNumber jsonNumber = new JsonNumber((Number) null);

        assertNull(jsonNumber.getValue());
        assertEquals(0, jsonNumber.getStrLength());
    }

    @Test
    public void test_constructor_initializes_value_to_zero() {
        JsonNumber number = new JsonNumber();

        assertEquals(0, number.getValue());
    }

    @Test
    public void test_constructor_value_remains_zero_on_multiple_calls() {
        JsonNumber number1 = new JsonNumber();
        JsonNumber number2 = new JsonNumber();
        JsonNumber number3 = new JsonNumber();

        assertEquals(0, number1.getValue());
        assertEquals(0, number2.getValue());
        assertEquals(0, number3.getValue());
    }

    @Test
    public void test_constructor_assigns_number_value() {
        Number inputValue = 42;
        JsonNumber jsonNumber = new JsonNumber(inputValue);

        assertEquals(inputValue, jsonNumber.getValue());
        assertEquals(2, jsonNumber.getStrLength());
    }

    @Test
    public void test_constructor_handles_null_input() {
        JsonNumber jsonNumber = new JsonNumber((Number) null);

        assertNull(jsonNumber.getValue());
        assertEquals(0, jsonNumber.getStrLength());
    }

    @Test
    public void test_integer_string_parsed_as_integer() {
        JsonNumber jsonNumber = new JsonNumber("42");

        assertEquals(Integer.class, jsonNumber.getValue().getClass());
        assertEquals(42, jsonNumber.getValue());
        assertEquals(2, jsonNumber.getStrLength());
    }

    @Test
    public void test_null_input_results_in_null_value() {
        JsonNumber jsonNumber = new JsonNumber((String) null);

        assertNull(jsonNumber.getValue());
        assertEquals(0, jsonNumber.getStrLength());
    }

    @Test
    public void test_valid_number_returns_integer() {
        JsonNumber jsonNumber = new JsonNumber(42);

        Integer result = jsonNumber.asInteger();

        assertEquals(42, result);
    }

    @Test
    public void test_max_integer_value() {
        JsonNumber jsonNumber = new JsonNumber(Integer.MAX_VALUE);

        Integer result = jsonNumber.asInteger();

        assertEquals(Integer.MAX_VALUE, result);
    }

    @Test
    public void test_string_constructor_non_integer_value() {
        JsonNumber num = new JsonNumber("3.14");
        assertEquals(3.14, num.getValue().doubleValue(), 0.001);
    }

    @Test
    public void test_string_constructor_keeps_large_integral_values() {
        assertEquals(1e10, new JsonNumber("10000000000").getValue().doubleValue());
        assertEquals(1e20, new JsonNumber("100000000000000000000").getValue().doubleValue());
        assertEquals(1e21, new JsonNumber("1e+21").getValue().doubleValue());
        assertEquals(-1e10, new JsonNumber("-10000000000").getValue().doubleValue());
    }

    @Test
    public void test_string_constructor_keeps_int_representation_in_range() {
        assertEquals(42, new JsonNumber("42").getValue());
        assertEquals(Integer.MAX_VALUE, new JsonNumber("2147483647").getValue());
        assertEquals(-2147483647, new JsonNumber("-2147483647").getValue());
    }
}
