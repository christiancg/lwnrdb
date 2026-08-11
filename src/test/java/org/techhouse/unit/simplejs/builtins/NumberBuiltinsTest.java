package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
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

    // BigInt coerces integers, booleans and integer strings
    @Test
    public void test_bigint_coercion() {
        assertTrue(bool("BigInt(10) === 10n"));
        assertTrue(bool("BigInt(-7) === -7n"));
        assertTrue(bool("BigInt(0) === 0n"));
        assertTrue(bool("BigInt('42') === 42n"));
        assertTrue(bool("BigInt('  -3  ') === -3n"));
        assertTrue(bool("BigInt(false) === 0n"));
        assertTrue(bool("BigInt(true) === 1n"));
        assertTrue(bool("typeof BigInt(5) === 'bigint'"));
    }

    // BigInt rejects a non-integer number with a RangeError
    @Test
    public void test_bigint_non_integer_throws() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("BigInt(1.5)"));
    }

    // BigInt rejects an unparseable string with a SyntaxError
    @Test
    public void test_bigint_bad_string_throws() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("BigInt('x')"));
    }

    // BigInt rejects an object argument with a TypeError
    @Test
    public void test_bigint_object_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("BigInt({})"));
    }

    // toLocaleString formats with the default locale; digits survive grouping, specials stay readable
    @Test
    public void test_to_locale_string() {
        assertEquals("1234", str("(1234).toLocaleString().replace(/[^0-9]/g, '')"));
        assertEquals("NaN", str("(Number.NaN).toLocaleString()"));
        assertEquals("∞", str("(Number.POSITIVE_INFINITY).toLocaleString()"));
        assertEquals("-∞", str("(Number.NEGATIVE_INFINITY).toLocaleString()"));
    }

    // toFixed rounds the binary double, not its shortest decimal form
    @Test
    public void test_to_fixed_binary_rounding() {
        assertEquals("1.00", str("(1.005).toFixed(2)"));
        assertEquals("3", str("(2.5).toFixed(0)"));
        assertEquals("-3", str("(-2.5).toFixed(0)"));
        assertEquals("1.4", str("(1.45).toFixed(1)"));
        assertEquals("1.50", str("(1.5).toFixed(2)"));
    }

    // toFixed rejects a digit count outside 0..100
    @Test
    public void test_to_fixed_digit_range() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("(1).toFixed(-1)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("(1).toFixed(101)"));
        assertEquals("1", str("(1).toFixed(0)"));
        assertEquals("NaN", str("(NaN).toFixed(2)"));
        assertEquals("Infinity", str("(Infinity).toFixed(2)"));
        assertEquals("-Infinity", str("(-Infinity).toFixed(2)"));
    }

    // isSafeInteger at the boundaries
    @Test
    public void test_is_safe_integer() {
        assertTrue(bool("Number.isSafeInteger(9007199254740991)"));
        assertTrue(bool("Number.isSafeInteger(-9007199254740991)"));
        assertFalse(bool("Number.isSafeInteger(9007199254740992)"));
        assertFalse(bool("Number.isSafeInteger(1.5)"));
        assertFalse(bool("Number.isSafeInteger('1')"));
        assertFalse(bool("Number.isSafeInteger(Infinity)"));
    }

    // BigInt instance methods and the asIntN/asUintN statics
    @Test
    public void test_bigint_methods() {
        assertEquals("ff", str("(255n).toString(16)"));
        assertEquals("255", str("(255n).toString()"));
        assertTrue(bool("(2n).valueOf() === 2n"));
        assertTrue(bool("typeof (2n).toLocaleString() === 'string'"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("(2n).toString(1)"));
        assertTrue(bool("BigInt.asIntN(8, 255n) === -1n"));
        assertTrue(bool("BigInt.asUintN(8, -1n) === 255n"));
        assertTrue(bool("BigInt.asIntN(0, 5n) === 0n"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("BigInt.asIntN(-1, 1n)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("BigInt.asIntN(8, 1)"));
    }

    // The global NaN, Infinity and undefined bindings
    @Test
    public void test_global_number_bindings() {
        assertTrue(bool("typeof NaN === 'number'"));
        assertTrue(bool("Number.isNaN(NaN)"));
        assertTrue(bool("Infinity > 0 && !isFinite(Infinity)"));
        assertTrue(bool("typeof undefined === 'undefined'"));
    }
}
