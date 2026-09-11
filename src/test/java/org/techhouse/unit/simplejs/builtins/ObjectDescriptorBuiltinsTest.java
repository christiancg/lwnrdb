package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.test.JsEval;

public class ObjectDescriptorBuiltinsTest {
    // getOwnPropertyDescriptor returns a data descriptor
    @Test
    public void test_get_own_property_descriptor() {
        assertEquals(3, JsEval.num("Object.getOwnPropertyDescriptor({a: 3}, 'a').value"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("Object.getOwnPropertyDescriptor({}, 'missing')"));
    }

    // getOwnPropertyDescriptor returns an accessor descriptor for accessors
    @Test
    public void test_get_own_property_descriptor_accessor() {
        assertEquals(4, JsEval.num(
                "let o = {}; Object.defineProperty(o, 'v', {get: function() { return 4; }}); Object.getOwnPropertyDescriptor(o, 'v').get()"));
    }

    // getOwnPropertyDescriptor reports the real flags of a defined property
    @Test
    public void test_get_own_property_descriptor_flags() {
        final var setup = "let o = {}; Object.defineProperty(o, 'v', "
                + "{value: 7, writable: false, enumerable: false, configurable: false}); ";
        assertFalse(JsEval.bool(setup + "Object.getOwnPropertyDescriptor(o, 'v').writable"));
        assertFalse(JsEval.bool(setup + "Object.getOwnPropertyDescriptor(o, 'v').enumerable"));
        assertFalse(JsEval.bool(setup + "Object.getOwnPropertyDescriptor(o, 'v').configurable"));
        assertEquals(7, JsEval.num(setup + "Object.getOwnPropertyDescriptor(o, 'v').value"));
    }

    // delete returns false for a non-configurable property and true for a configurable one
    @Test
    public void test_delete_configurability() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let o = {}; Object.defineProperty(o, 'v', {value: 1}); delete o.v"));
        assertFalse(
                JsEval.bool("let o = {}; Object.defineProperty(o, 'v', {value: 1}); Reflect.deleteProperty(o, 'v')"));
        assertTrue(JsEval.bool("let o = {a: 1}; delete o.a"));
    }

    // getOwnPropertyDescriptors reports every own key's descriptor
    @Test
    public void test_get_own_property_descriptors_data_property() {
        final var source = """
                const d = Object.getOwnPropertyDescriptors({a: 1});
                d.a.value + '|' + d.a.writable + '|' + d.a.enumerable + '|' + d.a.configurable
                """;
        assertEquals("1|true|true|true", JsEval.str(source));
    }

    // an accessor descriptor carries its get and set functions
    @Test
    public void test_get_own_property_descriptors_accessor() {
        final var source = """
                const d = Object.getOwnPropertyDescriptors({ get x() { return 1; }, set x(v) {} });
                typeof d.x.get + '|' + typeof d.x.set
                """;
        assertEquals("function|function", JsEval.str(source));
    }

    // a non-enumerable key is still described
    @Test
    public void test_get_own_property_descriptors_includes_non_enumerable() {
        final var source = """
                const o = {};
                Object.defineProperty(o, 'x', { value: 1 });
                Object.getOwnPropertyDescriptors(o).x.value
                """;
        assertEquals(1, JsEval.num(source));
    }

    // symbol keys are described alongside string keys
    @Test
    public void test_get_own_property_descriptors_includes_symbol() {
        final var source = """
                const s = Symbol('s');
                const o = { [s]: 3 };
                Object.getOwnPropertyDescriptors(o)[s].value
                """;
        assertEquals(3, JsEval.num(source));
    }

    // an empty object yields an empty descriptor map
    @Test
    public void test_get_own_property_descriptors_empty() {
        assertEquals(0, JsEval.num("Object.keys(Object.getOwnPropertyDescriptors({})).length"));
    }

