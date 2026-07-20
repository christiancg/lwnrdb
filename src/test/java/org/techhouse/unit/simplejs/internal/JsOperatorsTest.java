package org.techhouse.unit.simplejs.internal;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

import static org.junit.jupiter.api.Assertions.*;

public class JsOperatorsTest {
    private static double num(JsValue value) {
        return ((JsNumber) value).getValue();
    }

    private static boolean bool(JsValue value) {
        return ((JsBoolean) value).getValue();
    }

    // Numeric arithmetic operators produce the expected results
    @Test
    public void test_arithmetic() {
        assertEquals(5, num(JsOperators.binary("+", new JsNumber(2), new JsNumber(3))));
        assertEquals(-1, num(JsOperators.binary("-", new JsNumber(2), new JsNumber(3))));
        assertEquals(6, num(JsOperators.binary("*", new JsNumber(2), new JsNumber(3))));
        assertEquals(2, num(JsOperators.binary("/", new JsNumber(6), new JsNumber(3))));
        assertEquals(2, num(JsOperators.binary("%", new JsNumber(8), new JsNumber(3))));
        assertEquals(8, num(JsOperators.binary("**", new JsNumber(2), new JsNumber(3))));
        assertEquals(Double.POSITIVE_INFINITY, num(JsOperators.binary("/", new JsNumber(1), new JsNumber(0))));
        assertTrue(Double.isNaN(num(JsOperators.binary("/", new JsNumber(0), new JsNumber(0)))));
    }

    // The + operator concatenates when either operand is a string
    @Test
    public void test_string_concat() {
        assertEquals("ab", ((JsString) JsOperators.binary("+", new JsString("a"), new JsString("b"))).getValue());
        assertEquals("a1", ((JsString) JsOperators.binary("+", new JsString("a"), new JsNumber(1))).getValue());
    }

    // Bitwise operators coerce to 32-bit integers, with an unsigned right shift
    @Test
    public void test_bitwise() {
        assertEquals(1, num(JsOperators.binary("&", new JsNumber(5), new JsNumber(3))));
        assertEquals(7, num(JsOperators.binary("|", new JsNumber(5), new JsNumber(2))));
        assertEquals(4, num(JsOperators.binary("^", new JsNumber(5), new JsNumber(1))));
        assertEquals(16, num(JsOperators.binary("<<", new JsNumber(1), new JsNumber(4))));
        assertEquals(64, num(JsOperators.binary(">>", new JsNumber(256), new JsNumber(2))));
        assertEquals(4294967295.0, num(JsOperators.binary(">>>", new JsNumber(-1), new JsNumber(0))));
    }

    // Relational operators compare numbers and strings, NaN is unordered
    @Test
    public void test_relational() {
        assertTrue(bool(JsOperators.binary("<", new JsNumber(1), new JsNumber(2))));
        assertTrue(bool(JsOperators.binary(">=", new JsNumber(2), new JsNumber(2))));
        assertTrue(bool(JsOperators.binary("<", new JsString("a"), new JsString("b"))));
        assertFalse(bool(JsOperators.binary(">", new JsNumber(Double.NaN), new JsNumber(1))));
        assertTrue(bool(JsOperators.binary(">", new JsBigInt(BigInteger.TWO), new JsNumber(1))));
    }

    // Loose equality applies type coercion, strict equality does not
    @Test
    public void test_equality() {
        assertTrue(bool(JsOperators.binary("==", new JsNumber(1), new JsString("1"))));
        assertTrue(bool(JsOperators.binary("==", JsNull.getInstance(), JsUndefined.getInstance())));
        assertTrue(bool(JsOperators.binary("==", JsBoolean.FALSE, new JsNumber(0))));
        assertTrue(bool(JsOperators.binary("==", new JsBigInt(BigInteger.ONE), new JsNumber(1))));
        assertTrue(bool(JsOperators.binary("==", new JsBigInt(BigInteger.ONE), new JsString("1"))));
        assertFalse(bool(JsOperators.binary("===", new JsNumber(1), new JsString("1"))));
        assertTrue(bool(JsOperators.binary("===", new JsNumber(1), new JsNumber(1))));
        assertTrue(bool(JsOperators.binary("!==", new JsNumber(1), new JsNumber(2))));
        assertFalse(bool(JsOperators.binary("==", new JsObject(), new JsObject())));
        assertFalse(bool(JsOperators.binary("==", JsNull.getInstance(), new JsNumber(0))));
    }

    // BigInt arithmetic stays exact, mixing with numbers throws
    @Test
    public void test_bigint() {
        assertEquals(BigInteger.valueOf(3),
                ((JsBigInt) JsOperators.binary("+", new JsBigInt(BigInteger.ONE), new JsBigInt(BigInteger.TWO)))
                        .getValue());
        assertThrows(TypeErrorException.class,
                () -> JsOperators.binary("+", new JsBigInt(BigInteger.ONE), new JsNumber(1)));
        assertThrows(RangeErrorException.class,
                () -> JsOperators.binary("/", new JsBigInt(BigInteger.ONE), new JsBigInt(BigInteger.ZERO)));
    }

