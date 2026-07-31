package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class InterpreterObjectTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // Object literals support shorthand and computed keys
    @Test
    public void test_object_literal_shorthand_and_computed() {
        assertEquals(3, num("let x = 3; let o = {x}; o.x"));
        assertEquals("v", str("let k = 'key'; let o = {[k]: 'v'}; o.key"));
        assertEquals("num", str("let o = {1: 'num'}; o[1]"));
    }

    // Array literals keep holes as undefined
    @Test
    public void test_array_holes() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("let a = [1, , 3]; a[1]"));
        assertEquals(3, num("let a = [1, , 3]; a.length"));
    }

    // Spread expands arrays and strings into array literals
    @Test
    public void test_array_spread() {
        assertEquals("1,2,3,4", str("let a = [1, 2]; let b = [...a, 3, 4]; b.join(',')"));
        assertEquals("a,b,c", str("[...'abc'].join(',')"));
    }

    // Spread merges object properties, later keys winning
    @Test
    public void test_object_spread() {
        assertEquals(9, num("let a = {x: 1}; let b = {...a, x: 9}; b.x"));
        assertEquals(2, num("let a = {x: 1}; let b = {...a, y: 2}; b.y"));
    }

    // Spread expands arguments into a call
    @Test
    public void test_call_spread() {
        assertEquals(6, num("function add(a, b, c) { return a + b + c; } add(...[1, 2, 3])"));
    }

    // Member assignment writes object and array slots
    @Test
    public void test_member_assignment() {
        assertEquals(7, num("let o = {}; o.a = 7; o.a"));
        assertEquals(5, num("let a = []; a[2] = 5; a[2]"));
    }

    // Optional chaining short-circuits on nullish receivers
    @Test
    public void test_optional_chaining() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("let o = null; o?.a"));
        assertEquals(1, num("let o = {a: 1}; o?.a"));
    }

    // Array destructuring binds elements, holes and rest
    @Test
    public void test_array_destructuring() {
        assertEquals(3, num("let [a, b] = [1, 2]; a + b"));
        assertEquals(3, num("let [, second] = [1, 3]; second"));
        assertEquals("2,3", str("let [first, ...rest] = [1, 2, 3]; rest.join(',')"));
        assertEquals(9, num("let [x = 9] = []; x"));
    }

    // Object destructuring binds, renames, defaults, computed keys and rest
    @Test
    public void test_object_destructuring() {
        assertEquals(3, num("let {a, b} = {a: 1, b: 2}; a + b"));
        assertEquals(5, num("let {a: renamed} = {a: 5}; renamed"));
        assertEquals(2, num("let {a, b = 2} = {a: 1}; b"));
        assertEquals(7, num("let k = 'x'; let {[k]: v} = {x: 7}; v"));
        assertEquals(2, num("let {a, ...rest} = {a: 1, b: 2, c: 3}; Object.keys(rest).length"));
    }

    // Nested patterns destructure recursively
    @Test
    public void test_nested_destructuring() {
        assertEquals(42, num("let {a: {b}} = {a: {b: 42}}; b"));
        assertEquals(2, num("let [[x], [y]] = [[1], [2]]; y"));
    }

    // Destructuring assignment reassigns existing bindings and swaps values
    @Test
    public void test_destructuring_assignment() {
        assertEquals(1, num("let a = 2, b = 1; [a, b] = [b, a]; a"));
        assertEquals(5, num("let o = {}; ({v: o.x} = {v: 5}); o.x"));
    }

    // Default, pattern and rest parameters bind from arguments
    @Test
    public void test_function_patterns() {
        assertEquals(3, num("function f(a = 1, b = 2) { return a + b; } f()"));
        assertEquals(30, num("function f({x, y}) { return x + y; } f({x: 10, y: 20})"));
        assertEquals(6, num("function f(...nums) { return nums.reduce((a, b) => a + b, 0); } f(1, 2, 3)"));
    }

    // Catch clause destructures the thrown error object
    @Test
    public void test_catch_pattern() {
        assertEquals("boom",
                str("let msg = ''; try { throw {message: 'boom'}; } catch ({message}) { msg = message; } msg"));
    }

    // Destructuring a non-object throws a TypeError
    @Test
    public void test_destructure_null_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("let {a} = null; a"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("let [a] = 5; a"));
    }

    // A compound-operator destructuring assignment is rejected
    @Test
    public void test_invalid_destructuring_assignment() {
        assertThrows(RuntimeException.class, () -> Interpreter.run("let a; [a] += [1];"));
    }

    // Empty patterns bind nothing and do not fail
    @Test
    public void test_empty_patterns() {
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter.run("let {} = {}; true")).getValue());
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter.run("let [] = []; true")).getValue());
    }

    // Object-literal method shorthand defines a callable member bound to the object
    @Test
    public void test_object_method_shorthand() {
        assertEquals(5, num("let o = { x: 2, add(n) { return this.x + n; } }; o.add(3)"));
    }

    // A computed method key stores the method under the evaluated name
    @Test
    public void test_object_computed_method() {
        assertEquals(7, num("let k = 'go'; let o = { [k]() { return 7; } }; o.go()"));
    }

    // Getter and setter accessors run on read and write
    @Test
    public void test_object_accessors() {
        final var source = """
                let o = {
                    _v: 1,
                    get v() { return this._v; },
                    set v(n) { this._v = n * 2; }
                };
                o.v = 5;
                o.v
                """;
        assertEquals(10, num(source));
    }

    // A getter-only accessor returns its computed value
    @Test
    public void test_object_getter_only() {
        assertEquals(42, num("let o = { get answer() { return 42; } }; o.answer"));
    }

    // A property literally named get/set is not treated as an accessor
    @Test
    public void test_object_get_set_as_keys() {
        assertEquals(3, num("let o = { get: 1, set: 2 }; o.get + o.set"));
    }

    // An async object method resolves through the microtask queue
    @Test
    public void test_object_async_method() {
        final var source = """
                let out = [];
                let o = { async f() { return 4; } };
                o.f().then(v => out.push(v));
                out
                """;
        assertEquals(4,
                ((JsNumber) ((org.techhouse.simplejs.values.JsArray) Interpreter.run(source)).get(0)).getValue());
    }

    // A string-valued [Symbol.toStringTag] customizes Object.prototype.toString
    @Test
    public void test_symbol_to_string_tag() {
        assertEquals("[object Tag]", str("let o = { [Symbol.toStringTag]: 'Tag' }; o.toString()"));
        assertEquals("[object Object]", str("({}).toString()"));
    }

    // A non-string [Symbol.toStringTag] is ignored, falling back to the default tag
    @Test
    public void test_symbol_to_string_tag_non_string_ignored() {
        assertEquals("[object Object]", str("let o = { [Symbol.toStringTag]: 42 }; o.toString()"));
    }
}
