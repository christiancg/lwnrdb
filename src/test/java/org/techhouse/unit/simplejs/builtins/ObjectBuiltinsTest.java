package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class ObjectBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool() {
        return ((JsBoolean) Interpreter.run("let o = {}; Object.freeze(o) === o")).getValue();
    }

    private static boolean flag(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // keys/values/entries enumerate own properties in insertion order
    @Test
    public void test_keys_values_entries() {
        assertEquals("a,b", str("Object.keys({a: 1, b: 2}).join(',')"));
        assertEquals("1,2", str("Object.values({a: 1, b: 2}).join(',')"));
        assertEquals("a=1,b=2", str("Object.entries({a: 1, b: 2}).map(e => e[0] + '=' + e[1]).join(',')"));
    }

    // keys/values also work over arrays (index keys)
    @Test
    public void test_over_arrays() {
        assertEquals("0,1", str("Object.keys(['x', 'y']).join(',')"));
        assertEquals("x,y", str("Object.values(['x', 'y']).join(',')"));
    }

    // assign copies own properties into the target and returns it
    @Test
    public void test_assign() {
        assertEquals(3, num("let t = Object.assign({a: 1}, {b: 2}); t.a + t.b"));
        assertEquals(9, num("let t = Object.assign({x: 1}, {x: 9}); t.x"));
    }

    // freeze blocks further writes, which the always-strict engine reports as a TypeError
    @Test
    public void test_freeze() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("let o = Object.freeze({a: 1}); o.a = 5"));
        assertEquals(1, num("let o = Object.freeze({a: 1}); try { o.a = 5; } catch (e) { } o.a"));
        assertTrue(bool());
    }

    // create sets the prototype; getPrototypeOf/setPrototypeOf round-trip
    @Test
    public void test_create_and_prototype_of() {
        assertEquals(1, num("let p = {a: 1}; let o = Object.create(p); o.a"));
        assertTrue(((JsBoolean) Interpreter.run("let p = {}; let o = Object.create(p); Object.getPrototypeOf(o) === p"))
                .getValue());
        assertEquals(7, num("let o = {}; Object.setPrototypeOf(o, {x: 7}); o.x"));
    }

    // Object.create(null) has a null prototype
    @Test
    public void test_create_null_proto() {
        assertInstanceOf(org.techhouse.simplejs.values.JsNull.class,
                Interpreter.run("Object.getPrototypeOf(Object.create(null))"));
    }

    // prototype-chain reads walk multiple links
    @Test
    public void test_prototype_chain_read() {
        assertEquals(9, num("let a = {x: 9}; let b = Object.create(a); let c = Object.create(b); c.x"));
    }

    // defineProperty with a value descriptor
    @Test
    public void test_define_property_value() {
        assertEquals(5, num("let o = {}; Object.defineProperty(o, 'v', {value: 5}); o.v"));
    }

    // defineProperty with an accessor descriptor invokes the getter/setter
    @Test
    public void test_define_property_accessor() {
        assertEquals(42, num(
                "let o = {n: 0}; Object.defineProperty(o, 'v', {get: function() { return 42; }, set: function(x) { this.n = x; }}); o.v"));
        assertEquals(8, num(
                "let o = {n: 0}; Object.defineProperty(o, 'v', {get: function() { return this.n; }, set: function(x) { this.n = x; }}); o.v = 8; o.n"));
    }

    // getOwnPropertyNames lists own string keys
    @Test
    public void test_get_own_property_names() {
        assertEquals("a,b", str("Object.getOwnPropertyNames({a: 1, b: 2}).join(',')"));
    }

    // getOwnPropertyDescriptor returns a data descriptor
    @Test
    public void test_get_own_property_descriptor() {
        assertEquals(3, num("Object.getOwnPropertyDescriptor({a: 3}, 'a').value"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("Object.getOwnPropertyDescriptor({}, 'missing')"));
    }

    // hasOwnProperty distinguishes own from inherited/absent keys
    @Test
    public void test_has_own_property() {
        assertTrue(((JsBoolean) Interpreter.run("({a: 1}).hasOwnProperty('a')")).getValue());
        assertFalse(((JsBoolean) Interpreter.run("({a: 1}).hasOwnProperty('b')")).getValue());
        assertFalse(((JsBoolean) Interpreter.run("let p = {a: 1}; let o = Object.create(p); o.hasOwnProperty('a')"))
                .getValue());
    }

    // fromEntries builds an object from an array of pairs
    @Test
    public void test_from_entries() {
        assertEquals(3, num("let o = Object.fromEntries([['a', 1], ['b', 2]]); o.a + o.b"));
    }

    // fromEntries consumes any iterable of pairs, not just arrays
    @Test
    public void test_from_entries_iterable() {
        final var source = """
                function* pairs() { yield ['a', 1]; yield ['b', 4]; }
                let o = Object.fromEntries(pairs());
                o.a + o.b
                """;
        assertEquals(5, num(source));
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
        assertEquals(3, num("let o = {}; Object.defineProperties(o, {a: {value: 1}, b: {value: 2}}); o.a + o.b"));
    }

    // create accepts a second properties argument
    @Test
    public void test_create_with_props() {
        assertEquals(9, num("let o = Object.create({}, {v: {value: 9}}); o.v"));
    }

    // a getter-only accessor makes assignment a no-op
    @Test
    public void test_getter_only_accessor() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("let o = {}; Object.defineProperty(o, 'v', {get: function() { return 1; }}); o.v = 5"));
        assertEquals(1, num(
                "let o = {}; Object.defineProperty(o, 'v', {get: function() { return 1; }}); try { o.v = 5; } catch (e) { } o.v"));
    }

    // getOwnPropertyDescriptor returns an accessor descriptor for accessors
    @Test
    public void test_get_own_property_descriptor_accessor() {
        assertEquals(4, num(
                "let o = {}; Object.defineProperty(o, 'v', {get: function() { return 4; }}); Object.getOwnPropertyDescriptor(o, 'v').get()"));
    }

    // getOwnPropertyNames over an array includes length
    @Test
    public void test_get_own_property_names_array() {
        assertEquals("0,1,length", str("Object.getOwnPropertyNames(['a', 'b']).join(',')"));
    }

    // setPrototypeOf with a non-object clears the prototype
    @Test
    public void test_set_prototype_of_null() {
        assertInstanceOf(org.techhouse.simplejs.values.JsNull.class, Interpreter
                .run("let o = Object.create({a: 1}); Object.setPrototypeOf(o, null); Object.getPrototypeOf(o)"));
    }

    // fromEntries ignores malformed entries and defaults missing values to undefined
    @Test
    public void test_from_entries_edge_cases() {
        assertEquals(1, num("Object.keys(Object.fromEntries([['a'], 5, ['b', 2]])).length - 1"));
    }

    // a non-writable data property ignores later assignment
    @Test
    public void test_define_property_non_writable() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("let o = {}; Object.defineProperty(o, 'v', {value: 1, writable: false}); o.v = 99"));
        assertEquals(1, num(
                "let o = {}; Object.defineProperty(o, 'v', {value: 1, writable: false}); try { o.v = 99; } catch (e) { } o.v"));
    }

    // a writable:true data property accepts later assignment
    @Test
    public void test_define_property_writable() {
        assertEquals(99, num("let o = {}; Object.defineProperty(o, 'v', {value: 1, writable: true}); o.v = 99; o.v"));
    }

    // a non-enumerable property is hidden from keys/values/entries and for-in, but visible to getOwnPropertyNames
    @Test
    public void test_define_property_non_enumerable() {
        final var setup = "let o = {a: 1}; Object.defineProperty(o, 'hidden', {value: 2, enumerable: false}); ";
        assertEquals("a", str(setup + "Object.keys(o).join(',')"));
        assertEquals("1", str(setup + "Object.values(o).join(',')"));
        assertEquals("a=1", str(setup + "Object.entries(o).map(e => e[0] + '=' + e[1]).join(',')"));
        assertEquals("a", str(setup + "let out = []; for (let k in o) out.push(k); out.join(',')"));
        assertEquals("a,hidden", str(setup + "Object.getOwnPropertyNames(o).join(',')"));
    }

    // a non-enumerable property is omitted from JSON.stringify
    @Test
    public void test_non_enumerable_json_stringify() {
        assertEquals("{\"a\":1}", str(
                "let o = {a: 1}; Object.defineProperty(o, 'hidden', {value: 2, enumerable: false}); JSON.stringify(o)"));
    }

    // propertyIsEnumerable reflects the real flag
    @Test
    public void test_property_is_enumerable() {
        final var setup = "let o = {a: 1}; Object.defineProperty(o, 'hidden', {value: 2, enumerable: false}); ";
        assertTrue(flag(setup + "o.propertyIsEnumerable('a')"));
        assertFalse(flag(setup + "o.propertyIsEnumerable('hidden')"));
        assertFalse(flag(setup + "o.propertyIsEnumerable('missing')"));
    }

    // freeze blocks add, modify and delete; isFrozen reports true
    @Test
    public void test_freeze_full() {
        assertEquals(1, num("let o = Object.freeze({a: 1}); try { o.a = 5; o.b = 9; } catch (e) { } o.a"));
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("let o = Object.freeze({a: 1}); try { o.b = 9; } catch (e) { } o.b"));
        assertEquals(1, num("let o = Object.freeze({a: 1}); try { delete o.a; } catch (e) { } o.a"));
        assertTrue(flag("Object.isFrozen(Object.freeze({a: 1}))"));
        assertFalse(flag("Object.isFrozen({a: 1})"));
    }

    // seal blocks adding keys but allows modifying existing ones; isSealed reports true
    @Test
    public void test_seal() {
        assertEquals(5, num("let o = Object.seal({a: 1}); o.a = 5; o.a"));
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("let o = Object.seal({a: 1}); try { o.b = 9; } catch (e) { } o.b"));
        assertEquals(1, num("let o = Object.seal({a: 1}); try { delete o.a; } catch (e) { } o.a"));
        assertTrue(flag("Object.isSealed(Object.seal({a: 1}))"));
        assertFalse(flag("Object.isSealed({a: 1})"));
        assertFalse(flag("Object.isFrozen(Object.seal({a: 1}))"));
    }

    // preventExtensions blocks new keys but keeps existing ones mutable; isExtensible reports the flag
    @Test
    public void test_prevent_extensions() {
        assertTrue(flag("Object.isExtensible({})"));
        assertFalse(flag("Object.isExtensible(Object.preventExtensions({}))"));
        assertEquals(5, num("let o = Object.preventExtensions({a: 1}); o.a = 5; o.a"));
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("let o = Object.preventExtensions({a: 1}); try { o.b = 9; } catch (e) { } o.b"));
    }

    // an empty non-extensible object is both sealed and frozen
    @Test
    public void test_empty_non_extensible_is_frozen() {
        assertTrue(flag("Object.isFrozen(Object.preventExtensions({}))"));
        assertTrue(flag("Object.isSealed(Object.preventExtensions({}))"));
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
        assertEquals(2, num("let o = {}; Object.defineProperty(o, 'v', {value: 1, configurable: true});"
                + "Object.defineProperty(o, 'v', {value: 2}); o.v"));
    }

    // getOwnPropertyDescriptor reports the real flags of a defined property
    @Test
    public void test_get_own_property_descriptor_flags() {
        final var setup = "let o = {}; Object.defineProperty(o, 'v', "
                + "{value: 7, writable: false, enumerable: false, configurable: false}); ";
        assertFalse(flag(setup + "Object.getOwnPropertyDescriptor(o, 'v').writable"));
        assertFalse(flag(setup + "Object.getOwnPropertyDescriptor(o, 'v').enumerable"));
        assertFalse(flag(setup + "Object.getOwnPropertyDescriptor(o, 'v').configurable"));
        assertEquals(7, num(setup + "Object.getOwnPropertyDescriptor(o, 'v').value"));
    }

    // a normally-assigned property stays writable and enumerable (no regression)
    @Test
    public void test_normal_property_defaults() {
        final var setup = "let o = {}; o.x = 1; ";
        assertTrue(flag(setup + "Object.getOwnPropertyDescriptor(o, 'x').writable"));
        assertTrue(flag(setup + "Object.getOwnPropertyDescriptor(o, 'x').enumerable"));
        assertTrue(flag(setup + "Object.getOwnPropertyDescriptor(o, 'x').configurable"));
        assertTrue(flag(setup + "o.propertyIsEnumerable('x')"));
        assertEquals(5, num(setup + "o.x = 5; o.x"));
    }

    // defineProperty defaults unspecified attributes to false for a new property
    @Test
    public void test_define_property_defaults_false() {
        final var setup = "let o = {}; Object.defineProperty(o, 'v', {value: 1}); ";
        assertFalse(flag(setup + "Object.getOwnPropertyDescriptor(o, 'v').writable"));
        assertFalse(flag(setup + "Object.getOwnPropertyDescriptor(o, 'v').enumerable"));
        assertFalse(flag(setup + "Object.getOwnPropertyDescriptor(o, 'v').configurable"));
    }

    // delete returns false for a non-configurable property and true for a configurable one
    @Test
    public void test_delete_configurability() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let o = {}; Object.defineProperty(o, 'v', {value: 1}); delete o.v"));
        assertFalse(flag("let o = {}; Object.defineProperty(o, 'v', {value: 1}); Reflect.deleteProperty(o, 'v')"));
        assertTrue(flag("let o = {a: 1}; delete o.a"));
    }

    // Object.hasOwn reports own (not inherited) properties on objects and arrays
    @Test
    public void test_has_own() {
        assertTrue(flag("Object.hasOwn({a: 1}, 'a')"));
        assertFalse(flag("Object.hasOwn({a: 1}, 'b')"));
        assertFalse(flag("let p = {a: 1}; let o = Object.create(p); Object.hasOwn(o, 'a')"));
        assertTrue(flag("Object.hasOwn([9], 0)"));
        assertTrue(flag("Object.hasOwn([9], 'length')"));
        assertFalse(flag("Object.hasOwn([9], 1)"));
        assertFalse(flag("Object.hasOwn(5, 'x')"));
    }

    // Object.groupBy buckets items by the callback's stringified key, in encounter order
    @Test
    public void test_group_by() {
        final var setup = "let g = Object.groupBy([1, 2, 3, 4], n => n % 2 === 0 ? 'even' : 'odd'); ";
        assertEquals("1,3", str(setup + "g.odd.join(',')"));
        assertEquals("2,4", str(setup + "g.even.join(',')"));
    }

    // Object.groupBy consumes any iterable and exposes the callback index
    @Test
    public void test_group_by_iterable_index() {
        final var source = "let g = Object.groupBy(new Set(['a', 'b', 'c']), (_, i) => i < 2 ? 'lo' : 'hi'); "
                + "g.lo.join(',') + '|' + g.hi.join(',')";
        assertEquals("a,b|c", str(source));
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
                num("let o = {}; let g = function () { return 1; }; "
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
        assertTrue(flag("let o = {}; Object.defineProperty(o, 'x', "
                + "{ value: Number.NaN, writable: false, configurable: false }); "
                + "Object.defineProperty(o, 'x', { value: Number.NaN }); true"));
    }

    // A configurable property may freely switch between data and accessor forms
    @Test
    public void test_configurable_redefine_allowed() {
        assertEquals(9, num("let o = {}; Object.defineProperty(o, 'x', { value: 1, configurable: true }); "
                + "Object.defineProperty(o, 'x', { get() { return 9; } }); o.x"));
    }

    // Object.is implements SameValue
    @Test
    public void test_object_is() {
        assertTrue(bool2("Object.is(NaN, NaN)"));
        assertFalse(bool2("Object.is(0, -0)"));
        assertTrue(bool2("Object.is(0, 0)"));
        assertTrue(bool2("Object.is(1, 1)"));
        assertFalse(bool2("Object.is('a', 'b')"));
        assertTrue(bool2("const o = {}; Object.is(o, o)"));
        assertFalse(bool2("Object.is({}, {})"));
        assertFalse(bool2("Object.is(1)"));
    }

    // Object.getOwnPropertySymbols lists symbol-keyed own properties
    @Test
    public void test_get_own_property_symbols() {
        assertEquals(1, num("const s = Symbol('k'); Object.getOwnPropertySymbols({[s]: 1}).length"));
        assertEquals(0, num("Object.getOwnPropertySymbols({a: 1}).length"));
        assertEquals(0, num("Object.getOwnPropertySymbols(1).length"));
        assertTrue(bool2("const s = Symbol('k'); Object.getOwnPropertySymbols({[s]: 1})[0] === s"));
    }

    // assign and spread copy symbol-keyed properties
    @Test
    public void test_symbol_keys_are_copied() {
        assertEquals(1, num("const s = Symbol('k'); Object.assign({}, {[s]: 1})[s]"));
        assertEquals(1, num("const s = Symbol('k'); ({...{[s]: 1}})[s]"));
        assertEquals(1, num("const s = Symbol('k'); const {...rest} = {[s]: 1}; rest[s]"));
    }

    private static boolean bool2(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }
}
