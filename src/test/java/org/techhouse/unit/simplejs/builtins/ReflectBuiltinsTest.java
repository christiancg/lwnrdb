package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class ReflectBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Reflect.get reads a property from a target
    @Test
    public void test_get() {
        assertEquals(5, num("Reflect.get({ a: 5 }, 'a')"));
    }

    // Reflect.set writes a property and returns true
    @Test
    public void test_set() {
        assertEquals(9, num("const o = {}; Reflect.set(o, 'x', 9); o.x"));
        assertTrue(bool("Reflect.set({}, 'x', 1)"));
    }

    // Reflect.has mirrors the `in` operator
    @Test
    public void test_has() {
        assertTrue(bool("Reflect.has({ a: 1 }, 'a')"));
        assertFalse(bool("Reflect.has({ a: 1 }, 'b')"));
    }

    // Reflect.deleteProperty removes a property and returns true
    @Test
    public void test_delete_property() {
        assertFalse(bool("const o = { a: 1 }; Reflect.deleteProperty(o, 'a'); 'a' in o"));
    }

    // Reflect.ownKeys lists a target's own keys
    @Test
    public void test_own_keys() {
        assertEquals("a,b", str("Reflect.ownKeys({ a: 1, b: 2 }).join(',')"));
    }

    // Reflect.ownKeys on an array reports its indices plus length
    @Test
    public void test_own_keys_array() {
        assertEquals("0,1,length", str("Reflect.ownKeys([10, 20]).join(',')"));
    }

    // Reflect.deleteProperty removes an array element by index
    @Test
    public void test_delete_property_array() {
        assertTrue(bool("Reflect.deleteProperty([1, 2, 3], '0')"));
    }

    // Reflect.deleteProperty falls through a proxy without a deleteProperty trap
    @Test
    public void test_delete_property_proxy() {
        assertFalse(bool("const t = { a: 1 }; const p = new Proxy(t, {}); Reflect.deleteProperty(p, 'a'); 'a' in t"));
    }

    // CreateListFromArrayLike rejects a missing (non-object) arguments list
    @Test
    public void test_apply_missing_args_list() {
        assertEquals("TypeError", str("let name = 'none';"
                + "try { Reflect.apply(function () { return 42; }, null); } catch (e) { name = e.name; } name"));
    }

    // Reflect.get with a missing key reads the "undefined" property
    @Test
    public void test_get_missing_key() {
        assertTrue(bool("Reflect.get({}) === undefined"));
    }

    // Reflect.apply invokes a function with an explicit this and argument list
    @Test
    public void test_apply() {
        assertEquals(6, num("Reflect.apply(function (a, b) { return a + b; }, null, [2, 4])"));
        assertEquals(3, num("Reflect.apply(function () { return this.n; }, { n: 3 }, [])"));
    }

    // Reflect.construct builds an instance from a constructor and argument list
    @Test
    public void test_construct() {
        final var source = """
                class Point { constructor(x, y) { this.x = x; this.y = y; } }
                const p = Reflect.construct(Point, [3, 4]);
                p.x + p.y
                """;
        assertEquals(7, num(source));
    }

    // Reflect.getPrototypeOf / setPrototypeOf read and replace the prototype link
    @Test
    public void test_prototype_ops() {
        final var source = """
                const proto = { greet() { return "hi"; } };
                const o = {};
                Reflect.setPrototypeOf(o, proto);
                (Reflect.getPrototypeOf(o) === proto) && o.greet() === "hi"
                """;
        assertTrue(bool(source));
    }

    // Reflect.defineProperty installs a descriptor and returns true
    @Test
    public void test_define_property() {
        final var source = """
                const o = {};
                const ok = Reflect.defineProperty(o, 'x', { value: 42, enumerable: false });
                ok && o.x === 42 && Object.keys(o).length === 0
                """;
        assertTrue(bool(source));
    }

    // Reflect.defineProperty returns false instead of throwing on an illegal redefine
    @Test
    public void test_define_property_returns_false() {
        final var source = """
                const o = {};
                Reflect.defineProperty(o, 'x', { value: 1, configurable: false });
                Reflect.defineProperty(o, 'x', { value: 2, configurable: true })
                """;
        assertFalse(bool(source));
    }

    // Reflect.getOwnPropertyDescriptor reports the real descriptor flags
    @Test
    public void test_get_own_property_descriptor() {
        final var source = """
                const o = {};
                Object.defineProperty(o, 'x', { value: 1, writable: false, enumerable: true, configurable: false });
                const d = Reflect.getOwnPropertyDescriptor(o, 'x');
                (d.value === 1) && !d.writable && d.enumerable && !d.configurable
                """;
        assertTrue(bool(source));
    }

    // Reflect.isExtensible / preventExtensions mirror the Object.* extensibility state
    @Test
    public void test_reflect_extensibility() {
        assertTrue(bool("Reflect.isExtensible({})"));
        assertFalse(bool("let o = {}; Reflect.preventExtensions(o); Reflect.isExtensible(o)"));
    }

    // Reflect.getPrototypeOf reports the object's prototype
    @Test
    public void test_reflect_get_prototype_of() {
        assertTrue(bool("let p = {}; let o = Object.create(p); Reflect.getPrototypeOf(o) === p"));
    }

    // Reflect.get invokes a getter with the supplied receiver
    @Test
    public void test_get_with_receiver() {
        assertEquals(42, num("let t = { get x() { return this.y; } }; let r = { y: 42 }; Reflect.get(t, 'x', r)"));
    }

    // Reflect.set invokes a setter with the supplied receiver
    @Test
    public void test_set_with_receiver() {
        assertEquals(9, num("let t = { set x(v) { this._w = v; } }; let r = {}; Reflect.set(t, 'x', 9, r); r._w"));
    }

    // Reflect.construct rejects a target without [[Construct]]
    @Test
    public void test_construct_rejects_non_constructor_target() {
        assertEquals("TypeError",
                str("let n = 'none';" + "try { Reflect.construct(Math.max, []); } catch (e) { n = e.name; } n"));
    }

    // Reflect.construct rejects a newTarget without [[Construct]]
    @Test
    public void test_construct_rejects_non_constructor_new_target() {
        assertEquals("TypeError", str("let n = 'none';"
                + "try { Reflect.construct(function () {}, [], Math.max); } catch (e) { n = e.name; } n"));
    }

    // An omitted newTarget defaults to the target itself
    @Test
    public void test_construct_defaults_new_target_to_target() {
        assertTrue(bool("function F() {} Reflect.construct(F, []) instanceof F"));
    }

    // The created instance's prototype comes from Get(newTarget, "prototype")
    @Test
    public void test_construct_derives_proto_from_new_target() {
        assertTrue(bool("function F() {} function G() {} Reflect.construct(F, [], G) instanceof G"));
    }

    // CreateListFromArrayLike walks any object by length + indexed Get
    @Test
    public void test_construct_accepts_array_like_arguments_list() {
        assertEquals(3,
                num("function F(a, b) { this.sum = a + b; }" + "Reflect.construct(F, { length: 2, 0: 1, 1: 2 }).sum"));
    }

    // A non-object arguments list is a TypeError, not an empty list
    @Test
    public void test_construct_throws_on_non_object_arguments_list() {
        assertEquals("TypeError",
                str("let n = 'none';" + "try { Reflect.construct(function () {}, 1); } catch (e) { n = e.name; } n"));
    }

    // Reflect.apply accepts the same array-like arguments list
    @Test
    public void test_apply_accepts_array_like_arguments_list() {
        assertEquals(3, num("Reflect.apply(function (a, b) { return a + b; }, null, { length: 2, 0: 1, 1: 2 })"));
    }
}
