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

    @Test
    public void test_get() {
        assertEquals(5, num("Reflect.get({ a: 5 }, 'a')"));
    }

    @Test
    public void test_set() {
        assertEquals(9, num("const o = {}; Reflect.set(o, 'x', 9); o.x"));
        assertTrue(bool("Reflect.set({}, 'x', 1)"));
    }

    @Test
    public void test_has() {
        assertTrue(bool("Reflect.has({ a: 1 }, 'a')"));
        assertFalse(bool("Reflect.has({ a: 1 }, 'b')"));
    }

    @Test
    public void test_delete_property() {
        assertFalse(bool("const o = { a: 1 }; Reflect.deleteProperty(o, 'a'); 'a' in o"));
    }

    @Test
    public void test_own_keys() {
        assertEquals("a,b", str("Reflect.ownKeys({ a: 1, b: 2 }).join(',')"));
    }

    @Test
    public void test_own_keys_array() {
        assertEquals("0,1,length", str("Reflect.ownKeys([10, 20]).join(',')"));
    }

    @Test
    public void test_delete_property_array() {
        assertTrue(bool("Reflect.deleteProperty([1, 2, 3], '0')"));
    }

    @Test
    public void test_delete_property_proxy() {
        assertFalse(bool("const t = { a: 1 }; const p = new Proxy(t, {}); Reflect.deleteProperty(p, 'a'); 'a' in t"));
    }

    @Test
    public void test_apply_missing_args_list() {
        assertEquals("TypeError", str("let name = 'none';"
                + "try { Reflect.apply(function () { return 42; }, null); } catch (e) { name = e.name; } name"));
    }

    @Test
    public void test_get_missing_key() {
        assertTrue(bool("Reflect.get({}) === undefined"));
    }

    @Test
    public void test_apply() {
        assertEquals(6, num("Reflect.apply(function (a, b) { return a + b; }, null, [2, 4])"));
        assertEquals(3, num("Reflect.apply(function () { return this.n; }, { n: 3 }, [])"));
    }

    @Test
    public void test_construct() {
        final var source = """
                class Point { constructor(x, y) { this.x = x; this.y = y; } }
                const p = Reflect.construct(Point, [3, 4]);
                p.x + p.y
                """;
        assertEquals(7, num(source));
    }

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

    @Test
    public void test_define_property() {
        final var source = """
                const o = {};
                const ok = Reflect.defineProperty(o, 'x', { value: 42, enumerable: false });
                ok && o.x === 42 && Object.keys(o).length === 0
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_define_property_returns_false() {
        final var source = """
                const o = {};
                Reflect.defineProperty(o, 'x', { value: 1, configurable: false });
                Reflect.defineProperty(o, 'x', { value: 2, configurable: true })
                """;
        assertFalse(bool(source));
    }

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

    @Test
    public void test_reflect_extensibility() {
        assertTrue(bool("Reflect.isExtensible({})"));
        assertFalse(bool("let o = {}; Reflect.preventExtensions(o); Reflect.isExtensible(o)"));
    }

    @Test
    public void test_reflect_get_prototype_of() {
        assertTrue(bool("let p = {}; let o = Object.create(p); Reflect.getPrototypeOf(o) === p"));
    }

    @Test
    public void test_get_with_receiver() {
        assertEquals(42, num("let t = { get x() { return this.y; } }; let r = { y: 42 }; Reflect.get(t, 'x', r)"));
    }

    @Test
    public void test_set_with_receiver() {
        assertEquals(9, num("let t = { set x(v) { this._w = v; } }; let r = {}; Reflect.set(t, 'x', 9, r); r._w"));
    }

    @Test
    public void test_construct_rejects_non_constructor_target() {
        assertEquals("TypeError",
                str("let n = 'none';" + "try { Reflect.construct(Math.max, []); } catch (e) { n = e.name; } n"));
    }

    @Test
    public void test_construct_rejects_non_constructor_new_target() {
        assertEquals("TypeError", str("let n = 'none';"
                + "try { Reflect.construct(function () {}, [], Math.max); } catch (e) { n = e.name; } n"));
    }

    @Test
    public void test_construct_defaults_new_target_to_target() {
        assertTrue(bool("function F() {} Reflect.construct(F, []) instanceof F"));
    }

    @Test
    public void test_construct_derives_proto_from_new_target() {
        assertTrue(bool("function F() {} function G() {} Reflect.construct(F, [], G) instanceof G"));
    }

    @Test
    public void test_construct_accepts_array_like_arguments_list() {
        assertEquals(3,
                num("function F(a, b) { this.sum = a + b; }" + "Reflect.construct(F, { length: 2, 0: 1, 1: 2 }).sum"));
    }

    @Test
    public void test_construct_throws_on_non_object_arguments_list() {
        assertEquals("TypeError",
                str("let n = 'none';" + "try { Reflect.construct(function () {}, 1); } catch (e) { n = e.name; } n"));
    }

    @Test
    public void test_apply_accepts_array_like_arguments_list() {
        assertEquals(3, num("Reflect.apply(function (a, b) { return a + b; }, null, { length: 2, 0: 1, 1: 2 })"));
    }

    @Test
    public void test_non_object_target_is_a_type_error() {
        final var methods = "get,set,has,deleteProperty,ownKeys,getPrototypeOf,setPrototypeOf,isExtensible,"
                + "preventExtensions,defineProperty,getOwnPropertyDescriptor";
        assertEquals("", str("""
                let bad = "";
                for (const name of "%s".split(",")) {
                    try { Reflect[name](1, "x", {}); bad += name + " "; } catch (e) {
                        if (!(e instanceof TypeError)) { bad += name + "! "; }
                    }
                }
                bad
                """.formatted(methods)));
    }

    @Test
    public void test_property_key_coercion_is_observable() {
        assertEquals("boom", str("""
                const key = { toString() { throw new Error("boom"); } };
                let message = "none";
                try { Reflect.get({}, key); } catch (e) { message = e.message; }
                message
                """));
    }

    @Test
    public void test_set_prototype_of_returns_false_rather_than_throwing() {
        assertFalse(bool("const o = {}; const child = Object.create(o); Reflect.setPrototypeOf(o, child)"));
        assertFalse(bool("const o = Object.preventExtensions({}); Reflect.setPrototypeOf(o, { a: 1 })"));
        assertTrue(bool("const o = Object.preventExtensions(Object.create(null)); Reflect.setPrototypeOf(o, null)"));
    }

    @Test
    public void test_set_prototype_of_rejects_a_primitive_prototype() {
        assertEquals("TypeError",
                str("let n = 'none'; try { Reflect.setPrototypeOf({}, 1); } catch (e) { n = e.name; } n"));
    }

    @Test
    public void test_set_with_a_receiver_writes_to_the_receiver() {
        assertEquals("42:1", str("""
                const target = { p: 42 };
                const receiver = {};
                Reflect.set(target, "p", 1, receiver);
                target.p + ":" + receiver.p
                """));
    }

    @Test
    public void test_set_with_a_receiver_reports_a_refusal() {
        assertFalse(bool("""
                const receiver = {};
                Object.defineProperty(receiver, "p", { set(v) {} });
                Reflect.set({}, "p", 1, receiver)
                """));
        assertFalse(bool("""
                const receiver = {};
                Object.defineProperty(receiver, "p", { value: 1, writable: false });
                Reflect.set({}, "p", 2, receiver)
                """));
        assertFalse(bool("Reflect.set({ p: 1 }, 'p', 2, 'not an object')"));
    }

    @Test
    public void test_set_with_a_receiver_runs_an_inherited_setter() {
        assertEquals(5, num("""
                const proto = { set p(value) { this.stored = value; } };
                const target = Object.create(proto);
                const receiver = {};
                Reflect.set(target, "p", 5, receiver);
                receiver.stored
                """));
    }

    @Test
    public void test_set_with_a_receiver_honours_a_non_writable_target() {
        assertFalse(bool("""
                const target = {};
                Object.defineProperty(target, "p", { value: 1, writable: false });
                Reflect.set(target, "p", 2, {})
                """));
    }

    // An invalid canonical numeric index on a typed array is a silent success that never coerces the value
    // and never walks the prototype chain (10.4.5.5 [[Set]] step 3.b.ii).
    @Test
    public void test_set_typed_array_invalid_index_short_circuits() {
        assertTrue(bool("""
                let reached = false;
                Object.defineProperty(Int32Array.prototype, "100", {
                    set() { reached = true; },
                    configurable: true
                });
                const target = new Int32Array(1);
                const receiver = {};
                const ok = Reflect.set(target, 100, { valueOf() { reached = true; return 1; } }, receiver);
                delete Int32Array.prototype["100"];
                ok && !reached && !receiver.hasOwnProperty("100")
                """));
    }

    @Test
    public void test_set_typed_array_valid_index_with_foreign_receiver() {
        assertEquals("5:7", str("""
                const target = new Int32Array(1);
                target[0] = 5;
                const receiver = {};
                Reflect.set(target, 0, 7, receiver);
                target[0] + ":" + receiver[0]
                """));
    }
}
