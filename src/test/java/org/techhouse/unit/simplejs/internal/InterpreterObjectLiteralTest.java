package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterObjectLiteralTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean flag(String source) {
        return ((org.techhouse.simplejs.values.JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_object_literal_shorthand_and_computed() {
        assertEquals(3, num("let x = 3; let o = {x}; o.x"));
        assertEquals("v", str("let k = 'key'; let o = {[k]: 'v'}; o.key"));
        assertEquals("num", str("let o = {1: 'num'}; o[1]"));
    }

    @Test
    public void test_array_spread() {
        assertEquals("1,2,3,4", str("let a = [1, 2]; let b = [...a, 3, 4]; b.join(',')"));
        assertEquals("a,b,c", str("[...'abc'].join(',')"));
    }

    @Test
    public void test_object_spread() {
        assertEquals(9, num("let a = {x: 1}; let b = {...a, x: 9}; b.x"));
        assertEquals(2, num("let a = {x: 1}; let b = {...a, y: 2}; b.y"));
    }

    @Test
    public void test_object_spread_copies_symbol_keyed_accessor() {
        assertTrue(flag("""
                var s = Symbol('k');
                var o = {};
                Object.defineProperty(o, s, { get: function() { return 'v'; }, enumerable: true });
                let copy = {...o};
                copy[s] === 'v'
                """));
        assertTrue(flag("""
                var s = Symbol('k');
                var o = {};
                Object.defineProperty(o, s, { value: 'v', enumerable: false });
                let copy = {...o};
                copy[s] === undefined
                """));
    }

    @Test
    public void test_call_spread() {
        assertEquals(6, num("function add(a, b, c) { return a + b + c; } add(...[1, 2, 3])"));
    }

    @Test
    public void test_object_method_shorthand() {
        assertEquals(5, num("let o = { x: 2, add(n) { return this.x + n; } }; o.add(3)"));
    }

    @Test
    public void test_object_computed_method() {
        assertEquals(7, num("let k = 'go'; let o = { [k]() { return 7; } }; o.go()"));
    }

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

    @Test
    public void test_object_getter_only() {
        assertEquals(42, num("let o = { get answer() { return 42; } }; o.answer"));
    }

    @Test
    public void test_object_literal_symbol_computed_accessor_get() {
        assertEquals(1, num("let o = { get [Symbol.iterator]() { return 1; } }; o[Symbol.iterator]"));
    }

    @Test
    public void test_object_literal_symbol_computed_accessor_set() {
        assertEquals(10,
                num("let v = 0; let o = { set [Symbol.iterator](n) { v = n * 2; } }; o[Symbol.iterator] = 5; v"));
    }

    @Test
    public void test_object_literal_symbol_computed_accessor_throwing_getter_propagates() {
        assertThrows(org.techhouse.simplejs.exceptions.JsThrowException.class, () -> Interpreter
                .run("let o = { get [Symbol.iterator]() { throw new RangeError('boom'); } }; o[Symbol.iterator]"));
    }

    @Test
    public void test_object_literal_proto_key() {
        assertEquals("hi", str("const p = { greet() { return 'hi' } }; const o = { __proto__: p }; o.greet()"));
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter
                .run("const p = {}; Object.getPrototypeOf({ __proto__: p }) === p")).getValue());
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter
                .run("Object.getPrototypeOf({ __proto__: null }) === null")).getValue());
    }

    @Test
    public void test_object_literal_computed_proto_key() {
        assertEquals("object", str("const p = {}; typeof ({ ['__proto__']: p }).__proto__"));
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter
                .run("const p = {}; Object.getPrototypeOf({ ['__proto__']: p }) === Object.prototype")).getValue());
    }

    @Test
    public void test_object_literal_proto_key_ignored() {
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter
                .run("({ __proto__: 1 }).__proto__ === Object.prototype")).getValue());
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter
                .run("({ __proto__: 'x' }).__proto__ === Object.prototype")).getValue());
    }

    @Test
    public void test_object_assign_and_spread_order() {
        assertEquals("1,2,b,a", str("Object.keys(Object.assign({}, {b: 1, 2: 2, a: 3, 1: 4})).join(',')"));
        assertEquals("1,2,b,a", str("Object.keys({...{b: 1, 2: 2, a: 3, 1: 4}}).join(',')"));
    }

    @Test
    public void test_object_literal_super() {
        assertEquals("po", str(
                "const p = { m() { return 'p'; } }; const o = { __proto__: p, m() { return super.m() + 'o'; } }; o.m()"));
        assertEquals(2, num(
                "const p = { get v() { return 1; } }; const o = { __proto__: p, get v() { return super.v + 1; } }; o.v"));
        assertEquals("p", str(
                "const p = { m() { return 'p'; } }; const o = { __proto__: p, m() { const g = () => super.m(); return g(); } }; o.m()"));
    }

    @Test
    public void test_spread_invokes_getter() {
        assertEquals(1, num("({...{get x() { return 1; }}}).x"));
    }

    @Test
    public void test_rest_destructuring_invokes_getter() {
        assertEquals(2, num("const {a, ...rest} = {a: 1, get b() { return 2; }}; rest.b"));
    }

    @Test
    public void test_for_in_lists_accessor() {
        assertEquals("x", str("let out = ''; for (const k in {get x() { return 1; }}) out += k; out"));
    }

    @Test
    public void test_setter_only_own_accessor_shadows_inherited_getter() {
        final var source = """
                let proto = {};
                Object.defineProperty(proto, 'x', { get() { return 'inherited'; } });
                let child = Object.create(proto);
                Object.defineProperty(child, 'x', { set() {} });
                typeof child.x
                """;
        assertEquals("undefined", str(source));
    }

    @Test
    public void test_redefine_accessor_as_data_property_clears_stale_getter() {
        final var source = """
                let obj = {};
                Object.defineProperty(obj, 'x', { get() { return 'stale'; }, configurable: true });
                Object.defineProperty(obj, 'x', { value: 'fresh' });
                obj.x
                """;
        assertEquals("fresh", str(source));
    }

    @Test
    public void test_redefine_partial_accessor_preserves_untouched_side() {
        final var source = """
                let calls = [];
                let obj = {};
                Object.defineProperty(obj, 'x', {
                    get() { return 'old-get'; },
                    set(v) { calls.push(v); },
                    configurable: true
                });
                Object.defineProperty(obj, 'x', { get: undefined });
                obj.x = 'set-after-redefine';
                JSON.stringify([calls, typeof obj.x])
                """;
        assertEquals("[[\"set-after-redefine\"],\"undefined\"]", str(source));
    }

    @Test
    public void test_computed_key_invokes_topropertykey_side_effect() {
        final var source = """
                var counter = 0;
                var key1 = { toString() { return counter++, 'b'; } };
                var key2 = { toString() { return counter++, 'd'; } };
                var object = {
                    a() { return 'A'; },
                    [key1]() { return 'B'; },
                    c() { return 'C'; },
                    [key2]() { return 'D'; },
                };
                JSON.stringify([counter, object.a(), object.b(), object.c(), object.d()])
                """;
        assertEquals("[2,\"A\",\"B\",\"C\",\"D\"]", str(source));
    }

    @Test
    public void test_object_spread_proxy_trap_order_and_symbol_getter() {
        final var source = """
                var calls = [];
                var sym = Symbol('s');
                var proxy = new Proxy({}, {
                    ownKeys: function() { return ['a', 'b', sym]; },
                    getOwnPropertyDescriptor: function(t, key) {
                        calls.push('gopd:' + String(key));
                        return { value: 1, enumerable: key !== 'b', configurable: true };
                    },
                    get: function(t, key) { calls.push('get:' + String(key)); return 42; },
                });
                var copy = { ...proxy };
                JSON.stringify([calls, copy.a, copy.b, copy[sym]])
                """;
        assertEquals("[[\"gopd:a\",\"get:a\",\"gopd:b\",\"gopd:Symbol(s)\",\"get:Symbol(s)\"],42,null,42]",
                str(source));
    }

    @Test
    public void test_proto_special_case_excludes_shorthand_and_method() {
        assertTrue(flag("var __proto__ = 2; var o = {__proto__, __proto__}; o.hasOwnProperty('__proto__')"));
        assertEquals(2, num("var __proto__ = 2; var o = {__proto__}; o.__proto__"));
        assertTrue(flag("var o = { __proto__() { return 1; } }; o.hasOwnProperty('__proto__')"));
        assertFalse(flag("var p = {}; var o = {__proto__: p}; o.hasOwnProperty('__proto__')"));
    }
}
