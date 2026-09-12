package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static boolean flag(String source) {
        return ((org.techhouse.simplejs.values.JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_array_holes() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("let a = [1, , 3]; a[1]"));
        assertEquals(3, num("let a = [1, , 3]; a.length"));
    }

    @Test
    public void test_member_assignment() {
        assertEquals(7, num("let o = {}; o.a = 7; o.a"));
        assertEquals(5, num("let a = []; a[2] = 5; a[2]"));
    }

    @Test
    public void test_optional_chaining() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("let o = null; o?.a"));
        assertEquals(1, num("let o = {a: 1}; o?.a"));
    }

    @Test
    public void test_array_destructuring() {
        assertEquals(3, num("let [a, b] = [1, 2]; a + b"));
        assertEquals(3, num("let [, second] = [1, 3]; second"));
        assertEquals("2,3", str("let [first, ...rest] = [1, 2, 3]; rest.join(',')"));
        assertEquals(9, num("let [x = 9] = []; x"));
    }

    @Test
    public void test_object_destructuring() {
        assertEquals(3, num("let {a, b} = {a: 1, b: 2}; a + b"));
        assertEquals(5, num("let {a: renamed} = {a: 5}; renamed"));
        assertEquals(2, num("let {a, b = 2} = {a: 1}; b"));
        assertEquals(7, num("let k = 'x'; let {[k]: v} = {x: 7}; v"));
        assertEquals(2, num("let {a, ...rest} = {a: 1, b: 2, c: 3}; Object.keys(rest).length"));
    }

    @Test
    public void test_nested_destructuring() {
        assertEquals(42, num("let {a: {b}} = {a: {b: 42}}; b"));
        assertEquals(2, num("let [[x], [y]] = [[1], [2]]; y"));
    }

    @Test
    public void test_destructuring_assignment() {
        assertEquals(1, num("let a = 2, b = 1; [a, b] = [b, a]; a"));
        assertEquals(5, num("let o = {}; ({v: o.x} = {v: 5}); o.x"));
    }

    @Test
    public void test_function_patterns() {
        assertEquals(3, num("function f(a = 1, b = 2) { return a + b; } f()"));
        assertEquals(30, num("function f({x, y}) { return x + y; } f({x: 10, y: 20})"));
        assertEquals(6, num("function f(...nums) { return nums.reduce((a, b) => a + b, 0); } f(1, 2, 3)"));
    }

    @Test
    public void test_catch_pattern() {
        assertEquals("boom",
                str("let msg = ''; try { throw {message: 'boom'}; } catch ({message}) { msg = message; } msg"));
    }

    @Test
    public void test_destructure_null_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("let {a} = null; a"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("let [a] = 5; a"));
    }

    @Test
    public void test_invalid_destructuring_assignment() {
        assertThrows(RuntimeException.class, () -> Interpreter.run("let a; [a] += [1];"));
    }

    @Test
    public void test_empty_patterns() {
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter.run("let {} = {}; true")).getValue());
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter.run("let [] = []; true")).getValue());
    }

    @Test
    public void test_object_get_set_as_keys() {
        assertEquals(3, num("let o = { get: 1, set: 2 }; o.get + o.set"));
    }

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

    @Test
    public void test_symbol_to_string_tag() {
        assertEquals("[object Tag]", str("let o = { [Symbol.toStringTag]: 'Tag' }; o.toString()"));
        assertEquals("[object Object]", str("({}).toString()"));
    }

    @Test
    public void test_symbol_to_string_tag_non_string_ignored() {
        assertEquals("[object Object]", str("let o = { [Symbol.toStringTag]: 42 }; o.toString()"));
    }

    @Test
    public void test_array_length_assignment() {
        assertEquals(1, num("const a = [1, 2, 3]; a.length = 1; a.length"));
        assertEquals(1, num("const a = [1, 2, 3]; a.length = 1; a[0]"));
        assertEquals(0, num("const a = [1, 2, 3]; a.length = 0; a.length"));
        assertEquals(3, num("const a = [1]; a.length = 3; a.length"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("const a = [1]; a.length = 3; a[2]"));
    }

    @Test
    public void test_key_order_integer_first() {
        assertEquals("1,2,b,a", str("Object.keys({b: 1, 2: 2, a: 3, 1: 4}).join(',')"));
        assertEquals("1,2,b,a", str("Object.getOwnPropertyNames({b: 1, 2: 2, a: 3, 1: 4}).join(',')"));
        assertEquals("4,2,1,3", str("Object.values({b: 1, 2: 2, a: 3, 1: 4}).join(',')"));
        assertEquals("1,2,b,a", str("Reflect.ownKeys({b: 1, 2: 2, a: 3, 1: 4}).join(',')"));
    }

    @Test
    public void test_forin_order_integer_first() {
        assertEquals("1,2,b,a",
                str("let r = []; for (const k in {b: 1, 2: 2, a: 3, 1: 4}) { r.push(k); } r.join(',')"));
    }

    @Test
    public void test_json_stringify_key_order() {
        assertEquals("{\"1\":4,\"2\":2,\"b\":1,\"a\":3}", str("JSON.stringify({b: 1, 2: 2, a: 3, 1: 4})"));
    }

    @Test
    public void test_non_canonical_index_keys_stay_strings() {
        assertEquals("1,01,-1,1.0,4294967295",
                str("Object.keys({'01': 1, '-1': 2, '1.0': 3, '4294967295': 4, 1: 5}).join(',')"));
        assertEquals("0,4294967294,x", str("Object.keys({x: 1, 4294967294: 2, 0: 3}).join(',')"));
    }

    @Test
    public void test_strict_assign_rejections_throw() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = Object.freeze({a: 1}); o.a = 2"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = Object.freeze({a: 1}); o.a += 2"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = Object.freeze({a: 1}); o.a++"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const o = Object.preventExtensions({}); o.b = 1"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = {get v() { return 1; }}; o.v = 2"));
    }

    @Test
    public void test_strict_delete_nonconfigurable_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = Object.freeze({a: 1}); delete o.a"));
        assertTrue(flag("const o = {a: 1}; delete o.b"));
    }

    @Test
    public void test_reflect_reports_rejection_as_false() {
        assertTrue(flag("const o = Object.freeze({a: 1}); Reflect.set(o, 'a', 2) === false"));
        assertTrue(flag("const o = Object.freeze({a: 1}); Reflect.deleteProperty(o, 'a') === false"));
        assertTrue(flag("const o = {}; Reflect.set(o, 'a', 2) === true"));
    }

    @Test
    public void test_freeze_array() {
        assertTrue(flag("Object.isFrozen(Object.freeze([1]))"));
        assertTrue(flag("!Object.isFrozen([1])"));
        assertTrue(flag("!Object.isExtensible(Object.freeze([1]))"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const a = Object.freeze([1]); a[0] = 9"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const a = Object.freeze([1]); a.length = 0"));
        assertEquals(1, num("const a = Object.freeze([1]); try { a.push(2); } catch (e) { } a.length"));
    }

    @Test
    public void test_prevent_extensions_array() {
        assertEquals(9, num("const a = Object.preventExtensions([1]); a[0] = 9; a[0]"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const a = Object.preventExtensions([1]); a[1] = 9"));
        assertTrue(flag("Object.isSealed(Object.seal([1]))"));
        assertTrue(flag("!Object.isFrozen(Object.seal([1]))"));
        assertTrue(flag("Object.isFrozen(Object.preventExtensions({}))"));
        assertTrue(flag("!Object.isFrozen(Object.preventExtensions([]))"));
        assertTrue(flag("Object.isFrozen(Object.freeze([]))"));
    }

    @Test
    public void test_array_length_assignment_range() {
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("const a = [1]; a.length = -1"));
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("const a = [1]; a.length = 1.5"));
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("const a = [1]; a.length = NaN"));
    }

    @Test
    public void test_super_without_home_still_syntax_error() {
        assertThrows(org.techhouse.simplejs.exceptions.SyntaxErrorException.class,
                () -> Interpreter.run("function f() { return super.x; } f()"));
        assertThrows(org.techhouse.simplejs.exceptions.SyntaxErrorException.class,
                () -> Interpreter.run("const o = { m: function() { return super.x; } }; o.m()"));
    }

    @Test
    public void test_in_operator_walks_prototype_chain() {
        assertTrue(flag("'foo' in Object.create({foo: 1})"));
        assertTrue(flag("'foo' in Object.create({get foo() { return 1; }})"));
        assertTrue(flag("'foo' in {foo: 1}"));
        assertFalse(flag("'bar' in Object.create({foo: 1})"));
    }

    @Test
    public void test_define_property_descriptor_fields_are_inherited() {
        final var source = """
                let sunk;
                let fun = function (v) { sunk = 'unset:' + v; };
                let proto = {};
                Object.defineProperty(proto, 'set', { get() { return fun; }, set(v) { fun = v; } });
                function Con() {}
                Con.prototype = proto;
                let descriptorLike = new Con();
                descriptorLike.set = function (v) { sunk = 'doubled:' + (v * 2); };
                let obj = {};
                Object.defineProperty(obj, 'prop', descriptorLike);
                obj.prop = 5;
                sunk
                """;
        assertEquals("doubled:10", str(source));
    }

    @Test
    public void test_object_destructuring_resolves_target_before_reading_source() {
        final var source = """
                var log = [];
                function target() { log.push('target'); return { set q(v) { log.push('set'); } }; }
                var source = { get p() { log.push('get'); } };
                ({ p: target().q } = source);
                JSON.stringify(log)
                """;
        assertEquals("[\"target\",\"get\",\"set\"]", str(source));
    }

    @Test
    public void test_object_rest_from_primitive_source() {
        assertEquals("[\"f\",\"o\",\"o\"]",
                str("var rest; ({...rest} = 'foo'); JSON.stringify([rest['0'], rest['1'], rest['2']])"));
        assertTrue(flag("var rest; ({...rest} = 51); rest instanceof Object"));
    }

    // Matches test262 object-rest-proxy-gopd-not-called-on-excluded-keys.js: the trap is never called
    // for a key an earlier pattern property already consumed.
    @Test
    public void test_object_rest_proxy_skips_excluded_keys() {
        final var source = """
                var gopdKeys = [];
                var proxy = new Proxy({}, {
                    ownKeys: function() { return ['excluded', 'included']; },
                    getOwnPropertyDescriptor: function(t, key) {
                        gopdKeys.push(key);
                        return { value: 1, enumerable: true, configurable: true };
                    },
                });
                var { excluded, ...rest } = proxy;
                JSON.stringify(gopdKeys)
                """;
        assertEquals("[\"included\"]", str(source));
    }
}