    // ToObject(O) rejects only null/undefined; another primitive simply has no own properties
    @Test
    public void test_get_own_property_descriptors_non_object_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.getOwnPropertyDescriptors(undefined)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.getOwnPropertyDescriptors(null)"));
        assertEquals(0, JsEval.num("Object.keys(Object.getOwnPropertyDescriptors(1)).length"));
    }

    // the name descriptor is non-writable, non-enumerable and configurable
    @Test
    public void test_function_name_descriptor_attributes() {
        final var source = """
                function foo(a, b){}
                const d = Object.getOwnPropertyDescriptor(foo, 'name');
                JSON.stringify([d.value, d.writable, d.enumerable, d.configurable])
                """;
        assertEquals("[\"foo\",false,false,true]", JsEval.str(source));
    }

    // the prototype descriptor is writable, non-enumerable and non-configurable
    @Test
    public void test_function_prototype_descriptor_attributes() {
        final var source = """
                function foo(){}
                const d = Object.getOwnPropertyDescriptor(foo, 'prototype');
                JSON.stringify([d.value === foo.prototype, d.writable, d.enumerable, d.configurable])
                """;
        assertEquals("[true,true,false,false]", JsEval.str(source));
    }

    // a declared global reports a data descriptor on globalThis
    @Test
    public void test_get_own_property_descriptor_of_a_global() {
        final var source = """
                var gg = 7;
                const d = Object.getOwnPropertyDescriptor(globalThis, 'gg');
                JSON.stringify([d.value, d.writable, d.enumerable, d.configurable])
                """;
        assertEquals("[7,true,true,false]", JsEval.str(source));
        assertTrue(JsEval.bool("Object.getOwnPropertyDescriptor(globalThis, 'neverDeclared') === undefined"));
    }

    // getOwnPropertyDescriptor with a missing key argument or a non-object receiver returns undefined
    @Test
    public void test_get_own_property_descriptor_missing_arg_or_non_object() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("Object.getOwnPropertyDescriptor({})"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("Object.getOwnPropertyDescriptor(5, 'x')"));
    }

    // a symbol key is not reflected in a function's descriptor lookup (functions have no symbol storage)
    @Test
    public void test_get_own_property_descriptor_of_function_with_symbol_key() {
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("Object.getOwnPropertyDescriptor(function() {}, Symbol('x'))"));
    }

    // the prototype metadata descriptor of a native constructor reports its real .prototype
    @Test
    public void test_get_own_property_descriptor_of_native_constructor_prototype() {
        assertTrue(JsEval.bool("Object.getOwnPropertyDescriptor(Array, 'prototype').value === Array.prototype"));
    }

    // a symbol key on globalThis's descriptor lookup returns undefined
    @Test
    public void test_get_own_property_descriptor_of_global_with_symbol_key() {
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("Object.getOwnPropertyDescriptor(globalThis, Symbol('x'))"));
    }

    // a symbol never assigned on the object reports no descriptor
    @Test
    public void test_get_own_property_descriptor_of_absent_symbol() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("Object.getOwnPropertyDescriptor({}, Symbol('x'))"));
    }

    // A descriptor carrying only enumerable/configurable leaves an existing accessor intact
    @Test
    public void test_generic_descriptor_preserves_existing_accessor() {
        assertTrue(
                JsEval.bool("const o = {}; Object.defineProperty(o, 'x', { get() { return 5; }, configurable: true });"
                        + "Object.defineProperty(o, 'x', { enumerable: true });"
                        + "typeof Object.getOwnPropertyDescriptor(o, 'x').get === 'function' && o.x === 5"));
    }

    // A symbol-keyed defineProperty stores its flags instead of always reporting all-true
    @Test
    public void test_symbol_descriptor_stores_flags() {
        assertTrue(JsEval.bool("const s = Symbol('s'); const o = {};"
                + "Object.defineProperty(o, s, { value: 1, enumerable: false, configurable: false });"
                + "const d = Object.getOwnPropertyDescriptor(o, s);"
                + "d.value === 1 && d.enumerable === false && d.configurable === false"));
    }

    // A builtin constructor's `prototype` is non-writable, non-enumerable and non-configurable
    @Test
    public void test_builtin_constructor_prototype_descriptor() {
        assertTrue(JsEval.bool("const d = Object.getOwnPropertyDescriptor(Array, 'prototype');"
                + "!d.writable && !d.enumerable && !d.configurable"));
        assertTrue(JsEval.bool("const d = Object.getOwnPropertyDescriptor(function f() {}, 'prototype');"
                + "d.writable && !d.enumerable && !d.configurable"));
    }

    @Test
    public void accessorDescriptorsAreValidated() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty({}, 'x', { get() { return 1; }, value: 1 })"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty({}, 'x', { set(v) {}, writable: true })"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.defineProperty({}, 'x', { get: 1 })"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.defineProperty({}, 'x', { set: 1 })"));
    }

    @Test
    public void redefiningAnAccessorClearsTheSideTheDescriptorNames() {
        assertTrue(JsEval.bool("""
                const o = {};
                Object.defineProperty(o, 'x', { get() { return 1; }, set(v) {}, configurable: true });
                Object.defineProperty(o, 'x', { set: undefined, configurable: true });
                const d = Object.getOwnPropertyDescriptor(o, 'x');
                typeof d.get === 'function' && d.set === undefined
                """));
        assertTrue(JsEval.bool("""
                const o = {};
                Object.defineProperty(o, 'x', { get() { return 1; }, set(v) {}, configurable: true });
                Object.defineProperty(o, 'x', { get: undefined, configurable: true });
                const d = Object.getOwnPropertyDescriptor(o, 'x');
                d.get === undefined && typeof d.set === 'function'
                """));
    }

    @Test
    public void symbolKeyedDescriptorsGoThroughTheSameSlotProtocol() {
        assertTrue(JsEval.bool("""
                const s = Symbol('s');
                const o = {};
                Object.defineProperty(o, s, { get() { return 1; }, configurable: true });
                Object.defineProperty(o, s, { set(v) {}, configurable: true });
                const d = Object.getOwnPropertyDescriptor(o, s);
                typeof d.get === 'function' && typeof d.set === 'function'
                """));
        assertTrue(JsEval.bool("""
                const s = Symbol('s');
                const o = {};
                Object.defineProperty(o, s, { get() { return 1; }, set(v) {}, configurable: true });
                Object.defineProperty(o, s, { get: undefined, configurable: true });
                Object.defineProperty(o, s, { set: undefined, configurable: true });
                const d = Object.getOwnPropertyDescriptor(o, s);
                d.get === undefined && d.set === undefined
                """));
        assertEquals(5, JsEval.num("""
                const s = Symbol('s');
                const o = {};
                Object.defineProperty(o, s, { get() { return 1; }, configurable: true });
                Object.defineProperty(o, s, { value: 5, configurable: true });
                o[s]
                """));
    }

    @Test
    public void ownPropertyDescriptorsWalkSymbolsAndSkipAbsentProxyKeys() {
        assertEquals(0, JsEval.num(
                "Object.keys(Object.getOwnPropertyDescriptors(new Proxy({}, " + "{ ownKeys: () => ['a'] }))).length"));
        assertTrue(JsEval.bool("""
                const s = Symbol('s');
                const o = {};
                o[s] = 1;
                Object.getOwnPropertyDescriptors(o)[s].value === 1
                """));
    }

    // ToPropertyDescriptor accepts any object (a function, an array, a Date), not just a literal
    @Test
    public void acceptsNonPlainObjectDescriptor() {
        assertEquals(5, JsEval.num("""
                const o = {};
                const d = function () {};
                d.value = 5;
                Object.defineProperty(o, 'x', d);
                o.x
                """));
        assertEquals(4, JsEval.num("""
                const o = {};
                const d = new Proxy({}, { get: (_, k) => 4, has: (_, k) => k === 'value' });
                Object.defineProperty(o, 'x', d);
                o.x
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.defineProperty({}, 'x', 1)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.defineProperty({}, 'x', { get: 1 })"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty({}, 'x', { get() {}, value: 1 })"));
    }

    // The descriptor object FromPropertyDescriptor builds is itself proto-linked to Object.prototype
    // (both the single- and the batch- getOwnPropertyDescriptor forms).
    @Test
    public void getOwnPropertyDescriptorResultIsAnInstanceOfObject() {
        assertTrue(JsEval.bool("Object.getOwnPropertyDescriptor({p: 1}, 'p') instanceof Object"));
        assertTrue(JsEval.bool("Object.getPrototypeOf(Object.getOwnPropertyDescriptors({})) === Object.prototype"));
    }

    // Object.values/entries over a Proxy interleave getOwnPropertyDescriptor and get per key (not a
    // getOwnPropertyDescriptor batch followed by a get batch).
    @Test
    public void valuesOverAProxyInterleavesDescriptorCheckAndGetPerKey() {
        assertEquals("|ownKeys|getOwnPropertyDescriptor:a|get:a|getOwnPropertyDescriptor:b|get:b", JsEval
                .str("""
                        let log = '';
                        const object = { a: 1, b: 2 };
                        const proxy = new Proxy(object, {
                            get(t, k) { log += '|get:' + k; return t[k]; },
                            getOwnPropertyDescriptor(t, k) { log += '|getOwnPropertyDescriptor:' + k; return Object.getOwnPropertyDescriptor(t, k); },
                            ownKeys(t) { log += '|ownKeys'; return Object.getOwnPropertyNames(t); },
                        });
                        Object.values(proxy);
                        log
                        """));
    }
}
