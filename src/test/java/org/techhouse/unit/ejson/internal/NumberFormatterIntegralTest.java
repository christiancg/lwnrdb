package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.NumberFormatter;

public class NumberFormatterIntegralTest {
    @Test
    public void test_small_integers_render_without_a_fraction() {
        assertEquals("0", NumberFormatter.toJsString(0d));
        assertEquals("1", NumberFormatter.toJsString(1d));
        assertEquals("-1", NumberFormatter.toJsString(-1d));
        assertEquals("42", NumberFormatter.toJsString(42d));
        assertEquals("-42", NumberFormatter.toJsString(-42d));
    }

    @Test
    public void test_negative_zero_still_renders_as_zero() {
        assertEquals("0", NumberFormatter.toJsString(-0.0d));
    }

    @Test
    public void test_millisecond_timestamps_render_as_integers() {
        assertEquals("1757000000000", NumberFormatter.toJsString(1757000000000d));
        assertEquals("-1757000000000", NumberFormatter.toJsString(-1757000000000d));
    }

    @Test
    public void test_values_at_and_around_two_pow_53() {
        assertEquals("9007199254740990", NumberFormatter.toJsString(9007199254740990d));
        assertEquals("9007199254740991", NumberFormatter.toJsString(9007199254740991d));
        assertEquals("9007199254740992", NumberFormatter.toJsString(9007199254740992d));
        assertEquals("-9007199254740992", NumberFormatter.toJsString(-9007199254740992d));
    }

    @Test
    public void test_large_powers_of_ten_keep_their_spec_form() {
        assertEquals("100000000000000000000", NumberFormatter.toJsString(1e20));
        assertEquals("1e+21", NumberFormatter.toJsString(1e21));
        assertEquals("1e+22", NumberFormatter.toJsString(1e22));
        assertEquals("-1e+21", NumberFormatter.toJsString(-1e21));
    }

    @Test
    public void test_non_integral_values_are_unaffected() {
        assertEquals("0.1", NumberFormatter.toJsString(0.1));
        assertEquals("1.5", NumberFormatter.toJsString(1.5));
        assertEquals("-0.5", NumberFormatter.toJsString(-0.5));
        assertEquals("1e-7", NumberFormatter.toJsString(0.0000001));
        assertEquals("0.000001", NumberFormatter.toJsString(0.000001));
    }

    @Test
    public void test_subnormals_and_extremes_are_unaffected() {
        assertEquals("5e-324", NumberFormatter.toJsString(Double.MIN_VALUE));
        assertEquals("NaN", NumberFormatter.toJsString(Double.NaN));
        assertEquals("Infinity", NumberFormatter.toJsString(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", NumberFormatter.toJsString(Double.NEGATIVE_INFINITY));
    }

    @Test
    public void test_every_power_of_two_below_the_exact_integer_bound_is_the_plain_integer() {
        for (var exponent = 0; exponent < 53; exponent++) {
            final var value = Math.pow(2, exponent);
            final var expected = BigDecimal.valueOf(value).toBigInteger().toString();
            assertEquals(expected, NumberFormatter.toJsString(value), "2^" + exponent);
        }
    }

    @Test
    public void test_dense_integer_range_is_the_plain_integer() {
        for (var i = -5000; i <= 5000; i++) {
            assertEquals(Integer.toString(i), NumberFormatter.toJsString(i));
        }
    }
}
