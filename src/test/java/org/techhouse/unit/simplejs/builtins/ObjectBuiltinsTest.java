package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class ObjectBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool() {
        return ((JsBoolean) Interpreter.run("let o = {}; Object.freeze(o) === o")).getValue();
    }

    // keys/values/entries enumerate own properties in insertion order
    @Test
    public void test_keys_values_entries() {
        assertEquals("a,b", str("Object.keys({a: 1, b: 2}).join(',')"));
        assertEquals("1,2", str("Object.values({a: 1, b: 2}).join(',')"));
        assertEquals("a=1,b=2", str("Object.entries({a: 1, b: 2}).map(e => e[0] + '=' + e[1]).join(',')"));
    }

    // keys/values also work over arrays (index keys)
    @Test
    public void test_over_arrays() {
        assertEquals("0,1", str("Object.keys(['x', 'y']).join(',')"));
        assertEquals("x,y", str("Object.values(['x', 'y']).join(',')"));
    }

    // assign copies own properties into the target and returns it
    @Test
    public void test_assign() {
        assertEquals(3, num("let t = Object.assign({a: 1}, {b: 2}); t.a + t.b"));
        assertEquals(9, num("let t = Object.assign({x: 1}, {x: 9}); t.x"));
    }

    // freeze blocks further writes
    @Test
    public void test_freeze() {
        assertEquals(1, num("let o = Object.freeze({a: 1}); o.a = 5; o.a"));
        assertTrue(bool());
    }
}
