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

    // An ownKeys trap may not report a key twice
    @Test
    public void test_own_keys_rejects_duplicates() {
        typeError("Object.keys(new Proxy({}, { ownKeys() { return ['a', 'a']; } }))");
    }

    // An ownKeys trap may not omit a non-configurable key of the target
    @Test
    public void test_own_keys_must_report_non_configurable_keys() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, configurable: false });
                Object.getOwnPropertyNames(new Proxy(target, { ownKeys() { return []; } }))
                """);
    }

    // An ownKeys trap may not invent keys for a non-extensible target
    @Test
    public void test_own_keys_cannot_invent_keys_on_a_non_extensible_target() {
        typeError("""
                const target = {};
                Object.preventExtensions(target);
                Object.getOwnPropertyNames(new Proxy(target, { ownKeys() { return ['a']; } }))
                """);
    }

    // Reporting exactly the target's keys satisfies a non-extensible target
    @Test
    public void test_own_keys_matching_a_non_extensible_target() {
        assertEquals("a", str("""
                const target = { a: 1 };
                Object.preventExtensions(target);
                Object.getOwnPropertyNames(new Proxy(target, { ownKeys() { return ['a']; } })).join(',')
                """));
    }

    // An ownKeys trap may only report strings and symbols
    @Test
    public void test_own_keys_rejects_a_number_key() {
        typeError("Object.getOwnPropertyNames(new Proxy({}, { ownKeys() { return [1]; } }))");
    }

    // An ownKeys trap must return an object
    @Test
    public void test_own_keys_rejects_a_primitive_result() {
        typeError("Object.getOwnPropertyNames(new Proxy({}, { ownKeys() { return 1; } }))");
    }

    // An array-like ownKeys result is read through its length
    @Test
    public void test_own_keys_accepts_an_array_like_result() {
        assertEquals("a,b", str("""
                const proxy = new Proxy({}, { ownKeys() { return { length: 2, 0: 'a', 1: 'b' }; } });
                Object.getOwnPropertyNames(proxy).join(',')
                """));
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

    // Without a trap, defineProperty writes through to the target
    @Test
    public void test_define_property_without_a_trap() {
        assertEquals(5, num("""
                const target = {};
                Object.defineProperty(new Proxy(target, {}), 'a', { value: 5 });
                target.a
                """));
    }

    // A defineProperty trap may not claim to have added a key to a non-extensible target
    @Test
    public void test_define_property_invariant_on_a_non_extensible_target() {
        typeError("""
                const target = {};
                Object.preventExtensions(target);
                const proxy = new Proxy(target, { defineProperty() { return true; } });
                Object.defineProperty(proxy, 'a', { value: 1 })
                """);
    }

    // A defineProperty trap may not claim to have added a non-configurable key that is absent
    @Test
    public void test_define_property_invariant_for_a_new_non_configurable_key() {
        typeError("""
                const proxy = new Proxy({}, { defineProperty() { return true; } });
                Object.defineProperty(proxy, 'a', { value: 1, configurable: false })
                """);
    }

    // A configurable new key is allowed
    @Test
    public void test_define_property_allows_a_configurable_new_key() {
        assertTrue(bool("""
                const proxy = new Proxy({}, { defineProperty() { return true; } });
                Object.defineProperty(proxy, 'a', { value: 1, configurable: true }) === proxy
                """));
    }

    // A defineProperty trap returning false is reported by Reflect.defineProperty
    @Test
    public void test_define_property_trap_returning_false() {
        assertFalse(bool(
                "Reflect.defineProperty(new Proxy({}, { defineProperty() { return false; } }), 'a', { value: 1 })"));
    }

    // A defineProperty trap may not claim to have made a configurable property non-configurable
    @Test
    public void test_define_property_invariant_for_a_configurability_downgrade() {
        typeError("""
                const target = { a: 1 };
                const proxy = new Proxy(target, { defineProperty() { return true; } });
                Object.defineProperty(proxy, 'a', { configurable: false })
                """);
    }

    // A defineProperty trap may not claim a writability downgrade on a non-configurable property
    @Test
    public void test_define_property_invariant_for_a_writability_downgrade() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, writable: true, configurable: false });
                const proxy = new Proxy(target, { defineProperty() { return true; } });
                Object.defineProperty(proxy, 'a', { writable: false })
                """);
    }

    // A getOwnPropertyDescriptor trap may not hide a non-configurable property
    @Test
    public void test_descriptor_trap_cannot_hide_a_non_configurable_property() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, configurable: false });
                const proxy = new Proxy(target, { getOwnPropertyDescriptor() { return undefined; } });
                Object.getOwnPropertyDescriptor(proxy, 'a')
                """);
    }

    // A getOwnPropertyDescriptor trap may not hide a property of a non-extensible target
    @Test
    public void test_descriptor_trap_cannot_hide_a_property_of_a_non_extensible_target() {
        typeError("""
                const target = { a: 1 };
                Object.preventExtensions(target);
                const proxy = new Proxy(target, { getOwnPropertyDescriptor() { return undefined; } });
                Object.getOwnPropertyDescriptor(proxy, 'a')
                """);
    }

    // A getOwnPropertyDescriptor trap must return an object or undefined
    @Test
    public void test_descriptor_trap_rejects_a_primitive_result() {
        typeError("Object.getOwnPropertyDescriptor(new Proxy({}, { getOwnPropertyDescriptor() { return 1; } }), 'a')");
    }

    // A trap may not report a non-configurable descriptor for an absent property
    @Test
    public void test_descriptor_trap_cannot_invent_a_non_configurable_property() {
        typeError("""
                const proxy = new Proxy({}, {
                    getOwnPropertyDescriptor() { return { value: 1, configurable: false }; }
                });
                Object.getOwnPropertyDescriptor(proxy, 'a')
                """);
    }

    // A trap may not report non-writable and non-configurable for a writable target property
    @Test
    public void test_descriptor_trap_cannot_invent_non_writability() {
        typeError("""
                const target = { a: 1 };
                const proxy = new Proxy(target, {
                    getOwnPropertyDescriptor() { return { value: 1, writable: false, configurable: false }; }
                });
                Object.getOwnPropertyDescriptor(proxy, 'a')
                """);
    }

    // A configurable accessor descriptor is reported as is
    @Test
    public void test_descriptor_trap_reports_an_accessor() {
        assertEquals("function", str("""
                const target = { a: 1 };
                const proxy = new Proxy(target, {
                    getOwnPropertyDescriptor() { return { get() { return 9; }, configurable: true }; }
                });
                typeof Object.getOwnPropertyDescriptor(proxy, 'a').get
                """));
    }

    // A trap may not turn a non-configurable data property into an accessor
    @Test
    public void test_descriptor_trap_cannot_change_the_kind() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, configurable: false });
                const proxy = new Proxy(target, {
                    getOwnPropertyDescriptor() { return { get() { return 1; }, configurable: false }; }
                });
                Object.getOwnPropertyDescriptor(proxy, 'a')
                """);
    }

    // A trap may not disagree about enumerability of a non-configurable property
    @Test
    public void test_descriptor_trap_cannot_change_enumerability() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, enumerable: false, configurable: false });
                const proxy = new Proxy(target, {
                    getOwnPropertyDescriptor() { return { value: 1, enumerable: true, configurable: false }; }
                });
                Object.getOwnPropertyDescriptor(proxy, 'a')
                """);
    }

    // A trap may not report a different value for a frozen property
    @Test
    public void test_descriptor_trap_cannot_change_the_value() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { value: 1, writable: false, configurable: false });
                const proxy = new Proxy(target, {
                    getOwnPropertyDescriptor() { return { value: 2, writable: false, configurable: false }; }
                });
                Object.getOwnPropertyDescriptor(proxy, 'a')
                """);
    }

    // A trap may not report a different getter for a non-configurable accessor
    @Test
    public void test_descriptor_trap_cannot_change_the_getter() {
        typeError("""
                const target = {};
                Object.defineProperty(target, 'a', { get() { return 1; }, configurable: false });
                const proxy = new Proxy(target, {
                    getOwnPropertyDescriptor() { return { get() { return 2; }, configurable: false }; }
                });
                Object.getOwnPropertyDescriptor(proxy, 'a')
                """);
    }

    // The descriptor invariants apply to symbol keys too
    @Test
    public void test_descriptor_invariant_for_a_symbol_key() {
        typeError("""
                const key = Symbol('k');
                const target = {};
                Object.defineProperty(target, key, { value: 1, configurable: false });
                const proxy = new Proxy(target, { getOwnPropertyDescriptor() { return undefined; } });
                Object.getOwnPropertyDescriptor(proxy, key)
                """);
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
