package org.techhouse.unit.simplejs.internal.interpreter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

public class ProxyDispatchTest {
    // Each case reports "<a TypeError was thrown>:<how many times the trap ran>", so an invariant
    // that is checked before the trap runs cannot be mistaken for one that is checked after.
    private static final String PROBE = """
            let threw = "no";
            try { %s } catch (e) { threw = e instanceof TypeError ? "yes" : "other"; }
            threw + ":" + calls
            """;

    private static String probe(String setup, String operation) {
        return ((JsString) Interpreter.run(setup + "\n" + PROBE.formatted(operation))).getValue();
    }

    @Test
    public void test_get_invariant_non_writable_non_configurable() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.defineProperty(target, "x", { value: 1, writable: false, configurable: false });
                const p = new Proxy(target, { get() { calls++; return 2; } });
                """;
        assertEquals("yes:1", probe(setup, "p.x;"));
    }

    @Test
    public void test_get_invariant_accessor_without_getter() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.defineProperty(target, "x", { set(v) {}, configurable: false });
                const p = new Proxy(target, { get() { calls++; return 2; } });
                """;
        assertEquals("yes:1", probe(setup, "p.x;"));
    }

    @Test
    public void test_set_invariant_non_writable_non_configurable() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.defineProperty(target, "x", { value: 1, writable: false, configurable: false });
                const p = new Proxy(target, { set() { calls++; return true; } });
                """;
        assertEquals("yes:1", probe(setup, "p.x = 2;"));
    }

    @Test
    public void test_set_invariant_accessor_without_setter() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.defineProperty(target, "x", { get() { return 1; }, configurable: false });
                const p = new Proxy(target, { set() { calls++; return true; } });
                """;
        assertEquals("yes:1", probe(setup, "p.x = 2;"));
    }

    @Test
    public void test_set_trap_returning_false_throws_at_the_caller() {
        final var setup = """
                let calls = 0;
                const p = new Proxy({}, { set() { calls++; return false; } });
                """;
        assertEquals("yes:1", probe(setup, "p.x = 2;"));
    }

    @Test
    public void test_set_trap_returning_false_is_only_false_through_reflect() {
        assertEquals("false",
                Interpreter.run(
                        "String(Reflect.set(new Proxy({}, { set() { return false; } }), 'x', 1))") instanceof JsString s
                                ? s.getValue()
                                : "");
    }

    @Test
    public void test_has_invariant_non_configurable_property() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.defineProperty(target, "x", { value: 1, configurable: false });
                const p = new Proxy(target, { has() { calls++; return false; } });
                """;
        assertEquals("yes:1", probe(setup, "'x' in p;"));
    }

    @Test
    public void test_has_invariant_non_extensible_target() {
        final var setup = """
                let calls = 0;
                const target = { x: 1 };
                Object.preventExtensions(target);
                const p = new Proxy(target, { has() { calls++; return false; } });
                """;
        assertEquals("yes:1", probe(setup, "'x' in p;"));
    }

    @Test
    public void test_delete_invariant_non_configurable_property() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.defineProperty(target, "x", { value: 1, configurable: false });
                const p = new Proxy(target, { deleteProperty() { calls++; return true; } });
                """;
        assertEquals("yes:1", probe(setup, "delete p.x;"));
    }

    @Test
    public void test_delete_trap_returning_false_is_false_through_reflect() {
        assertEquals("false:1", ((JsString) Interpreter.run("""
                let calls = 0;
                const p = new Proxy({ x: 1 }, { deleteProperty() { calls++; return false; } });
                String(Reflect.deleteProperty(p, "x")) + ":" + calls
                """)).getValue());
    }

    @Test
    public void test_own_keys_invariant_duplicate_entries() {
        final var setup = """
                let calls = 0;
                const p = new Proxy({}, { ownKeys() { calls++; return ["a", "a"]; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.keys(p);"));
    }

    @Test
    public void test_own_keys_invariant_omits_non_configurable_key() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.defineProperty(target, "x", { value: 1, configurable: false });
                const p = new Proxy(target, { ownKeys() { calls++; return []; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.keys(p);"));
    }

    @Test
    public void test_own_keys_invariant_extra_key_on_non_extensible_target() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.preventExtensions(target);
                const p = new Proxy(target, { ownKeys() { calls++; return ["a"]; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.keys(p);"));
    }

    @Test
    public void test_own_keys_invariant_result_is_not_an_object() {
        final var setup = """
                let calls = 0;
                const p = new Proxy({}, { ownKeys() { calls++; return undefined; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.keys(p);"));
    }

    @Test
    public void test_own_keys_invariant_result_holds_a_non_key() {
        final var setup = """
                let calls = 0;
                const p = new Proxy({}, { ownKeys() { calls++; return [1]; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.keys(p);"));
    }

    @Test
    public void test_own_keys_reads_an_array_like_result() {
        assertEquals("a,b", ((JsString) Interpreter.run("""
                const p = new Proxy({}, { ownKeys() { return { length: 2, 0: "a", 1: "b" }; } });
                Reflect.ownKeys(p).join(",")
                """)).getValue());
    }

    @Test
    public void test_get_own_property_descriptor_invariant_undefined_for_non_configurable() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.defineProperty(target, "x", { value: 1, configurable: false });
                const p = new Proxy(target, { getOwnPropertyDescriptor() { calls++; return undefined; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.getOwnPropertyDescriptor(p, 'x');"));
    }

    @Test
    public void test_get_own_property_descriptor_invariant_non_configurable_report() {
        final var setup = """
                let calls = 0;
                const target = { x: 1 };
                const p = new Proxy(target, {
                    getOwnPropertyDescriptor() { calls++; return { value: 1, configurable: false }; }
                });
                """;
        assertEquals("yes:1", probe(setup, "Object.getOwnPropertyDescriptor(p, 'x');"));
    }

    @Test
    public void test_get_own_property_descriptor_invariant_result_type() {
        final var setup = """
                let calls = 0;
                const p = new Proxy({}, { getOwnPropertyDescriptor() { calls++; return 1; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.getOwnPropertyDescriptor(p, 'x');"));
    }

    @Test
    public void test_define_property_invariant_new_key_on_non_extensible_target() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.preventExtensions(target);
                const p = new Proxy(target, { defineProperty() { calls++; return true; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.defineProperty(p, 'x', { value: 1 });"));
    }

    @Test
    public void test_define_property_invariant_non_configurable_on_configurable_target() {
        final var setup = """
                let calls = 0;
                const p = new Proxy({ x: 1 }, { defineProperty() { calls++; return true; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.defineProperty(p, 'x', { configurable: false });"));
    }

    @Test
    public void test_define_property_trap_returning_false_is_false_through_reflect() {
        assertEquals("false", ((JsString) Interpreter.run("""
                const p = new Proxy({}, { defineProperty() { return false; } });
                String(Reflect.defineProperty(p, "x", { value: 1 }))
                """)).getValue());
    }

    @Test
    public void test_get_prototype_of_invariant_result_type() {
        final var setup = """
                let calls = 0;
                const p = new Proxy({}, { getPrototypeOf() { calls++; return 1; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.getPrototypeOf(p);"));
    }

    @Test
    public void test_get_prototype_of_invariant_non_extensible_target() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.preventExtensions(target);
                const p = new Proxy(target, { getPrototypeOf() { calls++; return null; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.getPrototypeOf(p);"));
    }

    @Test
    public void test_set_prototype_of_invariant_non_extensible_target() {
        final var setup = """
                let calls = 0;
                const target = {};
                Object.preventExtensions(target);
                const p = new Proxy(target, { setPrototypeOf() { calls++; return true; } });
                """;
        assertEquals("yes:1", probe(setup, "Reflect.setPrototypeOf(p, null);"));
    }

    @Test
    public void test_set_prototype_of_trap_returning_false_is_false_through_reflect() {
        assertEquals("false", ((JsString) Interpreter.run("""
                const p = new Proxy({}, { setPrototypeOf() { return false; } });
                String(Reflect.setPrototypeOf(p, null))
                """)).getValue());
    }

    @Test
    public void test_is_extensible_invariant_disagrees_with_target() {
        final var setup = """
                let calls = 0;
                const p = new Proxy({}, { isExtensible() { calls++; return false; } });
                """;
        assertEquals("yes:1", probe(setup, "Object.isExtensible(p);"));
    }

    @Test
    public void test_prevent_extensions_invariant_leaves_target_extensible() {
        final var setup = """
                let calls = 0;
                const p = new Proxy({}, { preventExtensions() { calls++; return true; } });
                """;
        assertEquals("yes:1", probe(setup, "Reflect.preventExtensions(p);"));
    }

    @Test
    public void test_prevent_extensions_trap_returning_false_is_false_through_reflect() {
        assertEquals("false", ((JsString) Interpreter.run("""
                const p = new Proxy({}, { preventExtensions() { return false; } });
                String(Reflect.preventExtensions(p))
                """)).getValue());
    }

    @Test
    public void test_revoked_proxy_is_accepted_as_a_target_and_a_handler() {
        assertEquals("object:object", ((JsString) Interpreter.run("""
                const first = Proxy.revocable({}, {});
                first.revoke();
                const second = Proxy.revocable({}, {});
                second.revoke();
                typeof new Proxy(first.proxy, {}) + ":" + typeof new Proxy({}, second.proxy)
                """)).getValue());
    }

    @Test
    public void test_new_proxy_rejects_a_primitive() {
        final var setup = "let calls = 0;";
        assertEquals("yes:0", probe(setup, "new Proxy(1, {});"));
    }

    @Test
    public void test_proxy_over_a_callable_is_callable_through_function_prototype() {
        assertEquals("7", ((JsString) Interpreter.run("""
                const p = new Proxy(function (a) { return a; }, {});
                String(Function.prototype.call.call(p, null, 7))
                """)).getValue());
    }

    @Test
    public void test_trap_lookup_on_a_revoked_proxy_throws() {
        final var setup = """
                let calls = 0;
                const revocable = Proxy.revocable({ x: 1 }, {});
                revocable.revoke();
                """;
        assertEquals("yes:0", probe(setup, "revocable.proxy.x;"));
    }

    @Test
    public void test_trapless_proxy_over_a_proxy_dispatches_the_inner_traps() {
        assertEquals("inner", ((JsString) Interpreter.run("""
                const inner = new Proxy({}, { get() { return "inner"; } });
                new Proxy(inner, {}).anything
                """)).getValue());
    }

    @Test
    public void test_own_keys_reports_symbol_keys() {
        assertEquals("true", ((JsString) Interpreter.run("""
                const s = Symbol("a");
                const target = {};
                target[s] = 1;
                String(Reflect.ownKeys(new Proxy(target, {})).indexOf(s) >= 0)
                """)).getValue());
    }
}
