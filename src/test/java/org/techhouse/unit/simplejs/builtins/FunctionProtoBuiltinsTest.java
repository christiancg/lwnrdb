package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;

public class FunctionProtoBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    // call invokes with an explicit this and positional arguments
    @Test
    public void test_call_with_this_and_args() {
        assertEquals(7, num("function f(y) { return this.x + y; } f.call({x: 3}, 4)"));
    }

    // apply spreads an array of arguments
    @Test
    public void test_apply_with_array() {
        assertEquals(6, num("function f(a, b, c) { return a + b + c; } f.apply(null, [1, 2, 3])"));
    }

    // apply with empty or missing argument array passes no arguments
    @Test
    public void test_apply_empty_or_missing() {
        assertEquals(3, num("function f() { return this.x; } f.apply({x: 3})"));
        assertEquals(3, num("function f() { return this.x; } f.apply({x: 3}, [])"));
    }

    // bind performs partial application and pins this
    @Test
    public void test_bind_partial_application() {
        assertEquals(10, num("function f(a, b) { return this.base + a + b; } let g = f.bind({base: 4}, 1); g(5)"));
    }

    // a bound function invoked with new ignores the new instance (documented limitation)
    @Test
    public void test_bind_then_new() {
        assertEquals(1, num("function f() { this.z = 1; return this.z; } let g = f.bind({}); new g()"));
    }

    // bind of a native function still applies
    @Test
    public void test_bind_native_function() {
        assertEquals(7, num("let f = Math.max.bind(null, 3); f(7)"));
    }

    // bind works on an anonymous arrow function
    @Test
    public void test_bind_anonymous_arrow() {
        assertEquals(5, num("let g = ((a, b) => a + b).bind(null, 2); g(3)"));
    }

    // an unknown function member reads as undefined
    @Test
    public void test_unknown_function_member() {
        assertEquals("undefined", str());
    }

    private static String str() {
        return ((org.techhouse.simplejs.values.JsString) Interpreter.run("typeof (function() {}).nope")).getValue();
    }
}