    // Unary operators negate, coerce, and report types
    @Test
    public void test_unary() {
        assertTrue(bool(JsOperators.unary("!", new JsNumber(0))));
        assertEquals(-5, num(JsOperators.unary("-", new JsNumber(5))));
        assertEquals(3, num(JsOperators.unary("+", new JsString("3"))));
        assertEquals(-1, num(JsOperators.unary("~", new JsNumber(0))));
        assertEquals("number", ((JsString) JsOperators.unary("typeof", new JsNumber(1))).getValue());
        assertInstanceOf(JsUndefined.class, JsOperators.unary("void", new JsNumber(1)));
    }

    // delta increments and decrements numbers and BigInts
    @Test
    public void test_delta() {
        assertEquals(6, num(JsOperators.delta(new JsNumber(5), true)));
        assertEquals(4, num(JsOperators.delta(new JsNumber(5), false)));
        assertEquals(BigInteger.valueOf(2),
                ((JsBigInt) JsOperators.delta(new JsBigInt(BigInteger.ONE), true)).getValue());
        assertEquals(BigInteger.ZERO, ((JsBigInt) JsOperators.delta(new JsBigInt(BigInteger.ONE), false)).getValue());
    }

    private static BigInteger big(JsValue value) {
        return ((JsBigInt) value).getValue();
    }

    private static JsBigInt bi(long value) {
        return new JsBigInt(BigInteger.valueOf(value));
    }

    // BigInt arithmetic operators other than + stay exact
    @Test
    public void test_bigint_arithmetic() {
        assertEquals(BigInteger.valueOf(7), big(JsOperators.binary("-", bi(10), bi(3))));
        assertEquals(BigInteger.valueOf(12), big(JsOperators.binary("*", bi(4), bi(3))));
        assertEquals(BigInteger.ONE, big(JsOperators.binary("%", bi(10), bi(3))));
        assertEquals(BigInteger.valueOf(1024), big(JsOperators.binary("**", bi(2), bi(10))));
        assertThrows(RangeErrorException.class, () -> JsOperators.binary("**", bi(2), bi(-1)));
        assertThrows(RangeErrorException.class, () -> JsOperators.binary("%", bi(1), bi(0)));
        assertThrows(TypeErrorException.class, () -> JsOperators.binary("-", bi(1), new JsNumber(1)));
    }

    // BigInt bitwise operators map onto BigInteger, unsigned shift is unsupported
    @Test
    public void test_bigint_bitwise() {
        assertEquals(BigInteger.valueOf(2), big(JsOperators.binary("&", bi(6), bi(3))));
        assertEquals(BigInteger.valueOf(7), big(JsOperators.binary("|", bi(6), bi(1))));
        assertEquals(BigInteger.valueOf(4), big(JsOperators.binary("^", bi(5), bi(1))));
        assertEquals(BigInteger.valueOf(16), big(JsOperators.binary("<<", bi(1), bi(4))));
        assertEquals(BigInteger.valueOf(64), big(JsOperators.binary(">>", bi(256), bi(2))));
        assertThrows(TypeErrorException.class, () -> JsOperators.binary(">>>", bi(1), bi(1)));
    }

    // BigInt comparisons work directly and against numbers, NaN stays unordered
    @Test
    public void test_bigint_relational_and_equality() {
        assertTrue(bool(JsOperators.binary("<", bi(1), bi(2))));
        assertTrue(bool(JsOperators.binary("<=", bi(2), new JsNumber(3))));
        assertFalse(bool(JsOperators.binary("<", bi(1), new JsNumber(Double.NaN))));
        assertTrue(bool(JsOperators.binary("==", new JsNumber(1), bi(1))));
        assertTrue(bool(JsOperators.binary("==", new JsString("1"), bi(1))));
        assertFalse(bool(JsOperators.binary("==", bi(1), new JsNumber(1.5))));
        assertFalse(bool(JsOperators.binary("==", bi(1), new JsString("x"))));
    }

    // Unary negation and bitwise-not have dedicated BigInt paths
    @Test
    public void test_unary_bigint() {
        assertEquals(BigInteger.valueOf(-5), big(JsOperators.unary("-", bi(5))));
        assertEquals(BigInteger.valueOf(-6), big(JsOperators.unary("~", bi(5))));
    }

    // Unknown operators are rejected by both binary and unary dispatch
    @Test
    public void test_unknown_operators_throw() {
        assertThrows(TypeErrorException.class, () -> JsOperators.binary("bogus", new JsNumber(1), new JsNumber(2)));
        assertThrows(TypeErrorException.class, () -> JsOperators.unary("bogus", new JsNumber(1)));
    }
}
