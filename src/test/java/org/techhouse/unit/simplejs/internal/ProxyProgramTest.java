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

public class ProxyProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // A get trap intercepts every property read on the proxy
    @Test
    public void test_get_trap() {
        final var source = """
                const p = new Proxy({}, { get(target, key) { return "got:" + key; } });
                p.anything
                """;
        assertEquals("got:anything", str(source));
    }

    // Absent get trap falls through to the target
    @Test
    public void test_get_falls_through_to_target() {
        assertEquals(7, num("const p = new Proxy({ a: 7 }, {}); p.a"));
    }

    // A set trap intercepts writes and can redirect them
    @Test
    public void test_set_trap() {
        final var source = """
                const log = {};
                const p = new Proxy({}, { set(target, key, value) { log[key] = value * 2; return true; } });
                p.x = 5;
                log.x
                """;
        assertEquals(10, num(source));
    }

    // Absent set trap writes through to the target
    @Test
    public void test_set_falls_through_to_target() {
        assertEquals(3, num("const t = {}; const p = new Proxy(t, {}); p.v = 3; t.v"));
    }

    // A has trap drives the `in` operator
    @Test
    public void test_has_trap_drives_in() {
        final var source = """
                const p = new Proxy({}, { has(target, key) { return key === "magic"; } });
                ("magic" in p) && !("other" in p)
                """;
        assertTrue(bool(source));
    }

    // Absent has trap falls through to the target for `in`
    @Test
    public void test_has_falls_through() {
        assertTrue(bool("const p = new Proxy({ a: 1 }, {}); 'a' in p"));
    }

    // A deleteProperty trap drives `delete`
    @Test
    public void test_delete_trap() {
        final var source = """
                let deleted = null;
                const p = new Proxy({}, { deleteProperty(target, key) { deleted = key; return true; } });
                delete p.gone;
                deleted
                """;
        assertEquals("gone", str(source));
    }

    // Absent deleteProperty trap deletes on the target
    @Test
    public void test_delete_falls_through() {
        assertFalse(bool("const t = { a: 1 }; const p = new Proxy(t, {}); delete p.a; 'a' in t"));
    }

    // An ownKeys trap drives Object.keys and for-in
    @Test
    public void test_own_keys_trap() {
        final var source = """
                const p = new Proxy({}, { ownKeys(target) { return ["a", "b", "c"]; } });
                let count = 0;
                for (const k in p) { count++; }
                Object.keys(p).length + count
                """;
        assertEquals(6, num(source));
    }

    // An apply trap intercepts calls on a callable proxy
    @Test
    public void test_apply_trap() {
        final var source = """
                const p = new Proxy(function () {}, { apply(target, thisArg, args) { return args[0] + args[1]; } });
                p(3, 4)
                """;
        assertEquals(7, num(source));
    }

    // Absent apply trap calls through to the target function
    @Test
    public void test_apply_falls_through() {
        assertEquals(9, num("const p = new Proxy(function (x) { return x + 1; }, {}); p(8)"));
    }

    // A construct trap intercepts `new` on a callable proxy
    @Test
    public void test_construct_trap() {
        final var source = """
                const p = new Proxy(function () {}, {
                    construct(target, args) { return { total: args[0] + args[1] }; }
                });
                new p(10, 20).total
                """;
        assertEquals(30, num(source));
    }

    // Absent construct trap constructs the target
    @Test
    public void test_construct_falls_through() {
        final var source = """
                function F(x) { this.x = x; }
                const p = new Proxy(F, {});
                new p(42).x
                """;
        assertEquals(42, num(source));
    }

    // Creating a proxy with a non-object target or handler throws a TypeError
    @Test
    public void test_bad_arguments_throw() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Proxy(1, {})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Proxy({}, 2)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Proxy()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Proxy({})"));
    }

    // A non-function trap value throws a TypeError when the trapped operation runs
    @Test
    public void test_non_function_trap_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const p = new Proxy({}, { get: 5 }); p.x"));
    }

    // Object destructuring reads proxy members through the get trap
    @Test
    public void test_destructuring_get_trap() {
        assertEquals(9, num("const p = new Proxy({}, { get(t, k) { return 9; } }); const { a } = p; a"));
    }

    // Destructuring assignment writes proxy members through the set trap
    @Test
    public void test_destructuring_assignment_set_trap() {
        final var source = """
                let seen = null;
                const p = new Proxy({}, { set(t, k, v) { seen = v; return true; } });
                [p.x] = [5];
                seen
                """;
        assertEquals(5, num(source));
    }

    // Without an ownKeys trap, for-in enumerates the target's own keys
    @Test
    public void test_own_keys_falls_through() {
        final var source = """
                const p = new Proxy({ a: 1, b: 2 }, {});
                let count = 0;
                for (const k in p) { count++; }
                count
                """;
        assertEquals(2, num(source));
    }

    // A non-array ownKeys trap result yields no enumerated keys
    @Test
    public void test_own_keys_non_array_result() {
        final var source = """
                const p = new Proxy({ a: 1 }, { ownKeys(t) { return 42; } });
                let count = 0;
                for (const k in p) { count++; }
                count
                """;
        assertEquals(0, num(source));
    }

    // Nested proxies chain their traps
    @Test
    public void test_nested_proxy() {
        final var source = """
                const inner = new Proxy({ v: 1 }, { get(t, k) { return t[k] + 10; } });
                const outer = new Proxy(inner, {});
                outer.v
                """;
        assertEquals(11, num(source));
    }

    // getPrototypeOf trap intercepts Object.getPrototypeOf; absent trap falls through to the target
    @Test
    public void test_proxy_get_prototype_of() {
        assertEquals("P", str("""
                let proto = { tag: 'P' };
                let p = new Proxy({}, { getPrototypeOf(t) { return proto; } });
                Object.getPrototypeOf(p).tag
                """));
        assertTrue(bool("""
                let proto = {};
                let t = Object.create(proto);
                let p = new Proxy(t, {});
                Object.getPrototypeOf(p) === proto
                """));
    }

    // isExtensible / preventExtensions traps are consulted by the Object.* operations
    @Test
    public void test_proxy_extensibility_traps() {
        assertFalse(bool("let p = new Proxy({}, { isExtensible(t) { return false; } }); Object.isExtensible(p)"));
        assertTrue(bool("""
                let hit = false;
                let p = new Proxy({}, { preventExtensions(t) { hit = true; return true; } });
                Object.preventExtensions(p);
                hit
                """));
    }

    // defineProperty and getOwnPropertyDescriptor traps intercept the reflective operations
    @Test
    public void test_proxy_define_and_descriptor_traps() {
        assertEquals("x", str("""
                let log = [];
                let p = new Proxy({}, { defineProperty(t, k, d) { log.push(k); return true; } });
                Object.defineProperty(p, 'x', { value: 1 });
                log[0]
                """));
        assertEquals(42, num("""
                let p = new Proxy({}, {
                    getOwnPropertyDescriptor(t, k) { return { value: 42, configurable: true }; }
                });
                Object.getOwnPropertyDescriptor(p, 'x').value
                """));
    }

    // Proxy.revocable returns a proxy usable until revoke(), after which any operation throws
    @Test
    public void test_proxy_revocable() {
        assertEquals(1,
                num("let { proxy, revoke } = Proxy.revocable({ x: 1 }, {}); let before = proxy.x; revoke(); before"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let r = Proxy.revocable({ x: 1 }, {}); r.revoke(); r.proxy.x"));
    }
}
