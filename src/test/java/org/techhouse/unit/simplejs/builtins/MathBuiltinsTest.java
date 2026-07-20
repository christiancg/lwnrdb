package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;

public class MathBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
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
}
