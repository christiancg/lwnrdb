package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.test.JsEval;

public class ProxyDescriptorInvariantTest {

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static void typeError(String source) {
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
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

    // Without a trap, defineProperty writes through to the target
    @Test
    public void test_define_property_without_a_trap() {
        assertEquals(5, JsEval.num("""
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
}
