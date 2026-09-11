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

    // A get trap may not report a different value for a non-writable, non-configurable property
    @Test
    public void test_get_invariant_on_a_frozen_data_property() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, writable: false, configurable: false });
                const proxy = new Proxy(target, { get() { return 2; } });
                proxy.a
                """);
    }

    // Reporting the same value satisfies the get invariant
    @Test
    public void test_get_invariant_allows_the_same_value() {
        assertEquals(1, num("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, writable: false, configurable: false });
                const proxy = new Proxy(target, { get() { return 1; } });
                proxy.a
                """));
    }

    // A get trap may not report a value for a non-configurable accessor without a getter
    @Test
    public void test_get_invariant_on_a_setter_only_accessor() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { set(v) {}, configurable: false });
                const proxy = new Proxy(target, { get() { return 2; } });
                proxy.a
                """);
    }

    // A set trap may not claim success against a non-writable, non-configurable property
    @Test
    public void test_set_invariant_on_a_frozen_data_property() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, writable: false, configurable: false });
                const proxy = new Proxy(target, { set() { return true; } });
                proxy.a = 3
                """);
    }

    // A set trap may not claim success against a non-configurable getter-only accessor
    @Test
    public void test_set_invariant_on_a_getter_only_accessor() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { get() { return 1; }, configurable: false });
                const proxy = new Proxy(target, { set() { return true; } });
                proxy.a = 3
                """);
    }

    // A set trap returning false is reported by Reflect.set
    @Test
    public void test_set_trap_returning_false() {
        assertFalse(bool("Reflect.set(new Proxy({}, { set() { return false; } }), 'a', 1)"));
    }

    // A has trap may not hide a non-configurable property of the target
    @Test
    public void test_has_invariant_on_a_non_configurable_property() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, configurable: false });
                const proxy = new Proxy(target, { has() { return false; } });
                'a' in proxy
                """);
    }

    // A has trap may not hide any property of a non-extensible target
    @Test
    public void test_has_invariant_on_a_non_extensible_target() {
        typeError("""
                const target = { a: 1 };
                Object.preventExtensions(target);
                const proxy = new Proxy(target, { has() { return false; } });
                'a' in proxy
                """);
    }

    // A deleteProperty trap may not claim to have removed a non-configurable property
    @Test
    public void test_delete_invariant_on_a_non_configurable_property() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, configurable: false });
                const proxy = new Proxy(target, { deleteProperty() { return true; } });
                delete proxy.a
                """);
    }

    // A deleteProperty trap returning false is reported by Reflect.deleteProperty
    @Test
    public void test_delete_trap_returning_false() {
        assertFalse(bool("Reflect.deleteProperty(new Proxy({ a: 1 }, { deleteProperty() { return false; } }), 'a')"));
    }

    // Without a trap, delete goes through to the target
    @Test
    public void test_delete_without_a_trap() {
        assertFalse(bool("const target = { a: 1 }; delete new Proxy(target, {}).a; 'a' in target"));
    }

    // A construct trap must return an object
    @Test
    public void test_construct_trap_must_return_an_object() {
        typeError("new (new Proxy(function () {}, { construct() { return 1; } }))()");
    }

    // Without a trap, construction goes through to the target
    @Test
    public void test_construct_without_a_trap() {
        assertEquals(4, num("function F(v) { this.v = v; } new (new Proxy(F, {}))(4).v"));
    }

    // The construct trap receives the proxy as newTarget
    @Test
    public void test_construct_trap_receives_new_target() {
        assertTrue(bool("""
                const proxy = new Proxy(function () {}, {
                    construct(target, args, newTarget) { return { same: newTarget === proxy }; }
                });
                new proxy().same
                """));
    }

    // A getPrototypeOf trap must return an object or null
    @Test
    public void test_get_prototype_of_must_return_an_object_or_null() {
        typeError("Object.getPrototypeOf(new Proxy({}, { getPrototypeOf() { return 1; } }))");
    }

    // A getPrototypeOf trap may return null
    @Test
    public void test_get_prototype_of_may_return_null() {
        assertEquals("null",
                str("String(Object.getPrototypeOf(new Proxy({}, { getPrototypeOf() { return null; } })))"));
    }

    // A getPrototypeOf trap may not disagree with a non-extensible target
    @Test
    public void test_get_prototype_of_invariant_on_a_non_extensible_target() {
        typeError("""
                const target = {};
                Object.preventExtensions(target);
                Object.getPrototypeOf(new Proxy(target, { getPrototypeOf() { return {}; } }))
                """);
    }

    // Without a trap, getPrototypeOf reports the target's prototype
    @Test
    public void test_get_prototype_of_without_a_trap() {
        assertTrue(bool("Object.getPrototypeOf(new Proxy({}, {})) === Object.prototype"));
    }

    // Without a trap, setPrototypeOf writes through to the target
    @Test
    public void test_set_prototype_of_without_a_trap() {
        assertTrue(bool("""
                const target = {};
                const proto = { z: 1 };
                Object.setPrototypeOf(new Proxy(target, {}), proto);
                Object.getPrototypeOf(target) === proto
                """));
    }

    // A setPrototypeOf trap returning false is reported by Reflect.setPrototypeOf
    @Test
    public void test_set_prototype_of_trap_returning_false() {
        assertFalse(bool("Reflect.setPrototypeOf(new Proxy({}, { setPrototypeOf() { return false; } }), null)"));
    }

    // A setPrototypeOf trap may not claim a change against a non-extensible target
    @Test
    public void test_set_prototype_of_invariant_on_a_non_extensible_target() {
        typeError("""
                const target = {};
                Object.preventExtensions(target);
                const proxy = new Proxy(target, { setPrototypeOf() { return true; } });
                Object.setPrototypeOf(proxy, { a: 1 })
                """);
    }

    // Claiming a no-op change against a non-extensible target is allowed
    @Test
    public void test_set_prototype_of_allows_an_unchanged_prototype() {
        assertTrue(bool("""
                const target = {};
                Object.preventExtensions(target);
                const proxy = new Proxy(target, { setPrototypeOf() { return true; } });
                Reflect.setPrototypeOf(proxy, Object.prototype)
                """));
    }

    // An isExtensible trap must agree with its target
    @Test
    public void test_is_extensible_must_agree_with_the_target() {
        typeError("Object.isExtensible(new Proxy({}, { isExtensible() { return false; } }))");
    }

    // An agreeing isExtensible trap is reported as is
    @Test
    public void test_is_extensible_agreeing_trap() {
        assertTrue(bool("Object.isExtensible(new Proxy({}, { isExtensible() { return true; } }))"));
    }

    // Without a trap, isExtensible reflects the target
    @Test
    public void test_is_extensible_without_a_trap() {
        assertFalse(bool(
                "const target = {}; Object.preventExtensions(target); Object.isExtensible(new Proxy(target, {}))"));
    }

    // A preventExtensions trap may not claim success while the target stays extensible
    @Test
    public void test_prevent_extensions_invariant() {
        typeError("Object.preventExtensions(new Proxy({}, { preventExtensions() { return true; } }))");
    }

    // A preventExtensions trap returning false is reported by Reflect.preventExtensions
    @Test
    public void test_prevent_extensions_trap_returning_false() {
        assertFalse(bool("Reflect.preventExtensions(new Proxy({}, { preventExtensions() { return false; } }))"));
    }

    // Without a trap, preventExtensions acts on the target
    @Test
    public void test_prevent_extensions_without_a_trap() {
        assertFalse(bool("""
                const target = {};
                Object.preventExtensions(new Proxy(target, {}));
                Object.isExtensible(target)
                """));
    }

    // A revoked proxy rejects every internal method
    @Test
    public void test_revoked_proxy_rejects_get_prototype_of() {
        typeError("const r = Proxy.revocable({}, {}); r.revoke(); Object.getPrototypeOf(r.proxy)");
    }

    // A revoked proxy rejects property reads
    @Test
    public void test_revoked_proxy_rejects_reads() {
        typeError("const r = Proxy.revocable({ a: 1 }, {}); r.revoke(); r.proxy.a");
    }

    // A non-callable trap is a TypeError
    @Test
    public void test_non_callable_trap() {
        typeError("new Proxy({}, { get: 1 }).a");
    }

    // A deleteProperty trap sees a symbol key as a symbol
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
