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

public class ProxyInvariantProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static void typeError(String source) {
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_get_invariant_on_a_frozen_data_property() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, writable: false, configurable: false });
                const proxy = new Proxy(target, { get() { return 2; } });
                proxy.a
                """);
    }

    @Test
    public void test_get_invariant_allows_the_same_value() {
        assertEquals(1, num("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, writable: false, configurable: false });
                const proxy = new Proxy(target, { get() { return 1; } });
                proxy.a
                """));
    }

    @Test
    public void test_get_invariant_on_a_setter_only_accessor() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { set(v) {}, configurable: false });
                const proxy = new Proxy(target, { get() { return 2; } });
                proxy.a
                """);
    }

    @Test
    public void test_set_invariant_on_a_frozen_data_property() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, writable: false, configurable: false });
                const proxy = new Proxy(target, { set() { return true; } });
                proxy.a = 3
                """);
    }

    @Test
    public void test_set_invariant_on_a_getter_only_accessor() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { get() { return 1; }, configurable: false });
                const proxy = new Proxy(target, { set() { return true; } });
                proxy.a = 3
                """);
    }

    @Test
    public void test_set_trap_returning_false() {
        assertFalse(bool("Reflect.set(new Proxy({}, { set() { return false; } }), 'a', 1)"));
    }

    @Test
    public void test_has_invariant_on_a_non_configurable_property() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, configurable: false });
                const proxy = new Proxy(target, { has() { return false; } });
                'a' in proxy
                """);
    }

    @Test
    public void test_has_invariant_on_a_non_extensible_target() {
        typeError("""
                const target = { a: 1 };
                Object.preventExtensions(target);
                const proxy = new Proxy(target, { has() { return false; } });
                'a' in proxy
                """);
    }

    @Test
    public void test_delete_invariant_on_a_non_configurable_property() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, configurable: false });
                const proxy = new Proxy(target, { deleteProperty() { return true; } });
                delete proxy.a
                """);
    }

    @Test
    public void test_delete_trap_returning_false() {
        assertFalse(bool("Reflect.deleteProperty(new Proxy({ a: 1 }, { deleteProperty() { return false; } }), 'a')"));
    }

    @Test
    public void test_delete_without_a_trap() {
        assertFalse(bool("const target = { a: 1 }; delete new Proxy(target, {}).a; 'a' in target"));
    }

    @Test
    public void test_construct_trap_must_return_an_object() {
        typeError("new (new Proxy(function () {}, { construct() { return 1; } }))()");
    }

    @Test
    public void test_construct_without_a_trap() {
        assertEquals(4, num("function F(v) { this.v = v; } new (new Proxy(F, {}))(4).v"));
    }

    @Test
    public void test_construct_trap_receives_new_target() {
        assertTrue(bool("""
                const proxy = new Proxy(function () {}, {
                    construct(target, args, newTarget) { return { same: newTarget === proxy }; }
                });
                new proxy().same
                """));
    }

    @Test
    public void test_get_prototype_of_must_return_an_object_or_null() {
        typeError("Object.getPrototypeOf(new Proxy({}, { getPrototypeOf() { return 1; } }))");
    }

    @Test
    public void test_get_prototype_of_may_return_null() {
        assertEquals("null",
                str("String(Object.getPrototypeOf(new Proxy({}, { getPrototypeOf() { return null; } })))"));
    }

    @Test
    public void test_get_prototype_of_invariant_on_a_non_extensible_target() {
        typeError("""
                const target = {};
                Object.preventExtensions(target);
                Object.getPrototypeOf(new Proxy(target, { getPrototypeOf() { return {}; } }))
                """);
    }

    @Test
    public void test_get_prototype_of_without_a_trap() {
        assertTrue(bool("Object.getPrototypeOf(new Proxy({}, {})) === Object.prototype"));
    }

    @Test
    public void test_set_prototype_of_without_a_trap() {
        assertTrue(bool("""
                const target = {};
                const proto = { z: 1 };
                Object.setPrototypeOf(new Proxy(target, {}), proto);
                Object.getPrototypeOf(target) === proto
                """));
    }

    @Test
    public void test_set_prototype_of_trap_returning_false() {
        assertFalse(bool("Reflect.setPrototypeOf(new Proxy({}, { setPrototypeOf() { return false; } }), null)"));
    }

    @Test
    public void test_set_prototype_of_invariant_on_a_non_extensible_target() {
        typeError("""
                const target = {};
                Object.preventExtensions(target);
                const proxy = new Proxy(target, { setPrototypeOf() { return true; } });
                Object.setPrototypeOf(proxy, { a: 1 })
                """);
    }

    @Test
    public void test_set_prototype_of_allows_an_unchanged_prototype() {
        assertTrue(bool("""
                const target = {};
                Object.preventExtensions(target);
                const proxy = new Proxy(target, { setPrototypeOf() { return true; } });
                Reflect.setPrototypeOf(proxy, Object.prototype)
                """));
    }

    @Test
    public void test_is_extensible_must_agree_with_the_target() {
        typeError("Object.isExtensible(new Proxy({}, { isExtensible() { return false; } }))");
    }

    @Test
    public void test_is_extensible_agreeing_trap() {
        assertTrue(bool("Object.isExtensible(new Proxy({}, { isExtensible() { return true; } }))"));
    }

    @Test
    public void test_is_extensible_without_a_trap() {
        assertFalse(bool(
                "const target = {}; Object.preventExtensions(target); Object.isExtensible(new Proxy(target, {}))"));
    }

    @Test
    public void test_prevent_extensions_invariant() {
        typeError("Object.preventExtensions(new Proxy({}, { preventExtensions() { return true; } }))");
    }

    @Test
    public void test_prevent_extensions_trap_returning_false() {
        assertFalse(bool("Reflect.preventExtensions(new Proxy({}, { preventExtensions() { return false; } }))"));
    }

    @Test
    public void test_prevent_extensions_without_a_trap() {
        assertFalse(bool("""
                const target = {};
                Object.preventExtensions(new Proxy(target, {}));
                Object.isExtensible(target)
                """));
    }

    @Test
    public void test_revoked_proxy_rejects_get_prototype_of() {
        typeError("const r = Proxy.revocable({}, {}); r.revoke(); Object.getPrototypeOf(r.proxy)");
    }

    @Test
    public void test_revoked_proxy_rejects_reads() {
        typeError("const r = Proxy.revocable({ a: 1 }, {}); r.revoke(); r.proxy.a");
    }

    @Test
    public void test_non_callable_trap() {
        typeError("new Proxy({}, { get: 1 }).a");
    }

    @Test
    public void test_delete_trap_receives_symbol_keys() {
        assertEquals("symbol", str("""
                const key = Symbol('k');
                let seen = '';
                const proxy = new Proxy({}, { deleteProperty(target, k) { seen = typeof k; return true; } });
                delete proxy[key];
                seen
                """));
    }
}
