package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

// The global functions and the Number/BigInt/Date/Error/Math/String/JSON builtins all reach the
// script through the ops-aware JsCoercion overloads, so a poisoned valueOf/toString must be
// observed and a Symbol argument must throw.
public class CoercionThreadingProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static String caught(String expression) {
        return str(
                "(function(){ try { " + expression + "; return 'no throw'; }" + " catch (e) { return e.name; } })()");
    }

    // isNaN/isFinite observe a poisoned valueOf instead of silently stringifying the object
    @Test
    public void test_global_predicates_observe_to_primitive() {
        assertEquals("RangeError", caught("isNaN({valueOf: function(){ throw new RangeError(); }})"));
        assertEquals("RangeError", caught("isFinite({valueOf: function(){ throw new RangeError(); }})"));
        assertEquals("TypeError", caught("isNaN(Symbol())"));
        assertTrue(bool("isFinite({valueOf: function(){ return 1; }})"));
    }

    // Number() is ToNumeric over ToPrimitive, so valueOf wins over toString
    @Test
    public void test_number_argument_coercion() {
        assertEquals(1, num("Number({valueOf: function(){ return '1'; }, toString: function(){ return 0; }})"));
        assertEquals("EvalError", caught("Number({valueOf: function(){ throw new EvalError(); }})"));
        assertEquals("TypeError", caught("Number(Symbol())"));
    }

    // parseInt/parseFloat strip the full StrWhiteSpace set, not Java's
    @Test
    public void test_parse_whitespace_and_infinity() {
        assertEquals(1.1, num("parseFloat('\\u00A01.1')"));
        assertEquals(1, num("parseInt('\\u00A01')"));
        assertEquals(Double.POSITIVE_INFINITY, num("parseFloat('Infinity1')"));
        assertEquals(1, num("parseFloat('1ex')"));
    }

    // The parseInt radix is ToInt32, so a value past 2^32 wraps rather than saturating
    @Test
    public void test_parse_int_radix_is_to_int32() {
        assertEquals(3, num("parseInt('11', 4294967298)"));
        assertEquals(11, num("parseInt('11', Number.POSITIVE_INFINITY)"));
    }

    // Only ASCII digits count: an Arabic-Indic digit terminates the literal
    @Test
    public void test_parse_rejects_non_ascii_digits() {
        assertTrue(Double.isNaN(num("parseInt('\\u0660')")));
        assertTrue(Double.isNaN(num("parseFloat('\\u0660')")));
    }

    // The Number namespace constants are non-writable and non-configurable
    @Test
    public void test_number_constants_are_frozen() {
        assertTrue(bool("var d = Object.getOwnPropertyDescriptor(Number, 'MAX_VALUE');"
                + "d.writable === false && d.configurable === false && d.enumerable === false"));
    }

    // toPrecision/toExponential render significant digits from the exact binary expansion
    @Test
    public void test_number_formatting() {
        assertEquals("7.00", str("(7).toPrecision(3)"));
        assertEquals("0.000000", str("(0).toPrecision(7)"));
        assertEquals("1e+2", str("(100).toPrecision(1)"));
        assertEquals("0e+0", str("(-0).toExponential(0)"));
        assertEquals("1.23456e+2", str("(123.456).toExponential()"));
    }

    // The precision is coerced before the range is checked, and NaN/Infinity answer before both
    @Test
    public void test_number_formatting_order() {
        assertEquals("NaN", str("(NaN).toPrecision(Infinity)"));
        assertEquals("RangeError", caught("(1).toPrecision(Infinity)"));
        assertEquals("RangeError", caught("(1).toString(1)"));
        assertEquals("EvalError", caught("(1).toString({valueOf: function(){ throw new EvalError(); }})"));
    }

    // BigInt reads the radix prefixes and reports a non-integral Number as a RangeError
    @Test
    public void test_bigint_conversion() {
        assertTrue(bool("BigInt('0b1111') === 15n"));
        assertTrue(bool("BigInt('0xa') === 10n"));
        assertEquals("RangeError", caught("BigInt({valueOf: function(){ return NaN; }})"));
    }

    // decodeURI rejects a malformed sequence: a non-ASCII hex digit, an overlong form and a
    // surrogate code point are all URIErrors
    @Test
    public void test_decode_uri_rejects_malformed_sequences() {
        assertEquals("URIError", caught("decodeURI('%\\u06601')"));
        assertEquals("URIError", caught("decodeURI('%C0%80')"));
        assertEquals("URIError", caught("decodeURIComponent('%ED%A0%80')"));
        assertEquals("URIError", caught("decodeURI('%C2')"));
        assertEquals("A", str("decodeURI('%41')"));
    }

    // The URI functions coerce their argument through ToPrimitive
    @Test
    public void test_uri_functions_coerce_argument() {
        assertEquals("ab", str("encodeURI({toString: function(){ return 'ab'; }})"));
        assertEquals("EvalError", caught("decodeURI({toString: function(){ throw new EvalError(); }})"));
    }

    // Math coerces every argument before comparing them, and rounds a negative half toward -0
    @Test
    public void test_math_coercion_and_rounding() {
        assertEquals(1, num("var n = 0; Math.max(NaN, {valueOf: function(){ n = 1; return 0; }}); n"));
        assertTrue(bool("1 / Math.round(-0.5) === -Infinity"));
        assertTrue(bool("1 / Math.round(-0.25) === -Infinity"));
        assertTrue(bool("1 / Math.round(0.5 - Number.EPSILON / 4) === Infinity"));
        assertEquals(3, num("Math.round(2.5)"));
        assertEquals(-2, num("Math.round(-2.5)"));
        assertEquals("EvalError", caught("Math.hypot(1, {valueOf: function(){ throw new EvalError(); }})"));
    }

    // atanh keeps the sign of a zero and rejects a magnitude above one
    @Test
    public void test_math_atanh() {
        assertTrue(bool("1 / Math.atanh(-0) === -Infinity"));
        assertTrue(bool("Math.atanh(-1) === -Infinity"));
        assertTrue(bool("Number.isNaN(Math.atanh(2))"));
    }

    // String.raw is generic over any array-like template
    @Test
    public void test_string_raw_is_generic() {
        assertEquals("enullundefined123",
                str("String.raw({raw: {length: 5, 0: 'e', 1: '', 2: null," + " 3: undefined, 4: 123, 5: 'ignored'}})"));
        assertEquals("", str("String.raw({raw: []})"));
        assertEquals("TypeError", caught("String.raw()"));
        assertEquals("TypeError", caught("String.raw({})"));
    }

    // fromCharCode applies ToUint16 rather than saturating an out-of-int argument
    @Test
    public void test_from_char_code_wraps() {
        assertEquals(0, num("String.fromCharCode(Infinity).charCodeAt(0)"));
        assertEquals(65534, num("String.fromCharCode(4294967294).charCodeAt(0)"));
    }

    // repeat rejects an infinite count instead of truncating it to Integer.MAX_VALUE
    @Test
    public void test_repeat_rejects_infinity() {
        assertEquals("RangeError", caught("'a'.repeat(Infinity)"));
        assertEquals("RangeError", caught("'a'.repeat(-1)"));
        assertEquals("aa", str("'a'.repeat(2)"));
    }

    // split reads ToUint32(limit) before ToString(separator), and a zero limit wins over an
    // undefined separator
    @Test
    public void test_split_limit_ordering() {
        assertEquals(0, num("'abc'.split(undefined, 0).length"));
        assertEquals(1, num("'abc'.split(undefined).length"));
        assertEquals("intoint",
                str("var e = ''; try { 'x'.split({toString: function(){ e += 'intostr';"
                        + " throw new Error(); }}, {valueOf: function(){ e += 'intoint'; throw new Error(); }}); }"
                        + " catch (ignored) {} e"));
    }
}
