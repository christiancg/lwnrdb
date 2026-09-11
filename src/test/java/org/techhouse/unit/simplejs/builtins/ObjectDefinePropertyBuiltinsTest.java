package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.test.JsEval;

public class ObjectDefinePropertyBuiltinsTest {
    // defineProperty with a value descriptor
    @Test
    public void test_define_property_value() {
        assertEquals(5, JsEval.num("let o = {}; Object.defineProperty(o, 'v', {value: 5}); o.v"));
    }

    // defineProperty with an accessor descriptor invokes the getter/setter
    @Test
    public void test_define_property_accessor() {
        assertEquals(42, JsEval.num(
                "let o = {n: 0}; Object.defineProperty(o, 'v', {get: function() { return 42; }, set: function(x) { this.n = x; }}); o.v"));
        assertEquals(8, JsEval.num(
                "let o = {n: 0}; Object.defineProperty(o, 'v', {get: function() { return this.n; }, set: function(x) { this.n = x; }}); o.v = 8; o.n"));
    }

    // defineProperty adding a new key to a frozen (non-extensible) object throws
    @Test
    public void test_define_property_frozen() {
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class,
                () -> Interpreter.run("let o = Object.freeze({}); Object.defineProperty(o, 'v', {value: 5});"));
    }

    // defineProperties applies multiple descriptors at once
    @Test
    public void test_define_properties() {
        assertEquals(3,
                JsEval.num("let o = {}; Object.defineProperties(o, {a: {value: 1}, b: {value: 2}}); o.a + o.b"));
    }

    // a non-writable data property ignores later assignment
    @Test
    public void test_define_property_non_writable() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("let o = {}; Object.defineProperty(o, 'v', {value: 1, writable: false}); o.v = 99"));
        assertEquals(1, JsEval.num(
                "let o = {}; Object.defineProperty(o, 'v', {value: 1, writable: false}); try { o.v = 99; } catch (e) { } o.v"));
    }

    // a writable:true data property accepts later assignment
    @Test
    public void test_define_property_writable() {
        assertEquals(99,
                JsEval.num("let o = {}; Object.defineProperty(o, 'v', {value: 1, writable: true}); o.v = 99; o.v"));
    }

    // a non-enumerable property is hidden from keys/values/entries and for-in, but visible to getOwnPropertyNames
    @Test
    public void test_define_property_non_enumerable() {
        final var setup = "let o = {a: 1}; Object.defineProperty(o, 'hidden', {value: 2, enumerable: false}); ";
        assertEquals("a", JsEval.str(setup + "Object.keys(o).join(',')"));
        assertEquals("1", JsEval.str(setup + "Object.values(o).join(',')"));
        assertEquals("a=1", JsEval.str(setup + "Object.entries(o).map(e => e[0] + '=' + e[1]).join(',')"));
        assertEquals("a", JsEval.str(setup + "let out = []; for (let k in o) out.push(k); out.join(',')"));
        assertEquals("a,hidden", JsEval.str(setup + "Object.getOwnPropertyNames(o).join(',')"));
    }

    // redefining a non-configurable property in an incompatible way throws
    @Test
    public void test_redefine_non_configurable_throws() {
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class,
                () -> Interpreter.run("let o = {}; Object.defineProperty(o, 'v', {value: 1, configurable: false});"
                        + "Object.defineProperty(o, 'v', {value: 2});"));
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class,
                () -> Interpreter.run("let o = {}; Object.defineProperty(o, 'v', {value: 1, configurable: false});"
                        + "Object.defineProperty(o, 'v', {configurable: true});"));
    }

    // redefining a configurable property is allowed
    @Test
    public void test_redefine_configurable_allowed() {
        assertEquals(2, JsEval.num("let o = {}; Object.defineProperty(o, 'v', {value: 1, configurable: true});"
                + "Object.defineProperty(o, 'v', {value: 2}); o.v"));
    }

    // defineProperty defaults unspecified attributes to false for a new property
    @Test
    public void test_define_property_defaults_false() {
        final var setup = "let o = {}; Object.defineProperty(o, 'v', {value: 1}); ";
        assertFalse(JsEval.bool(setup + "Object.getOwnPropertyDescriptor(o, 'v').writable"));
        assertFalse(JsEval.bool(setup + "Object.getOwnPropertyDescriptor(o, 'v').enumerable"));
        assertFalse(JsEval.bool(setup + "Object.getOwnPropertyDescriptor(o, 'v').configurable"));
    }

    // Redefining a non-configurable data property as an accessor is rejected
    @Test
    public void test_redefine_data_to_accessor_rejected() {
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class,
                () -> Interpreter.run("let o = {}; Object.defineProperty(o, 'x', { value: 1, configurable: false }); "
                        + "Object.defineProperty(o, 'x', { get() { return 2; } });"));
    }

    // Redefining a non-configurable accessor as a data property is rejected
    @Test
    public void test_redefine_accessor_to_data_rejected() {
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class,
                () -> Interpreter.run("let o = {}; Object.defineProperty(o, 'x', { get() { return 1; }, "
                        + "configurable: false }); Object.defineProperty(o, 'x', { value: 2 });"));
    }

    // Changing the getter of a non-configurable accessor is rejected
    @Test
    public void test_redefine_accessor_getter_change_rejected() {
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class,
                () -> Interpreter.run("let o = {}; let g = function () { return 1; }; "
                        + "Object.defineProperty(o, 'x', { get: g, configurable: false }); "
                        + "Object.defineProperty(o, 'x', { get() { return 2; } });"));
    }

    // Redefining a non-configurable accessor with the same getter is allowed
    @Test
    public void test_redefine_accessor_same_getter_allowed() {
        assertEquals(1,
                JsEval.num("let o = {}; let g = function () { return 1; }; "
                        + "Object.defineProperty(o, 'x', { get: g, configurable: false }); "
                        + "Object.defineProperty(o, 'x', { get: g }); o.x"));
    }

    // Changing +0 to -0 on a non-writable property is rejected under SameValue
    @Test
    public void test_redefine_value_signed_zero_rejected() {
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class,
                () -> Interpreter.run("let o = {}; Object.defineProperty(o, 'x', "
                        + "{ value: 0, writable: false, configurable: false }); "
                        + "Object.defineProperty(o, 'x', { value: -0 });"));
    }

    // Redefining a non-writable NaN value with NaN is allowed under SameValue
    @Test
    public void test_redefine_value_nan_allowed() {
        assertTrue(JsEval.bool("let o = {}; Object.defineProperty(o, 'x', "
                + "{ value: Number.NaN, writable: false, configurable: false }); "
                + "Object.defineProperty(o, 'x', { value: Number.NaN }); true"));
    }

    // A configurable property may freely switch between data and accessor forms
    @Test
    public void test_configurable_redefine_allowed() {
        assertEquals(9, JsEval.num("let o = {}; Object.defineProperty(o, 'x', { value: 1, configurable: true }); "
                + "Object.defineProperty(o, 'x', { get() { return 9; } }); o.x"));
    }

    // an accessor defined with enumerable:true participates in enumeration
    @Test
    public void test_define_property_enumerable_accessor_is_enumerated() {
        final var source = """
                const o = {};
                Object.defineProperty(o, 'x', { get() { return 7; }, enumerable: true });
                Object.keys(o).join(',') + '|' + Object.values(o).join(',')
                """;
        assertEquals("x|7", JsEval.str(source));
    }

    // a setter-only property enumerates with an undefined value
    @Test
    public void test_setter_only_property_enumerates_as_undefined() {
        final var source = """
                const o = { set x(v) {} };
                Object.keys(o).join(',') + '|' + String(Object.values(o)[0])
                """;
        assertEquals("x|undefined", JsEval.str(source));
    }

    // changing the enumerable flag of a non-configurable property is rejected
    @Test
    public void test_redefine_enumerable_change_rejected() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run(
                        "let o = {}; Object.defineProperty(o, 'x', {value: 1, enumerable: true, configurable: false}); "
                                + "Object.defineProperty(o, 'x', {enumerable: false});"));
    }

    // changing the setter of a non-configurable accessor is rejected
    @Test
    public void test_redefine_accessor_setter_change_rejected() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let o = {}; let s = function(v) {}; "
                        + "Object.defineProperty(o, 'x', { set: s, configurable: false }); "
                        + "Object.defineProperty(o, 'x', { set(v) {} });"));
    }

    // flipping writable from false to true on a non-configurable property is rejected
    @Test
    public void test_redefine_writable_false_to_true_rejected() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let o = {}; Object.defineProperty(o, 'x', "
                        + "{ value: 1, writable: false, configurable: false }); "
                        + "Object.defineProperty(o, 'x', { writable: true });"));
    }

    @Test
    public void test_define_property_non_configurable_index_rejects_redefine() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var arr = [1];
                Object.defineProperty(arr, "0", { configurable: false });
                Object.defineProperty(arr, "0", { configurable: true });
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var arr = [1];
                Object.defineProperty(arr, "0", { writable: false, configurable: false });
                Object.defineProperty(arr, "0", { value: 2 });
                """));
    }

    @Test
    public void test_define_property_accessor_with_value_throws() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], '0', { get() {}, value: 1 })"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'foo', { get() {}, value: 1 })"));
    }

    // ToObject rejects only null/undefined, so getOwnPropertyDescriptor throws for them alone
    @Test
    public void test_get_own_property_descriptor_throws_on_undefined_target() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.getOwnPropertyDescriptor(undefined, 'x')"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.getOwnPropertyDescriptor(null, 'x')"));
    }

    // Object.defineProperties rejects a non-object Properties argument
    @Test
    public void test_define_properties_throws_on_undefined_props() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.defineProperties({}, undefined)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.defineProperties({}, null)"));
    }

    // defineProperty is honoured on an exotic target rather than silently returning it
    @Test
    public void test_define_property_on_exotic_target_is_honoured() {
        assertTrue(JsEval.bool("const m = new Map(); Object.defineProperty(m, 'x', { value: 1, enumerable: false });"
                + "const d = Object.getOwnPropertyDescriptor(m, 'x');" + "d.value === 1 && d.enumerable === false"));
        assertTrue(JsEval.bool("const dt = new Date(0); Object.defineProperty(dt, 'y', { value: 2 });"
                + "Object.getOwnPropertyDescriptor(dt, 'y').value === 2"));
    }

    @Test
    public void definePropertyRejectsANonObjectTargetOrDescriptor() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.defineProperty(1, 'a', {})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.defineProperty('s', 'a', {})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.defineProperty({}, 'a')"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.defineProperty({}, 'a', 5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.defineProperties(1, {})"));
    }

    @Test
    public void definePropertyWritesThroughTheGlobalObject() {
        assertEquals(1, JsEval.num("Object.defineProperty(globalThis, 'gDefined', { value: 1 });"
                + " Object.getOwnPropertyDescriptor(globalThis, 'gDefined').value"));
        assertEquals(7, JsEval.num("globalThis.gAssigned = 1;"
                + " Object.defineProperty(globalThis, 'gAssigned', { value: 7 }); gAssigned"));
        // A top-level `var` is a non-configurable but *writable* global property, so redefining its
        // value is legal; only a non-writable one rejects.
        assertEquals(7, JsEval.num("var gVar = 1;" + " Object.defineProperty(globalThis, 'gVar', { value: 7 }); gVar"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty(globalThis, 'NaN', { value: 7 })"));
        assertEquals("undefined", JsEval.str("typeof Object.getOwnPropertyDescriptor(globalThis, Symbol('never'))"));
    }

    @Test
    public void definePropertyMaterialisesCallableMetadata() {
        assertTrue(JsEval.bool("function f() {} Object.defineProperty(f, 'prototype', { value: { tag: 1 } });"
                + " f.prototype.tag === 1"));
        assertTrue(JsEval.bool(
                "function f() {} Object.defineProperty(f, 'name', { value: 'renamed' });" + " f.name === 'renamed'"));
        assertTrue(JsEval.bool("function f(a, b) {} Object.defineProperty(f, 'length', { value: 9 }); f.length === 9"));
    }

    // 'get' and 'set' both present but neither callable is still an accessor: it reads as undefined
    @Test
    public void anAllUndefinedAccessorReadsAsUndefined() {
        assertEquals("undefined", JsEval
                .str("const o = {}; Object.defineProperty(o, 'x', { get: undefined, set: undefined }); typeof o.x"));
    }

    // A non-symbol key runs through ToPropertyKey, so a user toString/valueOf decides the name
    @Test
    public void definePropertyCoercesKeyThroughToPropertyKey() {
        assertEquals(7, JsEval.num("""
                const o = {};
                Object.defineProperty(o, { toString() { return 'k'; } }, { value: 7 });
                o.k
                """));
        assertEquals(3, JsEval.num("const o = {}; Object.defineProperty(o, 2, { value: 3 }); o['2']"));
        assertTrue(JsEval.bool("""
                const o = { a: 1 };
                Object.hasOwn(o, { toString() { return 'a'; } })
                """));
        assertTrue(JsEval.bool("""
                const o = { a: 1 };
                delete o[{ toString() { return 'a'; } }];
                !('a' in o)
                """));
        assertEquals(1, JsEval.num("""
                const o = {};
                Object.defineProperty(o, 'x', { value: 1 });
                Object.getOwnPropertyDescriptor(o, { toString() { return 'x'; } }).value
                """));
    }

    // ToPropertyKey(P) runs before the descriptor is inspected, so a poisoned key coercion is what
    // escapes even when the descriptor is not an object at all.
    @Test
    public void definePropertyCoercesTheKeyBeforeReadingTheDescriptor() {
        assertEquals("key", JsEval.str("""
                let order = [];
                const key = { toString() { order.push('key'); return 'k'; } };
                try { Object.defineProperty({}, key, 1); } catch (e) { order.push('desc'); }
                order.join(',')
                """).split(",")[0]);
        assertEquals("key,desc", JsEval.str("""
                let order = [];
                const key = { toString() { order.push('key'); return 'k'; } };
                try { Object.defineProperty({}, key, 1); } catch (e) { order.push('desc'); }
                order.join(',')
                """));
    }

    // ToPropertyDescriptor reads its fields in the normative order, so a poisoned accessor on the
    // descriptor object is observed at exactly the right point.
    @Test
    public void definePropertyObservesDescriptorFieldsInSpecOrder() {
        assertEquals("enumerable,configurable,value,writable,get,set", JsEval.str("""
                let order = [];
                const desc = {
                  get writable() { order.push('writable'); return true; },
                  get set() { order.push('set'); return undefined; },
                  get enumerable() { order.push('enumerable'); return true; },
                  get value() { order.push('value'); return 1; },
                  get get() { order.push('get'); return undefined; },
                  get configurable() { order.push('configurable'); return true; }
                };
                try { Object.defineProperty({}, 'x', desc); } catch (e) {}
                order.join(',')
                """));
        // The first poisoned field aborts, so nothing after `enumerable` is ever read.
        assertEquals("enumerable", JsEval.str("""
                let order = [];
                const desc = {
                  get enumerable() { order.push('enumerable'); throw new RangeError(); },
                  get value() { order.push('value'); return 1; }
                };
                try { Object.defineProperty({}, 'x', desc); } catch (e) {}
                order.join(',')
                """));
    }

    // A redefinition that clears writability has to take effect, not be dropped.
    @Test
    public void definePropertyAppliesAWritabilityChange() {
        assertTrue(JsEval.bool("""
                const o = { x: 1 };
                Object.defineProperty(o, 'x', { writable: false });
                Object.getOwnPropertyDescriptor(o, 'x').writable === false
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("const o = { x: 1 }; Object.defineProperty(o, 'x', { writable: false }); o.x = 2"));
        assertTrue(JsEval.bool("""
                const o = {};
                Object.defineProperty(o, 'x', { value: 1, writable: false, configurable: true });
                Object.defineProperty(o, 'x', { writable: true });
                o.x = 5;
                o.x === 5
                """));
    }

    // A Date or Map receiver carries a PropertyTable, so a definition on it must land and read back
    // rather than being silently discarded.
    @Test
    public void definePropertiesReachesExoticReceivers() {
        assertEquals("dateData", JsEval.str("""
                const d = new Date(0);
                Object.defineProperties(d, { tag: { value: 'dateData', enumerable: true } });
                d.tag
                """));
        assertEquals("mapData", JsEval.str("""
                const m = new Map();
                Object.defineProperty(m, 'tag', { value: 'mapData' });
                m.tag
                """));
        assertEquals("setData", JsEval.str("const s = new Set(); s.tag = 'setData'; s.tag"));
        assertEquals("bufferData", JsEval.str("const b = new ArrayBuffer(1); b.tag = 'bufferData'; b.tag"));
        // The internal slot still wins: a Map's `size` is not shadowed by the table.
        assertEquals(1, JsEval.num("const m = new Map([[1, 2]]); m.tag = 'x'; m.size"));
    }

    // ObjectDefineProperties walks every own key - including symbols - calling
    // getOwnPropertyDescriptor on each one in key order, not skipping symbol keys ahead of that call.
    @Test
    public void definePropertiesConsultsEveryOwnKeyIncludingSymbols() {
        assertEquals("0,foo,symbol", JsEval.str("""
                const target = {};
                const sym = Symbol();
                target[sym] = 1;
                target.foo = 2;
                target[0] = 3;
                const seen = [];
                const proxy = new Proxy(target, {
                    getOwnPropertyDescriptor(t, key) { seen.push(typeof key === 'symbol' ? 'symbol' : key); },
                });
                Object.defineProperties({}, proxy);
                seen.join(',')
                """));
    }
}
