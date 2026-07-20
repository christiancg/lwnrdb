package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;

public class NumberBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
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
}
