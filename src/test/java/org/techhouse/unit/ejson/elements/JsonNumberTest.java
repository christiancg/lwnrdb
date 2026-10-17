package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.exceptions.MalformedJsonException;

public class JsonNumberTest {
    @Test
    public void test_every_value_is_held_as_a_double() {
        assertEquals(Double.class, new JsonNumber(12345).getValue().getClass());
        assertEquals(Double.class, new JsonNumber(Integer.valueOf(42)).getValue().getClass());
        assertEquals(Double.class, new JsonNumber("42").getValue().getClass());
        assertEquals(Double.class, new JsonNumber(7L).getValue().getClass());
        assertEquals(12345.0, new JsonNumber(12345).doubleValue());
        assertEquals(42.0, new JsonNumber("42").doubleValue());
    }

    @Test
    public void test_absent_inputs_and_the_default_are_zero() {
        assertEquals(0.0, new JsonNumber().doubleValue());
        assertEquals(0.0, new JsonNumber((Number) null).doubleValue());
        assertEquals(0.0, new JsonNumber((String) null).doubleValue());
    }

    @Test
    public void test_valid_number_returns_integer() {
        assertEquals(42, new JsonNumber(42).asInteger());
        assertEquals(Integer.MAX_VALUE, new JsonNumber(Integer.MAX_VALUE).asInteger());
        assertEquals(3, new JsonNumber(3.9).asInteger());
    }

    @Test
    public void test_string_constructor_non_integer_value() {
        assertEquals(3.14, new JsonNumber("3.14").doubleValue(), 0.001);
    }

    @Test
    public void test_string_constructor_keeps_large_integral_values() {
        assertEquals(1e10, new JsonNumber("10000000000").doubleValue());
        assertEquals(1e20, new JsonNumber("100000000000000000000").doubleValue());
        assertEquals(1e21, new JsonNumber("1e+21").doubleValue());
        assertEquals(-1e10, new JsonNumber("-10000000000").doubleValue());
    }

    @Test
    public void test_every_long_up_to_two_to_the_53_round_trips_exactly() {
        for (final var value : new long[]{0L, 1L, -1L, 1_700_000_000_000L, (1L << 53) - 1, -(1L << 53) + 1, 1L << 53}) {
            assertEquals(value, (long) new JsonNumber(value).doubleValue());
            assertEquals(value, new JsonNumber(value).getValue().longValue());
        }
    }

    @Test
    public void test_overflowing_literal_is_refused() {
        assertThrows(MalformedJsonException.class, () -> new JsonNumber("1e400"));
        assertThrows(MalformedJsonException.class, () -> new JsonNumber("-1e400"));
        assertThrows(MalformedJsonException.class, () -> new JsonNumber("1" + "0".repeat(400)));
    }

    @Test
    public void test_max_double_is_accepted() {
        assertEquals(Double.MAX_VALUE, new JsonNumber("1.7976931348623157e308").doubleValue());
    }

    @Test
    public void test_underflowing_literal_is_zero() {
        assertEquals(0.0, new JsonNumber("1e-400").doubleValue());
    }

    @Test
    public void test_equality_is_numeric_with_zero_and_nan_normalised() {
        assertEquals(new JsonNumber(0.0), new JsonNumber(-0.0));
        assertEquals(new JsonNumber(0.0).hashCode(), new JsonNumber("-0.0").hashCode());
        assertEquals(new JsonNumber(5), new JsonNumber("5.0"));
        assertEquals(new JsonNumber(5).hashCode(), new JsonNumber(5.0).hashCode());
        assertEquals(new JsonNumber(Double.NaN), new JsonNumber(Double.NaN));
        assertNotEquals(new JsonNumber(1), new JsonNumber(2));
        assertNotEquals(new Object(), new JsonNumber(1));
    }

    @Test
    public void test_every_spelling_of_negative_zero_parses_to_positive_zero() {
        for (final var spelling : new String[]{"-0", "-0.0", "-0e5", "-0.000", "-1e-400"}) {
            assertEquals(0, Double.compare(0.0, new JsonNumber(spelling).doubleValue()), spelling);
        }
        assertEquals(0, Double.compare(-0.0, new JsonNumber(-0.0).doubleValue()));
    }
}
