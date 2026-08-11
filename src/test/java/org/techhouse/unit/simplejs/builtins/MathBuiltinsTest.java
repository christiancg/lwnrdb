package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;

public class MathBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((org.techhouse.simplejs.values.JsBoolean) Interpreter.run(source)).getValue();
    }

    // Math exposes constants
    @Test
    public void test_constants() {
        assertEquals(Math.PI, num("Math.PI"));
        assertEquals(Math.E, num("Math.E"));
    }

    // rounding and roots
    @Test
    public void test_rounding_and_roots() {
        assertEquals(5, num("Math.abs(-5)"));
        assertEquals(2, num("Math.floor(2.9)"));
        assertEquals(3, num("Math.ceil(2.1)"));
        assertEquals(3, num("Math.round(2.5)"));
        assertEquals(2, num("Math.trunc(2.9)"));
        assertEquals(3, num("Math.sqrt(9)"));
        assertEquals(2, num("Math.cbrt(8)"));
        assertEquals(1, num("Math.sign(9)"));
    }

    // pow, min, max over varargs
    @Test
    public void test_pow_min_max() {
        assertEquals(8, num("Math.pow(2, 3)"));
        assertEquals(1, num("Math.min(3, 1, 2)"));
        assertEquals(3, num("Math.max(3, 1, 2)"));
        assertTrue(Double.isNaN(num("Math.max(1, 0 / 0)")));
    }

    // random stays within [0, 1)
    @Test
    public void test_random_range() {
        final var r = num("Math.random()");
        assertTrue(r >= 0 && r < 1);
    }

    // transcendental functions delegate to java.lang.Math
    @Test
    public void test_transcendental() {
        assertEquals(0, num("Math.log(1)"));
        assertEquals(1, num("Math.exp(0)"));
        assertEquals(0, num("Math.sin(0)"));
        assertEquals(1, num("Math.cos(0)"));
        assertEquals(0, num("Math.tan(0)"));
    }

    // Logarithms and exponentials added for ES completeness
    @Test
    public void test_logs_and_exponentials() {
        assertEquals(3, num("Math.log2(8)"));
        assertEquals(3, num("Math.log10(1000)"));
        assertEquals(0, num("Math.log1p(0)"));
        assertEquals(0, num("Math.expm1(0)"));
    }

    // Trigonometric and hyperbolic functions
    @Test
    public void test_trig_and_hyperbolic() {
        assertEquals(0, num("Math.asin(0)"));
        assertEquals(0, num("Math.acos(1)"));
        assertEquals(0, num("Math.atan(0)"));
        assertEquals(0, num("Math.sinh(0)"));
        assertEquals(1, num("Math.cosh(0)"));
        assertEquals(0, num("Math.tanh(0)"));
        assertEquals(0, num("Math.asinh(0)"));
        assertEquals(0, num("Math.acosh(1)"));
        assertEquals(0, num("Math.atanh(0)"));
        assertTrue(num("Math.asinh(-1)") < 0);
        assertTrue(Double.isInfinite(num("Math.asinh(Infinity)")));
    }

    // atan2 spans the quadrants
    @Test
    public void test_atan2_quadrants() {
        assertEquals(Math.PI / 4, num("Math.atan2(1, 1)"));
        assertEquals(3 * Math.PI / 4, num("Math.atan2(1, -1)"));
        assertEquals(-Math.PI / 4, num("Math.atan2(-1, 1)"));
        assertEquals(0, num("Math.atan2(0, 1)"));
    }

    // hypot over zero, one and many arguments
    @Test
    public void test_hypot() {
        assertEquals(0, num("Math.hypot()"));
        assertEquals(5, num("Math.hypot(3, 4)"));
        assertEquals(3, num("Math.hypot(3)"));
        assertTrue(Double.isInfinite(num("Math.hypot(Infinity, NaN)")));
        assertTrue(Double.isNaN(num("Math.hypot(NaN, 1)")));
    }

    // clz32 counts leading zeros of the ToUint32 value
    @Test
    public void test_clz32() {
        assertEquals(32, num("Math.clz32(0)"));
        assertEquals(31, num("Math.clz32(1)"));
        assertEquals(0, num("Math.clz32(-1)"));
        assertEquals(32, num("Math.clz32(NaN)"));
        assertEquals(32, num("Math.clz32(Infinity)"));
    }

    // fround narrows through float precision
    @Test
    public void test_fround() {
        assertEquals(1, num("Math.fround(1)"));
        assertEquals((float) 1.1, num("Math.fround(1.1)"));
    }

    // imul multiplies as C-like Int32 arithmetic
    @Test
    public void test_imul() {
        assertEquals(6, num("Math.imul(2, 3)"));
        assertEquals(-6, num("Math.imul(-2, 3)"));
        assertEquals(-5, num("Math.imul(-1, 5)"));
        assertEquals(-2147483648d, num("Math.imul(2, 1073741824)"));
        assertEquals(2, num("Math.imul(4294967295, 5) + 7"));
        assertEquals(0, num("Math.imul(0 / 0, 5)"));
    }

    // trunc keeps values past the long range instead of clamping them
    @Test
    public void test_trunc_large() {
        assertEquals(1e21, num("Math.trunc(1e21)"));
        assertEquals(-1e21, num("Math.trunc(-1e21)"));
        assertEquals(-2, num("Math.trunc(-2.9)"));
        assertEquals(-0d, num("Math.trunc(-0.5)"));
    }

    // clz32 wraps its argument to Uint32 rather than saturating it
    @Test
    public void test_clz32_wraps_large_values() {
        assertEquals(32, num("Math.clz32(4294967296)"));
        assertEquals(31, num("Math.clz32(4294967297)"));
        assertEquals(2, num("Math.clz32(1e9)"));
    }

    // The logarithm and root constants
    @Test
    public void test_added_constants() {
        assertEquals(Math.log(2), num("Math.LN2"));
        assertEquals(Math.log(10), num("Math.LN10"));
        assertEquals(1 / Math.log(2), num("Math.LOG2E"));
        assertEquals(1 / Math.log(10), num("Math.LOG10E"));
        assertEquals(Math.sqrt(2), num("Math.SQRT2"));
        assertEquals(Math.sqrt(0.5), num("Math.SQRT1_2"));
    }

    // sumPrecise rounds once, so the cancelling terms do not lose the small addend
    @Test
    public void test_sum_precise_is_exact() {
        assertEquals(0.1, num("Math.sumPrecise([1e20, 0.1, -1e20])"));
        assertEquals(6, num("Math.sumPrecise([1, 2, 3])"));
    }

    // an empty iterable sums to -0
    @Test
    public void test_sum_precise_empty_is_negative_zero() {
        assertTrue(bool("Object.is(-0, Math.sumPrecise([]))"));
    }

    // NaN and opposing infinities produce NaN
    @Test
    public void test_sum_precise_nan_cases() {
        assertTrue(bool("Number.isNaN(Math.sumPrecise([NaN, 1]))"));
        assertTrue(bool("Number.isNaN(Math.sumPrecise([Infinity, -Infinity]))"));
    }

    // a single infinity dominates the sum
    @Test
    public void test_sum_precise_infinity() {
        assertEquals(Double.POSITIVE_INFINITY, num("Math.sumPrecise([Infinity, 1])"));
        assertEquals(Double.NEGATIVE_INFINITY, num("Math.sumPrecise([-Infinity, 1])"));
    }

    // a non-number element is rejected
    @Test
    public void test_sum_precise_non_number_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Math.sumPrecise(['1'])"));
    }

    // a non-iterable argument is rejected
    @Test
    public void test_sum_precise_non_iterable_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Math.sumPrecise(1)"));
    }

    // any iterable works, not just an array
    @Test
    public void test_sum_precise_generator() {
        assertEquals(6, num("function* g() { yield 1; yield 2; yield 3; } Math.sumPrecise(g())"));
    }
}
