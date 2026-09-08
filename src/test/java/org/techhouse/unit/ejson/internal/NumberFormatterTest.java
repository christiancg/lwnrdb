package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.NumberFormatter;

public class NumberFormatterTest {
    // Integral values below the exponential threshold render as plain digits
    @Test
    public void test_to_js_string_integers() {
        assertEquals("0", NumberFormatter.toJsString(0d));
        assertEquals("0", NumberFormatter.toJsString(-0d));
        assertEquals("1", NumberFormatter.toJsString(1d));
        assertEquals("-1", NumberFormatter.toJsString(-1d));
        assertEquals("100", NumberFormatter.toJsString(100d));
        assertEquals("1000000000000000", NumberFormatter.toJsString(1e15));
    }

    // Values past the long range keep their decimal expansion instead of clamping
    @Test
    public void test_to_js_string_past_long_range() {
        assertEquals("10000000000000000000", NumberFormatter.toJsString(1e19));
        assertEquals("9223372036854776000", NumberFormatter.toJsString(9.223372036854776E18));
        assertEquals("123000000000000000000", NumberFormatter.toJsString(1.23e20));
        assertEquals("100000000000000000000", NumberFormatter.toJsString(1e20));
    }

    // At and above 1e21 the exponential form is used
    @Test
    public void test_to_js_string_exponential_upper() {
        assertEquals("1e+21", NumberFormatter.toJsString(1e21));
        assertEquals("1.5e+21", NumberFormatter.toJsString(1.5e21));
        assertEquals("1e+100", NumberFormatter.toJsString(1e100));
        assertEquals("-1e+21", NumberFormatter.toJsString(-1e21));
        assertEquals("1.7976931348623157e+308", NumberFormatter.toJsString(Double.MAX_VALUE));
    }

    // Below 1e-6 the exponential form is used, above it the plain fraction
    @Test
    public void test_to_js_string_exponential_lower() {
        assertEquals("1e-7", NumberFormatter.toJsString(1e-7));
        assertEquals("0.000001", NumberFormatter.toJsString(1e-6));
        assertEquals("5e-324", NumberFormatter.toJsString(Double.MIN_VALUE));
        assertEquals("2.2250738585072014e-308", NumberFormatter.toJsString(Double.MIN_NORMAL));
        assertEquals("1.5e-323", NumberFormatter.toJsString(1.5e-323));
    }

    // Fractions keep the shortest round-tripping digit string
    @Test
    public void test_to_js_string_fractions() {
        assertEquals("0.5", NumberFormatter.toJsString(0.5));
        assertEquals("0.3333333333333333", NumberFormatter.toJsString(1d / 3));
        assertEquals("0.30000000000000004", NumberFormatter.toJsString(0.1 + 0.2));
        assertEquals("123.456", NumberFormatter.toJsString(123.456));
        assertEquals("-0.25", NumberFormatter.toJsString(-0.25));
    }

    // NaN and the infinities use their JS spellings
    @Test
    public void test_to_js_string_specials() {
        assertEquals("NaN", NumberFormatter.toJsString(Double.NaN));
        assertEquals("Infinity", NumberFormatter.toJsString(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", NumberFormatter.toJsString(Double.NEGATIVE_INFINITY));
    }

    // ToInt32 wraps modulo 2^32 instead of saturating at Long.MAX_VALUE
    @Test
    public void test_to_int32_wraps() {
        assertEquals(-559939584, NumberFormatter.toInt32(1e21));
        assertEquals(0, NumberFormatter.toInt32(9.223372036854776E18));
        assertEquals(1410065408, NumberFormatter.toInt32(1e10));
        assertEquals(-1, NumberFormatter.toInt32(-1));
        assertEquals(0, NumberFormatter.toInt32(4294967296d));
        assertEquals(1, NumberFormatter.toInt32(4294967297d));
        assertEquals(-2147483648, NumberFormatter.toInt32(2147483648d));
        assertEquals(5, NumberFormatter.toInt32(5.9));
        assertEquals(-5, NumberFormatter.toInt32(-5.9));
    }

    // ToInt32 maps the non-finite values to zero
    @Test
    public void test_to_int32_non_finite() {
        assertEquals(0, NumberFormatter.toInt32(Double.NaN));
        assertEquals(0, NumberFormatter.toInt32(Double.POSITIVE_INFINITY));
        assertEquals(0, NumberFormatter.toInt32(Double.NEGATIVE_INFINITY));
    }

    // ToUint32 reinterprets the wrapped value as unsigned
    @Test
    public void test_to_uint32_wraps() {
        assertEquals(4294967295L, NumberFormatter.toUint32(-1));
        assertEquals(0L, NumberFormatter.toUint32(4294967296d));
        assertEquals(2147483648L, NumberFormatter.toUint32(-2147483648d));
        assertEquals(0L, NumberFormatter.toUint32(Double.NaN));
    }
}
