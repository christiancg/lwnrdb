package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
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

    // a bound function used with new constructs the underlying target (bound this ignored)
    @Test
    public void test_bind_then_new() {
        assertEquals(1, num("function f() { this.z = 1; } let g = f.bind({}); new g().z"));
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

    @Test
    public void applyAcceptsArrayLike() {
        assertEquals(6, num("function f(a, b, c) { return a + b + c; } f.apply(null, {0: 1, 1: 2, 2: 3, length: 3})"));
        assertEquals(6, num("function f(a, b, c) { return a + b + c; }"
                + " function g() { return f.apply(null, arguments); } g(1, 2, 3)"));
        assertEquals(0, num("function f() { return arguments.length; } f.apply(null)"));
    }

    @Test
    public void applyThrowsOnNonObjectArgArray() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("(function() {}).apply(null, 1)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("(function() {}).apply(null, 'ab')"));
    }

    @Test
    public void bindComputesLength() {
        assertEquals(2, num("(function(a, b, c) {}).bind(null, 1).length"));
        assertEquals(0, num("(function(a) {}).bind(null, 1, 2, 3).length"));
        assertEquals(1, num("Math.max.bind(null, 1).length"));
    }

    @Test
    public void bindDerivesNameFromTarget() {
        assertEquals("bound f", strOf("(function f() {}).bind(null).name"));
        assertEquals("bound bound f", strOf("(function f() {}).bind(null).bind(null).name"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Function.prototype.bind.call({})"));
    }

    @Test
    public void toStringReturnsSourceTextForUserFunctions() {
        assertEquals("function f() { return 1; }", strOf("(function f() { return 1; }).toString()"));
        assertEquals("function f() {}", strOf("'' + function f() {}"));
        assertEquals("function () {}", strOf("String(function () {})"));
        assertEquals("class C {}", strOf("String(class C {})"));
    }

    // A builtin has no source of its own, so it keeps the NativeFunction form.
    @Test
    public void toStringReturnsTheNativeFormForBuiltins() {
        assertEquals("function values() { [native code] }", strOf("Object.values.toString()"));
        assertEquals("function () { [native code] }", strOf("String((function f() {}).bind(null))"));
    }

    @Test
    public void functionPrototypeExposesSymbolHasInstance() {
        assertEquals("function", strOf("typeof Function.prototype[Symbol.hasInstance]"));
        assertEquals("true", strOf("let f = function() {}; String(f[Symbol.hasInstance](new f()))"));
        assertEquals("false", strOf("String((function() {})[Symbol.hasInstance]({}))"));
    }

    @Test
    public void instanceofReadsThePrototypeProperty() {
        assertEquals("true", strOf("function f() {} let p = f.prototype; String(Object.create(p) instanceof f)"));
        assertEquals("false", strOf("function f() {} String({} instanceof f)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("({}) instanceof Object.create(null)"));
    }

    // BoundFunctionLength reads the target's own `length` without coercing it: an absent or
    // non-Number one gives 0, an infinite one survives the subtraction, and a fractional one truncates
    @Test
    public void test_bound_length() {
        assertEquals(2, num("function f(a, b, c) {} f.bind(null, 1).length"));
        assertEquals(0, num("function f(a) {} f.bind(null, 1, 2, 3).length"));
        assertEquals(Double.POSITIVE_INFINITY,
                num("function f() {} Object.defineProperty(f, 'length', {value: Infinity}); f.bind(0, 0).length"));
        assertEquals(0, num("function f() {} Object.defineProperty(f, 'length', {value: -Infinity}); f.bind().length"));
        assertEquals(3, num("function f() {} Object.defineProperty(f, 'length', {value: 3.66}); f.bind().length"));
        assertEquals(0, num("function f() {} Object.defineProperty(f, 'length', {value: NaN}); f.bind().length"));
        assertEquals(0, num("function f() {} Object.defineProperty(f, 'length', {value: '1'}); f.bind().length"));
        assertEquals(2147483648d,
                num("function f() {} Object.defineProperty(f, 'length', {value: 2147483648}); f.bind().length"));
        assertEquals(0, num("function f() {} Object.setPrototypeOf(f, {length: 42}); delete f.length;"
                + "Function.prototype.bind.call(f, null, 1).length"));
    }

    // the bound function's own `length` keeps the builtin shape {w:false, e:false, c:true}
    @Test
    public void test_bound_length_descriptor() {
        assertEquals("false,false,true",
                strOf("function f(a) {} let d =" + " Object.getOwnPropertyDescriptor(f.bind(null), 'length');"
                        + "[d.writable, d.enumerable, d.configurable].join(',')"));
    }

    private static String strOf(String source) {
        return ((org.techhouse.simplejs.values.JsString) Interpreter.run(source)).getValue();
    }

    private static String str() {
        return ((org.techhouse.simplejs.values.JsString) Interpreter.run("typeof (function() {}).nope")).getValue();
    }

    // A class constructor has a [[Call]] slot (bind never invokes it, so the class's own
    // "cannot be called without new" restriction is irrelevant here) and so must be bindable, unlike
    // a plain non-callable value.
    @Test
    public void test_bind_accepts_a_class_target() {
        assertEquals(3, num("""
                class Foo {
                    constructor(a, b) { this.sum = a + b; }
                }
                let Bound = Foo.bind(null, 1);
                new Bound(2).sum
                """));
    }

    // bind still rejects a genuinely non-callable receiver
    @Test
    public void test_bind_rejects_non_callable() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Function.prototype.bind.call({}, null)"));
    }

    // ExpectedArgumentCount: a plain BindingPattern parameter with no initializer still counts like
    // any other parameter - only a default value (AssignmentPattern) or a rest parameter stops the
    // count.
    @Test
    public void test_length_counts_plain_pattern_but_stops_at_default_or_rest() {
        assertEquals(2, num("(function(a, {b}) {}).length"));
        assertEquals(2, num("(function(a, [b, c]) {}).length"));
        assertEquals(1, num("(function(a, b = 1) {}).length"));
        assertEquals(1, num("(function(a, ...rest) {}).length"));
    }
}
