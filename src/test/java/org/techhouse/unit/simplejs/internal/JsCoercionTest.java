package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class JsCoercionTest {
    // toBoolean follows JS truthiness for every value kind
    @Test
    public void test_to_boolean() {
        assertTrue(JsCoercion.toBoolean(JsBoolean.TRUE));
        assertFalse(JsCoercion.toBoolean(JsBoolean.FALSE));
        assertFalse(JsCoercion.toBoolean(new JsNumber(0)));
        assertFalse(JsCoercion.toBoolean(new JsNumber(Double.NaN)));
        assertTrue(JsCoercion.toBoolean(new JsNumber(5)));
        assertFalse(JsCoercion.toBoolean(new JsString("")));
        assertTrue(JsCoercion.toBoolean(new JsString("a")));
        assertFalse(JsCoercion.toBoolean(new JsBigInt(BigInteger.ZERO)));
        assertTrue(JsCoercion.toBoolean(new JsBigInt(BigInteger.TEN)));
        assertFalse(JsCoercion.toBoolean(JsNull.getInstance()));
        assertFalse(JsCoercion.toBoolean(JsUndefined.getInstance()));
        assertTrue(JsCoercion.toBoolean(new JsObject()));
        assertTrue(JsCoercion.toBoolean(new JsArray()));
    }

    // toNumber converts primitives, parsing decimal, radix, and Infinity strings
    @Test
    public void test_to_number() {
        assertEquals(5, JsCoercion.toNumber(new JsNumber(5)));
        assertEquals(1, JsCoercion.toNumber(JsBoolean.TRUE));
        assertEquals(0, JsCoercion.toNumber(JsNull.getInstance()));
        assertTrue(Double.isNaN(JsCoercion.toNumber(JsUndefined.getInstance())));
        assertEquals(0, JsCoercion.toNumber(new JsString("")));
        assertEquals(12, JsCoercion.toNumber(new JsString("  12  ")));
        assertEquals(1000, JsCoercion.toNumber(new JsString("1e3")));
        assertEquals(16, JsCoercion.toNumber(new JsString("0x10")));
        assertEquals(8, JsCoercion.toNumber(new JsString("0o10")));
        assertEquals(5, JsCoercion.toNumber(new JsString("0b101")));
        assertEquals(Double.POSITIVE_INFINITY, JsCoercion.toNumber(new JsString("Infinity")));
        assertEquals(Double.NEGATIVE_INFINITY, JsCoercion.toNumber(new JsString("-Infinity")));
        assertTrue(Double.isNaN(JsCoercion.toNumber(new JsString("abc"))));
        assertTrue(Double.isNaN(JsCoercion.toNumber(new JsString("5d"))));
    }

    // toNumber of a BigInt throws, mirroring +bigint in JS
    @Test
    public void test_to_number_bigint_throws() {
        assertThrows(TypeErrorException.class, () -> JsCoercion.toNumber(new JsBigInt(BigInteger.ONE)));
    }

    // toStr renders each value kind, integers without a decimal point
    @Test
    public void test_to_str() {
        assertEquals("hi", JsCoercion.toStr(new JsString("hi")));
        assertEquals("10", JsCoercion.toStr(new JsNumber(10)));
        assertEquals("1.5", JsCoercion.toStr(new JsNumber(1.5)));
        assertEquals("NaN", JsCoercion.toStr(new JsNumber(Double.NaN)));
        assertEquals("Infinity", JsCoercion.toStr(new JsNumber(Double.POSITIVE_INFINITY)));
        assertEquals("-Infinity", JsCoercion.toStr(new JsNumber(Double.NEGATIVE_INFINITY)));
        assertEquals("0", JsCoercion.toStr(new JsNumber(0)));
        assertEquals("true", JsCoercion.toStr(JsBoolean.TRUE));
        assertEquals("42", JsCoercion.toStr(new JsBigInt(BigInteger.valueOf(42))));
        assertEquals("null", JsCoercion.toStr(JsNull.getInstance()));
        assertEquals("undefined", JsCoercion.toStr(JsUndefined.getInstance()));
        assertEquals("[object Object]", JsCoercion.toStr(new JsObject()));
    }

    // Array toStr joins with commas, leaving holes for null and undefined elements
    @Test
    public void test_to_str_array() {
        final var array = new JsArray(List.of(new JsNumber(1), JsNull.getInstance(), new JsNumber(3)));
        assertEquals("1,,3", JsCoercion.toStr(array));
    }

    // typeof reports the value kind, with null classified as object
    @Test
    public void test_type_of() {
        assertEquals("number", JsCoercion.typeOf(new JsNumber(1)));
        assertEquals("string", JsCoercion.typeOf(new JsString("a")));
        assertEquals("boolean", JsCoercion.typeOf(JsBoolean.TRUE));
        assertEquals("bigint", JsCoercion.typeOf(new JsBigInt(BigInteger.ONE)));
        assertEquals("undefined", JsCoercion.typeOf(JsUndefined.getInstance()));
        assertEquals("object", JsCoercion.typeOf(JsNull.getInstance()));
        assertEquals("object", JsCoercion.typeOf(new JsObject()));
        assertEquals("object", JsCoercion.typeOf(new JsArray()));
    }

    // toPrimitive stringifies objects and arrays, leaving primitives untouched
    @Test
    public void test_to_primitive() {
        assertEquals("[object Object]", ((JsString) JsCoercion.toPrimitive(new JsObject())).getValue());
        assertEquals(5, ((JsNumber) JsCoercion.toPrimitive(new JsNumber(5))).getValue());
    }

    // Function values report the function typeof and a non-throwing string form
    @Test
    public void test_function_coercion() {
        final var function = new JsFunction("f", List.of(), null, false, false, false, false, Environment.global());
        final var nativeFunction = new JsNativeFunction("n", (_, _) -> JsUndefined.getInstance());
        assertEquals("function", JsCoercion.typeOf(function));
        assertEquals("function", JsCoercion.typeOf(nativeFunction));
        assertEquals("function f() { }", JsCoercion.toStr(function));
        assertEquals("function n() { }", JsCoercion.toStr(nativeFunction));
        assertTrue(JsCoercion.toBoolean(function));
    }
}
