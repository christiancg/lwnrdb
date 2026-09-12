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

public class ProxyPrototypeProgramTest {
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
    public void test_has_trap_drives_in() {
        final var source = """
                const p = new Proxy({}, { has(target, key) { return key === "magic"; } });
                ("magic" in p) && !("other" in p)
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_has_falls_through() {
        assertTrue(bool("const p = new Proxy({ a: 1 }, {}); 'a' in p"));
    }

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

    @Test
    public void test_delete_falls_through() {
        assertFalse(bool("const t = { a: 1 }; const p = new Proxy(t, {}); delete p.a; 'a' in t"));
    }

    @Test
    public void test_own_keys_trap() {
        final var source = """
                const p = new Proxy({}, {
                    ownKeys(target) { return ["a", "b", "c"]; },
                    getOwnPropertyDescriptor(target, key) { return { value: 1, enumerable: true, configurable: true }; }
                });
                let count = 0;
                for (const k in p) { count++; }
                Object.keys(p).length + count
                """;
        assertEquals(6, num(source));
    }

    @Test
    public void test_own_keys_trap_filters_non_enumerable() {
        final var source = """
                const p = new Proxy({}, {
                    ownKeys(target) { return ["a", "b"]; },
                    getOwnPropertyDescriptor(target, key) {
                        return { value: 1, enumerable: key === "a", configurable: true };
                    }
                });
                let count = 0;
                for (const k in p) { count++; }
                Object.keys(p).length + count
                """;
        assertEquals(2, num(source));
    }

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

    @Test
    public void test_own_keys_non_array_result() {
        final var source = """
                const p = new Proxy({ a: 1 }, { ownKeys(t) { return 42; } });
                let count = 0;
                for (const k in p) { count++; }
                count
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

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

    @Test
    public void test_proto_proxy_get_trap() {
        final var source = """
                const p = new Proxy({}, { get(target, key, receiver) { return key + ':' + (receiver === o); } });
                const o = Object.create(p);
                o.x
                """;
        assertEquals("x:true", str(source));
    }

    @Test
    public void test_proto_proxy_get_trap_undefined_ends_walk() {
        final var source = """
                Object.prototype.shared = 'from Object.prototype';
                const p = new Proxy({}, { get() { return undefined; } });
                const o = Object.create(p);
                typeof o.shared
                """;
        assertEquals("undefined", str(source));
    }

    @Test
    public void test_own_property_beats_proto_proxy() {
        final var source = """
                const p = new Proxy({}, { get() { return 'trap'; } });
                const o = Object.create(p);
                o.x = 'own';
                o.x
                """;
        assertEquals("own", str(source));
    }

    @Test
    public void test_proto_proxy_has_trap() {
        final var source = """
                const p = new Proxy({}, { has(target, key) { return key === 'yes'; } });
                const o = Object.create(p);
                ('yes' in o) && !('no' in o)
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_proto_proxy_set_trap() {
        final var source = """
                let seen = '';
                const p = new Proxy({}, {
                    set(target, key, value, receiver) { seen = key + '=' + value + ':' + (receiver === o); return true; }
                });
                const o = Object.create(p);
                o.y = 7;
                seen
                """;
        assertEquals("y=7:true", str(source));
    }

    @Test
    public void test_proto_proxy_set_trap_false_throws() {
        final var source = """
                const p = new Proxy({}, { set() { return false; } });
                const o = Object.create(p);
                o.z = 1;
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_proto_proxy_symbol_get_trap() {
        final var source = """
                const s = Symbol('tag');
                const p = new Proxy({}, { get(target, key) { return key === s ? 'sym' : 'other'; } });
                const o = Object.create(p);
                o[s]
                """;
        assertEquals("sym", str(source));
    }

    @Test
    public void test_proto_proxy_symbol_has_trap() {
        final var source = """
                const s = Symbol('tag');
                const p = new Proxy({}, { has(target, key) { return key === s; } });
                const o = Object.create(p);
                (s in o) && !(Symbol('other') in o)
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_proto_proxy_without_trap_reads_target() {
        final var source = """
                const p = new Proxy({ inherited: 42 }, {});
                const o = Object.create(p);
                o.inherited
                """;
        assertEquals(42d, num(source));
    }

    @Test
    public void test_own_keys_result_is_validated() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.keys(new Proxy({}, { ownKeys: () => ['a', 'a'] }))"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.keys(new Proxy({}, { ownKeys: () => [1] }))"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let t = {}; Object.defineProperty(t, 'a', { value: 1, configurable: false });"
                        + "Object.getOwnPropertyNames(new Proxy(t, { ownKeys: () => [] }))"));
    }

    @Test
    public void test_symbol_keys_in_the_has_trap() {
        assertTrue(bool("""
                let seen;
                let p = new Proxy({}, { has(t, k) { seen = k; return true; } });
                let ok = Symbol.iterator in p;
                ok && seen === Symbol.iterator
                """));
    }

    @Test
    public void test_numeric_key_is_converted_to_a_string_before_set_and_has_traps() {
        assertTrue(bool("""
                let setKey, hasKey;
                let p = new Proxy({}, {
                    set(t, k, v) { setKey = k; return true; },
                    has(t, k) { hasKey = k; return true; }
                });
                p[10] = 'x';
                let seen = 10 in p;
                seen && setKey === '10' && hasKey === '10'
                """));
    }

    // GetFunctionRealm on a newTarget that is a revoked Proxy is a TypeError, not a silent fallback to the
    // default prototype.
    @Test
    public void test_construct_with_newtarget_revoked_during_prototype_read_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                let handle = Proxy.revocable(function() {}, { get: function() { handle.revoke(); } });
                new handle.proxy();
                """));
    }
}
