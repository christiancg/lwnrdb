package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class FunctionProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_instanceof_plain_function() {
        assertTrue(bool("function F(){ this.x = 1; } new F() instanceof F"));
    }

    @Test
    public void test_instanceof_plain_function_false() {
        assertFalse(bool("function F(){} ({}) instanceof F"));
    }

    @Test
    public void test_prototype_method_resolution() {
        final var source = """
                function F(){ this.x = 10; }
                F.prototype.doubled = function() { return this.x * 2; };
                new F().doubled()
                """;
        assertEquals(20, num(source));
    }

    @Test
    public void test_prototype_constructor_back_reference() {
        assertTrue(bool("function F(){} new F().constructor === F"));
    }

    @Test
    public void test_bound_function_new() {
        final var source = """
                function Point(x, y){ this.x = x; this.y = y; }
                const g = Point.bind(null, 3);
                const p = new g(4);
                p.x + p.y
                """;
        assertEquals(7, num(source));
    }

    @Test
    public void test_bound_function_new_instanceof() {
        final var source = """
                function Point(x){ this.x = x; }
                const g = Point.bind(null, 3);
                new g() instanceof Point
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_bound_function_new_ignores_bound_this() {
        final var source = """
                function F(){ this.here = true; }
                const g = F.bind({ here: false });
                new g().here
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_tagged_template_in_new_position() {
        final var source = """
                function make(){ return function C(){ this.tagged = true; }; }
                function tag(){ return make(); }
                new tag`hello`().tagged
                """;
        assertTrue(bool(source));
    }

    // The engine is always-strict, so `arguments` is always the unmapped form even for a simple
    // parameter list: writing arguments[0] never aliases the named parameter.
    @Test
    public void test_unmapped_arguments_index_does_not_alias_param() {
        assertEquals(1, num("function f(a){ arguments[0] = 9; return a; } f(1)"));
    }

    @Test
    public void test_unmapped_arguments_param_does_not_alias_index() {
        assertEquals(1, num("function f(a){ a = 9; return arguments[0]; } f(1)"));
    }

    @Test
    public void test_unmapped_arguments_with_default_param() {
        assertEquals(1, num("function f(a = 0){ arguments[0] = 9; return a; } f(1)"));
    }

    @Test
    public void test_unmapped_arguments_with_rest_param() {
        assertEquals(1, num("function f(...a){ arguments[0] = 9; return a[0]; } f(1)"));
    }

    @Test
    public void test_arguments_length_counts_passed() {
        assertEquals(3, num("function f(a){ return arguments.length; } f(1, 2, 3)"));
    }

    @Test
    public void test_arguments_extra_args_not_mapped() {
        assertEquals(5, num("function f(a){ arguments[1] = 5; return arguments[1]; } f(1, 2)"));
    }

    @Test
    public void test_arguments_for_of() {
        final var source = """
                function f(a, b){
                    a = 10;
                    let sum = 0;
                    for (const x of arguments) { sum += x; }
                    return sum;
                }
                f(1, 2)
                """;
        assertEquals(3, num(source));
    }

    @Test
    public void test_arguments_spread() {
        assertEquals(6, num("function f(){ return [...arguments].reduce((a, b) => a + b, 0); } f(1, 2, 3)"));
    }

    @Test
    public void test_arguments_callee_poisoned() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("function f(){ return arguments.callee; } f()"));
    }

    @Test
    public void test_arguments_caller_absent() {
        assertTrue(bool("function f(){ return Object.getOwnPropertyDescriptor(arguments, 'caller') === undefined"
                + " && arguments.caller === undefined; } f()"));
    }

    @Test
    public void test_function_own_property_read_write() {
        assertEquals(1, num("function f(){} f.x = 1; f.x"));
    }

    @Test
    public void test_function_own_method_call() {
        assertEquals(2, num("function f(){} f.m = function(){ return 2; }; f.m()"));
    }

    @Test
    public void test_arrow_own_property() {
        assertEquals(3, num("var g = () => {}; g.x = 3; g.x"));
    }

    @Test
    public void test_native_function_own_property() {
        assertEquals(1, num("Array.from.tag = 1; Array.from.tag"));
    }

    @Test
    public void test_function_own_property_in_operator() {
        assertTrue(bool("function f(){} f.x = 1; 'x' in f"));
        assertFalse(bool("function f(){} f.x = 1; 'y' in f"));
    }

    @Test
    public void test_function_in_operator_reaches_builtins() {
        assertTrue(bool("function f(){} 'name' in f"));
        assertTrue(bool("function f(){} 'call' in f"));
        assertTrue(bool("function f(){} 'prototype' in f"));
    }

    @Test
    public void test_function_own_property_delete() {
        assertTrue(bool("function f(){} f.x = 1; delete f.x"));
        assertEquals("undefined", str("function f(){} f.x = 1; delete f.x; typeof f.x"));
    }

    @Test
    public void test_function_own_keys() {
        assertEquals("[\"x\"]", str("function f(a, b){} f.x = 1; JSON.stringify(Object.keys(f))"));
        assertEquals("[\"length\",\"name\",\"prototype\",\"x\"]",
                str("function f(){} f.x = 1; JSON.stringify(Object.getOwnPropertyNames(f))"));
    }

    @Test
    public void test_native_function_builtin_statics_not_enumerable() {
        assertEquals("[]", str("JSON.stringify(Object.keys(Object))"));
        assertEquals("[\"tag\"]", str("Object.keys.tag = 1; JSON.stringify(Object.keys(Object.keys))"));
    }

    @Test
    public void test_function_own_property_descriptor() {
        final var source = """
                function f(){}
                f.x = 1;
                const d = Object.getOwnPropertyDescriptor(f, 'x');
                JSON.stringify([d.value, d.writable, d.enumerable, d.configurable])
                """;
        assertEquals("[1,true,true,true]", str(source));
        assertEquals("undefined", str("function f(){} typeof Object.getOwnPropertyDescriptor(f, 'x')"));
    }

    @Test
    public void test_function_own_property_shadows_builtin() {
        assertEquals(1, num("function f(){} f.call = 1; f.call"));
    }

    @Test
    public void test_function_own_properties_enumerate() {
        assertEquals("[\"x\"]",
                str("function f(){} f.x = 1; const o = []; for (const k in f) o.push(k); JSON.stringify(o)"));
        assertEquals("{\"x\":1}", str("function f(){} f.x = 1; JSON.stringify(Object.assign({}, f))"));
    }

    @Test
    public void test_function_metadata_unchanged() {
        assertEquals("[\"f\",1,\"object\"]",
                str("function f(a){} JSON.stringify([f.name, f.length, typeof f.prototype])"));
    }

    @Test
    public void test_assert_js_shape() {
        final var source = """
                function assert(mustBeTrue, message) {
                    if (mustBeTrue === true) { return; }
                    throw new Error(message || 'assert failed');
                }
                assert._isSameValue = function (a, b) {
                    if (a === b) { return a !== 0 || 1 / a === 1 / b; }
                    return a !== a && b !== b;
                };
                assert.sameValue = function (actual, expected, message) {
                    if (assert._isSameValue(actual, expected)) { return; }
                    throw new Error(message || 'sameValue failed');
                };
                assert.throws = function (expectedErrorConstructor, func, message) {
                    try {
                        func();
                    } catch (thrown) {
                        if (thrown instanceof expectedErrorConstructor) { return; }
                        throw new Error('wrong error type');
                    }
                    throw new Error(message || 'did not throw');
                };
                assert(true);
                assert.sameValue(NaN, NaN);
                assert.throws(TypeError, function () { null.x; });
                'prelude-ok'
                """;
        assertEquals("prelude-ok", str(source));
    }

    @Test
    public void test_reassigned_prototype_used_by_new_and_inheritance() {
        final var source = """
                function Base() {}
                Base.prototype.greet = function () { return 'hi'; };
                function Con() {}
                Con.prototype = Base.prototype;
                var child = new Con();
                JSON.stringify([child instanceof Base, child.greet()])
                """;
        assertEquals("[true,\"hi\"]", str(source));
    }
}
