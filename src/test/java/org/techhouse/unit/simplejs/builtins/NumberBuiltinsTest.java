package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class NumberBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Number coerces its argument
    @Test
    public void test_number_coercion() {
        assertEquals(5, num("Number('5')"));
        assertEquals(0, num("Number()"));
    }

    // Number.isNaN and Number.isInteger do not coerce
    @Test
    public void test_isnan_isinteger() {
        assertTrue(bool("Number.isNaN(0 / 0)"));
        assertFalse(bool("Number.isNaN('x')"));
        assertTrue(bool("Number.isInteger(4)"));
        assertFalse(bool("Number.isInteger(4.5)"));
        assertFalse(bool("Number.isInteger('4')"));
        assertTrue(bool("Number.isFinite(1)"));
    }

    // global parseInt honors base prefixes and radix
    @Test
    public void test_parseint() {
        assertEquals(42, num("parseInt('42')"));
        assertEquals(255, num("parseInt('0xff')"));
        assertEquals(10, num("parseInt('1010', 2)"));
        assertEquals(-7, num("parseInt('-7')"));
        assertTrue(Double.isNaN(num("parseInt('xyz')")));
    }

    // global parseFloat reads a leading float
    @Test
    public void test_parsefloat() {
        assertEquals(3.14, num("parseFloat('3.14abc')"));
        assertEquals(1.5e3, num("parseFloat('1.5e3')"));
        assertTrue(Double.isNaN(num("parseFloat('abc')")));
    }

    // global isNaN and isFinite coerce their argument
    @Test
    public void test_global_isnan_isfinite() {
        assertTrue(bool("isNaN('x')"));
        assertFalse(bool("isNaN('5')"));
        assertTrue(bool("isFinite(3)"));
        assertFalse(bool("isFinite(1 / 0)"));
    }

    // toFixed rounds half up to the requested number of digits
    @Test
    public void test_tofixed() {
        assertEquals("3.14", str("(3.14159).toFixed(2)"));
        assertEquals("3", str("(3).toFixed(0)"));
        assertEquals("0.13", str("(0.125).toFixed(2)"));
        assertEquals("NaN", str("(0 / 0).toFixed(2)"));
    }

    // toPrecision renders significant digits; no arg falls back to toString
    @Test
    public void test_toprecision() {
        assertEquals("123", str("(123.456).toPrecision(3)"));
        assertEquals("123.46", str("(123.456).toPrecision(5)"));
        assertEquals("42", str("(42).toPrecision()"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("(1).toPrecision(0)"));
    }

    // toString honors a radix
    @Test
    public void test_tostring_radix() {
        assertEquals("ff", str("(255).toString(16)"));
        assertEquals("1010", str("(10).toString(2)"));
        assertEquals("255", str("(255).toString()"));
        assertEquals("-10", str("(-16).toString(16)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("(1).toString(40)"));
    }

    // toExponential renders exponential notation
    @Test
    public void test_toexponential() {
        assertEquals("1.2345e+4", str("(12345).toExponential()"));
        assertEquals("1.23e+4", str("(12345).toExponential(2)"));
    }

    // valueOf returns the primitive number
    @Test
    public void test_valueof() {
        assertEquals(5, num("(5).valueOf()"));
    }

    // toString with a radix handles fractional, negative, NaN and infinite values
    @Test
    public void test_tostring_radix_edges() {
        assertEquals("11.1", str("(3.5).toString(2)"));
        assertEquals("NaN", str("(0 / 0).toString(2)"));
        assertEquals("Infinity", str("(1 / 0).toString(2)"));
        assertEquals("-Infinity", str("(-1 / 0).toString(2)"));
    }

    // toFixed/toPrecision/toExponential render NaN and infinities
    @Test
    public void test_nonfinite_formatting() {
        assertEquals("NaN", str("(0 / 0).toFixed(2)"));
        assertEquals("Infinity", str("(1 / 0).toFixed(2)"));
        assertEquals("NaN", str("(0 / 0).toPrecision(2)"));
        assertEquals("Infinity", str("(1 / 0).toPrecision(2)"));
        assertEquals("NaN", str("(0 / 0).toExponential(2)"));
        assertEquals("-Infinity", str("(-1 / 0).toExponential()"));
    }

    // Number carries the documented constants
    @Test
    public void test_constants() {
        assertEquals(9007199254740991d, num("Number.MAX_SAFE_INTEGER"));
        assertEquals(-9007199254740991d, num("Number.MIN_SAFE_INTEGER"));
        assertTrue(bool("Number.POSITIVE_INFINITY === 1 / 0"));
        assertTrue(bool("Number.isNaN(Number.NaN)"));
        assertTrue(num("Number.EPSILON") > 0);
    }
}
