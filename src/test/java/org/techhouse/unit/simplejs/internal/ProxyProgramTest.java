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

    // An ownKeys trap drives Object.keys and for-in; enumerable descriptors keep every key
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

    // Object.keys / for-in filter ownKeys-trap keys down to enumerable ones
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

    // CreateListFromArrayLike rejects a non-object ownKeys trap result
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

    // isExtensible / preventExtensions traps are consulted by the Object.* operations, but each
    // result has to agree with the target's own extensibility
    @Test
    public void test_proxy_extensibility_traps() {
        assertFalse(bool("""
                let t = Object.preventExtensions({});
                let p = new Proxy(t, { isExtensible(target) { return false; } });
                Object.isExtensible(p)
                """));
        assertTrue(bool("""
                let hit = false;
                let t = {};
                let p = new Proxy(t, {
                    preventExtensions(target) { hit = true; Object.preventExtensions(target); return true; }
                });
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

    // A trap-less proxy runs a target getter with the proxy as the receiver
    @Test
    public void test_get_accessor_receiver_is_proxy() {
        final var source = """
                const t = { get x() { return this === p ? 'proxy' : 'target'; } };
                const p = new Proxy(t, {});
                p.x
                """;
        assertEquals("proxy", str(source));
    }

    // A trap-less proxy runs a target setter with the proxy as the receiver
    @Test
    public void test_set_accessor_receiver_is_proxy() {
        final var source = """
                let seen = 'none';
                const t = { set x(v) { seen = this === p ? 'proxy' : 'target'; } };
                const p = new Proxy(t, {});
                p.x = 1;
                seen
                """;
        assertEquals("proxy", str(source));
    }

    // Object.keys drops non-enumerable keys returned by an ownKeys trap
    @Test
    public void test_object_keys_filters_proxy_non_enumerable() {
        final var source = """
                const t = {};
                Object.defineProperty(t, 'a', { value: 1, enumerable: true });
                Object.defineProperty(t, 'b', { value: 2, enumerable: false });
                const p = new Proxy(t, {});
                Object.keys(p).join(',')
                """;
        assertEquals("a", str(source));
    }

    // for-in drops non-enumerable keys returned by an ownKeys trap
    @Test
    public void test_for_in_filters_proxy_non_enumerable() {
        final var source = """
                const t = {};
                Object.defineProperty(t, 'a', { value: 1, enumerable: true });
                Object.defineProperty(t, 'b', { value: 2, enumerable: false });
                const p = new Proxy(t, {});
                let out = [];
                for (const k in p) { out.push(k); }
                out.join(',')
                """;
        assertEquals("a", str(source));
    }

    // A proxy reached as a [[Prototype]] dispatches its get trap, with the original object as receiver
    @Test
    public void test_proto_proxy_get_trap() {
        final var source = """
                const p = new Proxy({}, { get(target, key, receiver) { return key + ':' + (receiver === o); } });
                const o = Object.create(p);
                o.x
                """;
        assertEquals("x:true", str(source));
    }

    // A get trap on a prototype proxy answers even when it yields undefined, ending the walk
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

    // An own property still wins over a prototype proxy's get trap
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

    // `in` walks into a prototype proxy's has trap
    @Test
    public void test_proto_proxy_has_trap() {
        final var source = """
                const p = new Proxy({}, { has(target, key) { return key === 'yes'; } });
                const o = Object.create(p);
                ('yes' in o) && !('no' in o)
                """;
        assertTrue(bool(source));
    }

    // A write on an object whose prototype is a proxy goes through the set trap with the receiver
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

    // A prototype proxy whose set trap returns false rejects the write, which throws in strict mode
    @Test
    public void test_proto_proxy_set_trap_false_throws() {
        final var source = """
                const p = new Proxy({}, { set() { return false; } });
                const o = Object.create(p);
                o.z = 1;
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // A symbol-keyed read walks into a prototype proxy's get trap
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

    // A symbol-keyed `in` walks into a prototype proxy's has trap
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

    // A prototype proxy with no trap of its own falls through to its target's properties
    @Test
    public void test_proto_proxy_without_trap_reads_target() {
        final var source = """
                const p = new Proxy({ inherited: 42 }, {});
                const o = Object.create(p);
                o.inherited
                """;
        assertEquals(42d, num(source));
    }

    // An inherited setter still runs when the chain holds no proxy
    @Test
    public void test_inherited_setter_without_proxy() {
        final var source = """
                let seen = 0;
                const base = {};
                Object.defineProperty(base, 'v', { set(value) { seen = value; } });
                const o = Object.create(base);
                o.v = 5;
                seen
                """;
        assertEquals(5d, num(source));
    }

    // A setter-less accessor on a prototype rejects the write
    @Test
    public void test_inherited_getter_only_rejects_write() {
        final var source = """
                const base = {};
                Object.defineProperty(base, 'v', { get() { return 1; } });
                const o = Object.create(base);
                o.v = 5;
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // Proxy is constructor-only: reached without `new` there is no new.target
    @Test
    public void test_constructor_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Proxy({}, {})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Proxy.call(null, {}, {})"));
    }

    // Every trap is called with the handler object as its `this`
    @Test
    public void test_traps_run_with_the_handler_as_this() {
        final var source = """
                let seen = [];
                let handler = {
                  get(t, k) { seen.push(this === handler ? 'get' : 'get?'); return t[k]; },
                  has(t, k) { seen.push(this === handler ? 'has' : 'has?'); return k in t; },
                  set(t, k, v) { seen.push(this === handler ? 'set' : 'set?'); t[k] = v; return true; },
                  deleteProperty(t, k) {
                    seen.push(this === handler ? 'deleteProperty' : 'delete?');
                    delete t[k];
                    return true;
                  },
                  ownKeys(t) { seen.push(this === handler ? 'ownKeys' : 'ownKeys?'); return Reflect.ownKeys(t); }
                };
                let p = new Proxy({ a: 1 }, handler);
                p.a; 'a' in p; p.b = 2; delete p.b; Object.keys(p);
                seen.join(',')
                """;
        assertEquals("get,has,set,deleteProperty,ownKeys", str(source));
    }

    // A revoked proxy throws from every trap, whether or not the handler defines one
    @Test
    public void test_revoked_proxy_throws_everywhere() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let r = Proxy.revocable({}, {}); r.revoke(); r.proxy.x"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let r = Proxy.revocable({}, {}); r.revoke(); 'x' in r.proxy"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let r = Proxy.revocable({}, {}); r.revoke(); Object.keys(r.proxy)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let r = Proxy.revocable({}, {}); r.revoke(); delete r.proxy.x"));
    }

    // The ownKeys result is validated: duplicates, non-string/symbol keys and a dropped
    // non-configurable target key are each a TypeError
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

    // Construct through a proxy of a proxy reaches the underlying constructor
    @Test
    public void test_construct_through_a_proxy_of_a_proxy() {
        assertTrue(bool("""
                function C() { this.x = 1; }
                let inner = new Proxy(C, {});
                let outer = new Proxy(inner, {});
                new outer().x === 1
                """));
    }

    // A symbol key reaches the has trap instead of being coerced to a string
    @Test
    public void test_symbol_keys_in_the_has_trap() {
        assertTrue(bool("""
                let seen;
                let p = new Proxy({}, { has(t, k) { seen = k; return true; } });
                let ok = Symbol.iterator in p;
                ok && seen === Symbol.iterator
                """));
    }

    // A numeric key reaching a trap-carrying proxy's own get trap must be ToPropertyKey'd (a String)
    // first, not handed through as the raw JsNumber - otherwise a `switch (key) { case "10": ... }`
    // inside the trap never matches.
    @Test
    public void test_numeric_key_is_converted_to_a_string_before_reaching_a_trap() {
        assertTrue(bool("""
                let seenType;
                let p = new Proxy({}, { get(t, k) { seenType = typeof k; return k === '10'; } });
                p[10] === true && seenType === 'string'
                """));
    }

    // Same ToPropertyKey requirement for the set and has traps.
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

    // [[Construct]] forwards newTarget unchanged through a chain of missing-trap proxies (it must
    // not be replaced by the proxy's own target), so a Reflect.construct newTarget three layers
    // down still links the created instance to that newTarget's prototype.
    @Test
    public void test_construct_forwards_newtarget_through_missing_trap_proxies() {
        assertTrue(bool("""
                class Base {}
                let inner = new Proxy(Base, {});
                let outer = new Proxy(inner, {});
                class Other {}
                Object.getPrototypeOf(Reflect.construct(outer, [], Other)) === Other.prototype
                """));
    }

    // GetFunctionRealm on a newTarget that is a revoked Proxy is a TypeError: a `get` trap that
    // revokes its own proxy as a side effect while OrdinaryCreateFromConstructor reads "prototype"
    // must surface that, rather than silently falling back to the default prototype.
    @Test
    public void test_construct_with_newtarget_revoked_during_prototype_read_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                let handle = Proxy.revocable(function() {}, { get: function() { handle.revoke(); } });
                new handle.proxy();
                """));
    }
}
