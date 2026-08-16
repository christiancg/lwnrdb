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

    // A computed accessor key that evaluates to a Symbol installs a symbol-keyed accessor
    @Test
    public void test_object_literal_symbol_computed_accessor_get() {
        assertEquals(1, num("let o = { get [Symbol.iterator]() { return 1; } }; o[Symbol.iterator]"));
    }

    // A computed symbol setter accessor runs on write
    @Test
    public void test_object_literal_symbol_computed_accessor_set() {
        assertEquals(10,
                num("let v = 0; let o = { set [Symbol.iterator](n) { v = n * 2; } }; o[Symbol.iterator] = 5; v"));
    }

    // A throwing symbol-keyed getter propagates its original error, not a string-conversion TypeError
    @Test
    public void test_object_literal_symbol_computed_accessor_throwing_getter_propagates() {
        assertThrows(org.techhouse.simplejs.exceptions.JsThrowException.class, () -> Interpreter
                .run("let o = { get [Symbol.iterator]() { throw new RangeError('boom'); } }; o[Symbol.iterator]"));
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

    // A non-computed __proto__ key sets the prototype
    @Test
    public void test_object_literal_proto_key() {
        assertEquals("hi", str("const p = { greet() { return 'hi' } }; const o = { __proto__: p }; o.greet()"));
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter
                .run("const p = {}; Object.getPrototypeOf({ __proto__: p }) === p")).getValue());
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter
                .run("Object.getPrototypeOf({ __proto__: null }) === null")).getValue());
    }

    // A computed __proto__ key stays an own property
    @Test
    public void test_object_literal_computed_proto_key() {
        assertEquals("object", str("const p = {}; typeof ({ ['__proto__']: p }).__proto__"));
        assertTrue(((org.techhouse.simplejs.values.JsBoolean) Interpreter
                .run("const p = {}; Object.getPrototypeOf({ ['__proto__']: p }) === Object.prototype")).getValue());
    }

    // A __proto__ value that is neither object nor null is ignored
    @Test
    public void test_object_literal_proto_key_ignored() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("({ __proto__: 1 }).__proto__"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("({ __proto__: 'x' }).__proto__"));
    }

    // Assigning length truncates or grows an array
    @Test
    public void test_array_length_assignment() {
        assertEquals(1, num("const a = [1, 2, 3]; a.length = 1; a.length"));
        assertEquals(1, num("const a = [1, 2, 3]; a.length = 1; a[0]"));
        assertEquals(0, num("const a = [1, 2, 3]; a.length = 0; a.length"));
        assertEquals(3, num("const a = [1]; a.length = 3; a.length"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("const a = [1]; a.length = 3; a[2]"));
    }

    // Own string keys enumerate with the canonical integer indexes first, then insertion order
    @Test
    public void test_key_order_integer_first() {
        assertEquals("1,2,b,a", str("Object.keys({b: 1, 2: 2, a: 3, 1: 4}).join(',')"));
        assertEquals("1,2,b,a", str("Object.getOwnPropertyNames({b: 1, 2: 2, a: 3, 1: 4}).join(',')"));
        assertEquals("4,2,1,3", str("Object.values({b: 1, 2: 2, a: 3, 1: 4}).join(',')"));
        assertEquals("1,2,b,a", str("Reflect.ownKeys({b: 1, 2: 2, a: 3, 1: 4}).join(',')"));
    }

    // for-in follows the same ordering
    @Test
    public void test_forin_order_integer_first() {
        assertEquals("1,2,b,a",
                str("let r = []; for (const k in {b: 1, 2: 2, a: 3, 1: 4}) { r.push(k); } r.join(',')"));
    }

    // JSON.stringify emits the spec key order
    @Test
    public void test_json_stringify_key_order() {
        assertEquals("{\"1\":4,\"2\":2,\"b\":1,\"a\":3}", str("JSON.stringify({b: 1, 2: 2, a: 3, 1: 4})"));
    }

    // Object.assign and object spread copy in the spec key order
    @Test
    public void test_object_assign_and_spread_order() {
        assertEquals("1,2,b,a", str("Object.keys(Object.assign({}, {b: 1, 2: 2, a: 3, 1: 4})).join(',')"));
        assertEquals("1,2,b,a", str("Object.keys({...{b: 1, 2: 2, a: 3, 1: 4}}).join(',')"));
    }

    // A key that only looks like an index stays in the string bucket, in insertion order
    @Test
    public void test_non_canonical_index_keys_stay_strings() {
        assertEquals("1,01,-1,1.0,4294967295",
                str("Object.keys({'01': 1, '-1': 2, '1.0': 3, '4294967295': 4, 1: 5}).join(',')"));
        assertEquals("0,4294967294,x", str("Object.keys({x: 1, 4294967294: 2, 0: 3}).join(',')"));
    }

    // A rejected write is a TypeError, since the engine is always strict
    @Test
    public void test_strict_assign_rejections_throw() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = Object.freeze({a: 1}); o.a = 2"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = Object.freeze({a: 1}); o.a += 2"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = Object.freeze({a: 1}); o.a++"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const o = Object.preventExtensions({}); o.b = 1"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = {get v() { return 1; }}; o.v = 2"));
    }

    // A rejected delete is a TypeError too
    @Test
    public void test_strict_delete_nonconfigurable_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = Object.freeze({a: 1}); delete o.a"));
        assertTrue(flag("const o = {a: 1}; delete o.b"));
    }

    // Reflect reports the same rejections as booleans instead of throwing
    @Test
    public void test_reflect_reports_rejection_as_false() {
        assertTrue(flag("const o = Object.freeze({a: 1}); Reflect.set(o, 'a', 2) === false"));
        assertTrue(flag("const o = Object.freeze({a: 1}); Reflect.deleteProperty(o, 'a') === false"));
        assertTrue(flag("const o = {}; Reflect.set(o, 'a', 2) === true"));
    }

    // freeze and friends reach arrays as well as plain objects
    @Test
    public void test_freeze_array() {
        assertTrue(flag("Object.isFrozen(Object.freeze([1]))"));
        assertTrue(flag("!Object.isFrozen([1])"));
        assertTrue(flag("!Object.isExtensible(Object.freeze([1]))"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const a = Object.freeze([1]); a[0] = 9"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const a = Object.freeze([1]); a.length = 0"));
        assertEquals(1, num("const a = Object.freeze([1]); try { a.push(2); } catch (e) { } a.length"));
    }

    // preventExtensions on an array rejects a new index but keeps the existing ones writable
    @Test
    public void test_prevent_extensions_array() {
        assertEquals(9, num("const a = Object.preventExtensions([1]); a[0] = 9; a[0]"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const a = Object.preventExtensions([1]); a[1] = 9"));
        assertTrue(flag("Object.isSealed(Object.seal([1]))"));
        assertTrue(flag("!Object.isFrozen(Object.seal([1]))"));
        assertTrue(flag("Object.isFrozen(Object.preventExtensions([]))"));
    }

    // An invalid length is rejected
    @Test
    public void test_array_length_assignment_range() {
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("const a = [1]; a.length = -1"));
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("const a = [1]; a.length = 1.5"));
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("const a = [1]; a.length = NaN"));
    }

    // A shorthand method in an object literal has a home object, so it may call super
    @Test
    public void test_object_literal_super() {
        assertEquals("po", str(
                "const p = { m() { return 'p'; } }; const o = { __proto__: p, m() { return super.m() + 'o'; } }; o.m()"));
        assertEquals(2, num(
                "const p = { get v() { return 1; } }; const o = { __proto__: p, get v() { return super.v + 1; } }; o.v"));
        assertEquals("p", str(
                "const p = { m() { return 'p'; } }; const o = { __proto__: p, m() { const g = () => super.m(); return g(); } }; o.m()"));
    }

    // super with no home object at all is still a syntax error
    @Test
    public void test_super_without_home_still_syntax_error() {
        assertThrows(org.techhouse.simplejs.exceptions.SyntaxErrorException.class,
                () -> Interpreter.run("function f() { return super.x; } f()"));
        assertThrows(org.techhouse.simplejs.exceptions.SyntaxErrorException.class,
                () -> Interpreter.run("const o = { m: function() { return super.x; } }; o.m()"));
    }

    // object spread copies the getter's value
    @Test
    public void test_spread_invokes_getter() {
        assertEquals(1, num("({...{get x() { return 1; }}}).x"));
    }

    // rest destructuring copies the getter's value
    @Test
    public void test_rest_destructuring_invokes_getter() {
        assertEquals(2, num("const {a, ...rest} = {a: 1, get b() { return 2; }}; rest.b"));
    }

    // for-in visits an enumerable accessor key
    @Test
    public void test_for_in_lists_accessor() {
        assertEquals("x", str("let out = ''; for (const k in {get x() { return 1; }}) out += k; out"));
    }

    // the `in` operator walks the prototype chain, not just own properties
    @Test
    public void test_in_operator_walks_prototype_chain() {
        assertTrue(flag("'foo' in Object.create({foo: 1})"));
        assertTrue(flag("'foo' in Object.create({get foo() { return 1; }})"));
        assertTrue(flag("'foo' in {foo: 1}"));
        assertFalse(flag("'bar' in Object.create({foo: 1})"));
    }

    // Object.defineProperty/defineProperties read a descriptor argument's get/set/value/writable
    // fields via HasProperty+Get (honouring an inherited accessor on the descriptor object), not
    // just its own properties
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

    // an own accessor with only a setter (no getter) terminates property lookup at that level -
    // reading it yields undefined rather than falling through to an inherited getter of the same name
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

    // redefining an accessor property with a data descriptor (no get/set fields) must drop the
    // stale getter, or a later read still finds the old accessor instead of the new value
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

    // redefining only one of get/set on an existing accessor must preserve the untouched side,
    // and an explicit `get: undefined` must actually clear the prior getter rather than leaving it
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
}
