package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
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

    // fromEntries defaults a missing "1" property to undefined, but a non-object entry - even one
    // reached after already-valid entries - throws per AddEntriesFromIterable step 3.c (Type(next)
    // is not Object), it does not just skip that one entry.
    @Test
    public void test_from_entries_edge_cases() {
        assertEquals(1, num("Object.keys(Object.fromEntries([['a']])).length"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.fromEntries([['a'], 5, ['b', 2]])"));
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

    // an accessor property is an own key, so Object.keys lists it
    @Test
    public void test_object_keys_includes_accessor() {
        assertEquals("x", str("Object.keys({get x() { return 1; }}).join(',')"));
    }

    // data and accessor properties share one insertion-ordered own-key list
    @Test
    public void test_own_key_order_mixes_data_and_accessor() {
        assertEquals("a,b,c", str("Object.keys({a: 1, get b() { return 2; }, c: 3}).join(',')"));
    }

    // Object.values invokes the getter rather than reporting undefined
    @Test
    public void test_object_values_invokes_getter() {
        assertEquals("1", str("Object.values({get x() { return 1; }}).join(',')"));
    }

    // Object.entries invokes the getter
    @Test
    public void test_object_entries_invokes_getter() {
        assertEquals("x,1", str("Object.entries({get x() { return 1; }})[0].join(',')"));
    }

    // Object.assign copies the getter's value, not the accessor itself
    @Test
    public void test_object_assign_copies_getter_value() {
        final var source = """
                let calls = 0;
                const src = { get x() { calls++; return 5; } };
                const target = Object.assign({}, src);
                target.x + ',' + target.x + ',' + calls
                """;
        assertEquals("5,5,1", str(source));
    }

    // getOwnPropertyNames lists accessor keys too
    @Test
    public void test_get_own_property_names_includes_accessor() {
        assertEquals("x", str("Object.getOwnPropertyNames({get x() { return 1; }}).join(',')"));
    }

    // an accessor defined with enumerable:true participates in enumeration
    @Test
    public void test_define_property_enumerable_accessor_is_enumerated() {
        final var source = """
                const o = {};
                Object.defineProperty(o, 'x', { get() { return 7; }, enumerable: true });
                Object.keys(o).join(',') + '|' + Object.values(o).join(',')
                """;
        assertEquals("x|7", str(source));
    }

    // a non-enumerable accessor stays hidden from keys but visible to getOwnPropertyNames
    @Test
    public void test_non_enumerable_accessor_is_skipped() {
        final var source = """
                const o = {};
                Object.defineProperty(o, 'x', { get() { return 7; } });
                Object.keys(o).length + '|' + Object.getOwnPropertyNames(o).join(',')
                """;
        assertEquals("0|x", str(source));
    }

    // a setter-only property enumerates with an undefined value
    @Test
    public void test_setter_only_property_enumerates_as_undefined() {
        final var source = """
                const o = { set x(v) {} };
                Object.keys(o).join(',') + '|' + String(Object.values(o)[0])
                """;
        assertEquals("x|undefined", str(source));
    }

    // delete drops the accessor entries along with the key
    @Test
    public void test_delete_removes_accessor() {
        final var source = """
                const o = { get x() { return 1; } };
                delete o.x;
                String(o.x) + '|' + Object.keys(o).length
                """;
        assertEquals("undefined|0", str(source));
    }

    // freeze and seal still cover accessor keys after the ownKeys() collapse
    @Test
    public void test_freeze_still_covers_accessors() {
        assertTrue(flag("const o = { get x() { return 1; } }; Object.freeze(o); Object.isFrozen(o)"));
        assertTrue(flag("const o = { get x() { return 1; } }; Object.seal(o); Object.isSealed(o)"));
        assertFalse(flag("const o = { a: 1, get x() { return 1; } }; Object.seal(o); Object.isFrozen(o)"));
    }

    // canonical array-index keys still sort ahead of the insertion-ordered rest
    @Test
    public void test_array_index_key_order_unaffected() {
        assertEquals("1,2,b,a", str("Object.keys({b: 1, 2: 1, a: 1, 1: 1}).join(',')"));
    }

    // getOwnPropertyDescriptors reports every own key's descriptor
    @Test
    public void test_get_own_property_descriptors_data_property() {
        final var source = """
                const d = Object.getOwnPropertyDescriptors({a: 1});
                d.a.value + '|' + d.a.writable + '|' + d.a.enumerable + '|' + d.a.configurable
                """;
        assertEquals("1|true|true|true", str(source));
    }

    // an accessor descriptor carries its get and set functions
    @Test
    public void test_get_own_property_descriptors_accessor() {
        final var source = """
                const d = Object.getOwnPropertyDescriptors({ get x() { return 1; }, set x(v) {} });
                typeof d.x.get + '|' + typeof d.x.set
                """;
        assertEquals("function|function", str(source));
    }

    // a non-enumerable key is still described
    @Test
    public void test_get_own_property_descriptors_includes_non_enumerable() {
        final var source = """
                const o = {};
                Object.defineProperty(o, 'x', { value: 1 });
                Object.getOwnPropertyDescriptors(o).x.value
                """;
        assertEquals(1, num(source));
    }

    // symbol keys are described alongside string keys
    @Test
    public void test_get_own_property_descriptors_includes_symbol() {
        final var source = """
                const s = Symbol('s');
                const o = { [s]: 3 };
                Object.getOwnPropertyDescriptors(o)[s].value
                """;
        assertEquals(3, num(source));
    }

    // an empty object yields an empty descriptor map
    @Test
    public void test_get_own_property_descriptors_empty() {
        assertEquals(0, num("Object.keys(Object.getOwnPropertyDescriptors({})).length"));
    }

    // ToObject(O) rejects only null/undefined; another primitive simply has no own properties
    @Test
    public void test_get_own_property_descriptors_non_object_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.getOwnPropertyDescriptors(undefined)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.getOwnPropertyDescriptors(null)"));
        assertEquals(0, num("Object.keys(Object.getOwnPropertyDescriptors(1)).length"));
    }

    // a function's name is an own property, not only a lookup-time synthesis
    @Test
    public void test_function_name_is_an_own_property() {
        assertTrue(flag("Object.prototype.hasOwnProperty.call(Array.prototype.join, 'name')"));
        assertTrue(flag("function f(){} Object.hasOwn(f, 'name')"));
    }

    // a function's length is an own property
    @Test
    public void test_function_length_is_an_own_property() {
        assertTrue(flag("function f(a, b){} Object.hasOwn(f, 'length')"));
    }

    // the name descriptor is non-writable, non-enumerable and configurable
    @Test
    public void test_function_name_descriptor_attributes() {
        final var source = """
                function foo(a, b){}
                const d = Object.getOwnPropertyDescriptor(foo, 'name');
                JSON.stringify([d.value, d.writable, d.enumerable, d.configurable])
                """;
        assertEquals("[\"foo\",false,false,true]", str(source));
    }

    // the prototype descriptor is writable, non-enumerable and non-configurable
    @Test
    public void test_function_prototype_descriptor_attributes() {
        final var source = """
                function foo(){}
                const d = Object.getOwnPropertyDescriptor(foo, 'prototype');
                JSON.stringify([d.value === foo.prototype, d.writable, d.enumerable, d.configurable])
                """;
        assertEquals("[true,true,false,false]", str(source));
    }

    // getOwnPropertyNames lists the synthesised metadata alongside script-assigned keys
    @Test
    public void test_get_own_property_names_of_a_function_includes_name_and_length() {
        assertEquals("[\"length\",\"name\",\"prototype\",\"x\"]",
                str("function f(a, b){} f.x = 1; JSON.stringify(Object.getOwnPropertyNames(f))"));
    }

    // a declared global reports a data descriptor on globalThis
    @Test
    public void test_get_own_property_descriptor_of_a_global() {
        final var source = """
                var gg = 7;
                const d = Object.getOwnPropertyDescriptor(globalThis, 'gg');
                JSON.stringify([d.value, d.writable, d.enumerable, d.configurable])
                """;
        assertEquals("[7,true,true,false]", str(source));
        assertTrue(flag("Object.getOwnPropertyDescriptor(globalThis, 'neverDeclared') === undefined"));
    }

    // Object() called as a plain function coerces a primitive to a plain object but returns an
    // object/array/function argument unchanged
    @Test
    public void test_object_called_as_function() {
        assertTrue(bool2("typeof Object(5) === 'object'"));
        assertTrue(bool2("let o = {a: 1}; Object(o) === o"));
        assertTrue(bool2("let a = [1]; Object(a) === a"));
        assertTrue(bool2("let f = function() {}; Object(f) === f"));
        assertTrue(bool2("typeof Object() === 'object'"));
    }

    // Object.hasOwn with a missing second argument reports false
    @Test
    public void test_has_own_requires_two_args() {
        assertFalse(flag("Object.hasOwn({a: 1})"));
    }

    // Object.hasOwn reports own index presence on a typed array and a script-assigned array property
    @Test
    public void test_has_own_typed_array_and_array_custom_property() {
        assertTrue(flag("Object.hasOwn(new Int8Array(3), 1)"));
        assertFalse(flag("Object.hasOwn(new Int8Array(3), 5)"));
        assertTrue(flag("let a = []; a.custom = 1; Object.hasOwn(a, 'custom')"));
        assertFalse(flag("Object.hasOwn([], 'missing')"));
    }

    // Object.values/entries over a proxy re-filter down to enumerable string keys via the ownKeys and
    // getOwnPropertyDescriptor traps, falling back to the target when the traps are absent
    @Test
    public void test_values_entries_over_proxy() {
        assertEquals("1,2", str("Object.values(new Proxy({a: 1, b: 2}, {})).join(',')"));
        assertEquals("a=1,b=2",
                str("Object.entries(new Proxy({a: 1, b: 2}, {})).map(e => e[0] + '=' + e[1]).join(',')"));
    }

    // Object.values/entries over a callable read its script-assigned enumerable properties
    @Test
    public void test_values_entries_over_function() {
        assertEquals("1", str("function f() {} f.x = 1; Object.values(f).join(',')"));
        assertEquals("x=1", str("function f() {} f.x = 1; Object.entries(f).map(e => e[0] + '=' + e[1]).join(',')"));
    }

    // Object.entries over an array pairs each index string with its element
    @Test
    public void test_entries_over_array() {
        assertEquals("0=a,1=b", str("Object.entries(['a', 'b']).map(e => e[0] + '=' + e[1]).join(',')"));
    }

    // assign with a non-object target and no sources returns the target argument unchanged
    @Test
    public void test_assign_non_object_target() {
        // Object.assign always ToObjects the target, even with no sources at all, so a primitive
        // target comes back wrapped rather than unchanged.
        assertEquals("object", str("typeof Object.assign(5)"));
        assertEquals(5, num("Object.assign(5).valueOf()"));
    }

    // isFrozen/isSealed/isExtensible report the trivial defaults for non-object, non-array values
    @Test
    public void test_is_frozen_sealed_extensible_on_primitives() {
        assertTrue(flag("Object.isFrozen(5)"));
        assertTrue(flag("Object.isSealed('a')"));
        assertFalse(flag("Object.isExtensible(true)"));
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

    // getOwnPropertyNames over a proxy delegates to the ownKeys trap, falling back to the target
    @Test
    public void test_get_own_property_names_over_proxy() {
        assertEquals("a,b", str("Object.getOwnPropertyNames(new Proxy({a: 1, b: 2}, {})).join(',')"));
    }

    // getOwnPropertyNames over globalThis lists every declared global name, not just enumerable ones
    @Test
    public void test_get_own_property_names_over_global_this() {
        assertTrue(flag("Object.getOwnPropertyNames(globalThis).includes('NaN')"));
    }

    // getOwnPropertyNames of an arrow function (no prototype) omits 'prototype' from the metadata keys
    @Test
    public void test_get_own_property_names_of_arrow_function_omits_prototype() {
        assertEquals("length,name", str("Object.getOwnPropertyNames(() => {}).join(',')"));
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
        assertTrue(bool2("Object.getOwnPropertyDescriptor(Array, 'prototype').value === Array.prototype"));
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

    // Object.defineProperty/defineProperties on an Array: data descriptors for indices, "length",
    // and named own properties, matching the same flags/redefinition rules as a plain object.
    @Test
    public void test_define_property_on_array_index() {
        assertEquals(1001,
                num("""
                        var arr = [1];
                        Object.defineProperty(arr, "0", { value: 1001, writable: false, enumerable: false, configurable: false });
                        arr[0]
                        """));
        assertTrue(
                bool2("""
                        var arr = [1];
                        Object.defineProperty(arr, "0", { value: 1001, writable: false, enumerable: false, configurable: false });
                        var d = Object.getOwnPropertyDescriptor(arr, "0");
                        d.value === 1001 && d.writable === false && d.enumerable === false && d.configurable === false
                        """));
    }

    @Test
    public void test_define_property_on_array_new_index_beyond_length() {
        assertTrue(bool2("""
                var arr = [];
                Object.defineProperty(arr, "2", { value: 9 });
                arr.length === 3 && arr[2] === 9 && arr[0] === undefined
                """));
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
    public void test_define_property_on_array_length() {
        assertTrue(bool2("""
                var arr = [1, 2, 3];
                Object.defineProperty(arr, "length", { value: 1 });
                arr.length === 1 && arr[1] === undefined
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var arr = [1];
                Object.defineProperty(arr, "length", { writable: false });
                Object.defineProperty(arr, "length", { value: 5 });
                """));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'length', { get() { return 1; } })"));
    }

    @Test
    public void test_define_property_on_array_named_property() {
        assertTrue(
                bool2("""
                        var arr = [];
                        Object.defineProperty(arr, "foo", { value: "bar", enumerable: true, writable: true, configurable: true });
                        arr.foo === "bar" && Object.keys(arr).includes("foo")
                        """));
    }

    @Test
    public void test_define_property_accessor_on_array_named_property() {
        assertTrue(bool2("""
                var arr = [];
                var log = [];
                Object.defineProperty(arr, "foo", {
                  get() { return 42; },
                  set(v) { log.push(v); },
                  enumerable: true,
                  configurable: true
                });
                arr.foo = 7;
                arr.foo === 42 && log[0] === 7
                """));
    }

    @Test
    public void test_define_property_accessor_on_array_index() {
        assertTrue(bool2("""
                var arr = [];
                Object.defineProperty(arr, "0", { get() { return 5; }, enumerable: true, configurable: true });
                arr[0] === 5 && arr.length === 1
                """));
    }

    @Test
    public void test_define_property_accessor_with_value_throws() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], '0', { get() {}, value: 1 })"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'foo', { get() {}, value: 1 })"));
    }

    @Test
    public void test_define_properties_on_array() {
        assertTrue(bool2("""
                var arr = [1, 2];
                Object.defineProperties(arr, { "0": { value: 9 }, length: { value: 1 } });
                arr[0] === 9 && arr.length === 1
                """));
    }

    @Test
    public void test_array_delete_configurable_makes_hole() {
        assertTrue(bool2("""
                var arr = [1, 2, 3];
                delete arr[1];
                !(1 in arr) && arr.length === 3
                """));
    }

    @Test
    public void test_array_delete_non_configurable_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var arr = [1];
                Object.defineProperty(arr, "0", { configurable: false });
                delete arr[0];
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var arr = [];
                Object.defineProperty(arr, "foo", { value: 1, configurable: false });
                delete arr.foo;
                """));
    }

    @Test
    public void test_array_delete_absent_or_named_property() {
        assertTrue(bool2("var arr = [1]; delete arr[5]"));
        assertTrue(bool2("""
                var arr = [];
                arr.foo = 1;
                delete arr.foo;
                !('foo' in arr)
                """));
    }

    @Test
    public void test_object_extension_checks_on_array() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("var arr = []; Object.preventExtensions(arr); Object.defineProperty(arr, '0', {value: 1});"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("var arr = []; Object.preventExtensions(arr); Object.defineProperty(arr, 'x', {value: 1});"));
    }

    // keys/values/entries/getOwnPropertyNames on an Array reflect enumerability and skip holes
    @Test
    public void test_array_keys_values_entries_respect_enumerable_and_holes() {
        assertTrue(bool2("""
                var arr = [1, 2];
                Object.defineProperty(arr, "0", { enumerable: false });
                Object.keys(arr).join(',') === '1'
                """));
        assertTrue(bool2("""
                var arr = [1, 2];
                Object.defineProperty(arr, "0", { enumerable: false });
                Object.values(arr).join(',') === '2'
                """));
        assertTrue(bool2("""
                var arr = [1, 2];
                Object.defineProperty(arr, "0", { enumerable: false });
                Object.entries(arr).length === 1
                """));
        assertTrue(bool2("""
                var arr = [1, 2, 3];
                delete arr[1];
                Object.getOwnPropertyNames(arr).join(',') === '0,2,length'
                """));
        assertTrue(bool2("""
                var arr = [];
                arr.foo = 1;
                Object.getOwnPropertyNames(arr).includes('foo')
                """));
    }

    @Test
    public void test_for_in_over_array_respects_enumerable_and_holes() {
        assertTrue(bool2("""
                var arr = [1, 2, 3];
                delete arr[1];
                var seen = [];
                for (var k in arr) { seen.push(k); }
                seen.join(',') === '0,2'
                """));
        assertTrue(bool2("""
                var arr = [];
                arr.foo = 1;
                var seen = [];
                for (var k in arr) { seen.push(k); }
                seen.join(',') === 'foo'
                """));
    }

    @Test
    public void test_array_index_get_own_property_descriptor_hole_is_undefined() {
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("var arr = [1,2,3]; delete arr[1]; Object.getOwnPropertyDescriptor(arr, '1')"));
    }

    // OrdinarySetPrototypeOf rejects a prototype that already inherits from the target
    @Test
    public void test_set_prototype_of_detects_cycle() {
        assertEquals("TypeError", str("""
                let caught = 'none';
                const parent = {};
                const child = Object.create(parent);
                try { Object.setPrototypeOf(parent, child); } catch (e) { caught = e.name; }
                caught
                """));
        assertEquals("TypeError", str("""
                let caught = 'none';
                const self = {};
                try { Object.setPrototypeOf(self, self); } catch (e) { caught = e.name; }
                caught
                """));
        assertTrue(flag("const a = {}; const b = {}; Object.setPrototypeOf(a, b); Object.getPrototypeOf(a) === b"));
    }

    // A [[Prototype]] may be any object-like value, not only a plain object
    @Test
    public void test_set_prototype_of_accepts_an_array() {
        assertEquals("function", str("const o = {}; Object.setPrototypeOf(o, [1, 2]); typeof o.join"));
        assertEquals(2, num("const o = {}; Object.setPrototypeOf(o, [1, 2]); o.length"));
        assertTrue(flag(
                "const p = [1, 2]; const o = {}; Object.setPrototypeOf(o, p);" + " Object.getPrototypeOf(o) === p"));
        assertTrue(flag("const p = new Map(); const o = Object.create(p); Object.getPrototypeOf(o) === p"));
    }

    // The cycle check still runs over a chain whose links include a non-plain-object prototype
    @Test
    public void test_set_prototype_of_cycle_check_across_an_array_link() {
        assertEquals("TypeError", str("""
                let caught = 'none';
                const parent = {};
                Object.setPrototypeOf(parent, [1, 2]);
                const child = Object.create(parent);
                try { Object.setPrototypeOf(parent, child); } catch (e) { caught = e.name; }
                caught
                """));
    }

    // Object.create rejects a primitive prototype but takes any object-like one
    @Test
    public void test_create_with_an_array_prototype() {
        assertEquals("function", str("typeof Object.create([1, 2, 3]).map"));
        assertEquals(3, num("Object.create([1, 2, 3]).length"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.create(5)"));
    }

    // isPrototypeOf walks a chain whose links are not plain objects
    @Test
    public void test_is_prototype_of_through_an_array_link() {
        assertTrue(flag("const p = [1, 2]; Object.prototype.isPrototypeOf.call(p, Object.create(p))"));
        assertFalse(flag("const p = [1, 2]; Object.prototype.isPrototypeOf.call(p, {})"));
    }

    // A descriptor carrying only enumerable/configurable leaves an existing accessor intact
    @Test
    public void test_generic_descriptor_preserves_existing_accessor() {
        assertTrue(flag("const o = {}; Object.defineProperty(o, 'x', { get() { return 5; }, configurable: true });"
                + "Object.defineProperty(o, 'x', { enumerable: true });"
                + "typeof Object.getOwnPropertyDescriptor(o, 'x').get === 'function' && o.x === 5"));
    }

    // A symbol-keyed defineProperty stores its flags instead of always reporting all-true
    @Test
    public void test_symbol_descriptor_stores_flags() {
        assertTrue(flag("const s = Symbol('s'); const o = {};"
                + "Object.defineProperty(o, s, { value: 1, enumerable: false, configurable: false });"
                + "const d = Object.getOwnPropertyDescriptor(o, s);"
                + "d.value === 1 && d.enumerable === false && d.configurable === false"));
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
        assertTrue(flag("const m = new Map(); Object.defineProperty(m, 'x', { value: 1, enumerable: false });"
                + "const d = Object.getOwnPropertyDescriptor(m, 'x');" + "d.value === 1 && d.enumerable === false"));
        assertTrue(flag("const dt = new Date(0); Object.defineProperty(dt, 'y', { value: 2 });"
                + "Object.getOwnPropertyDescriptor(dt, 'y').value === 2"));
    }

    // A builtin constructor's `prototype` is non-writable, non-enumerable and non-configurable
    @Test
    public void test_builtin_constructor_prototype_descriptor() {
        assertTrue(flag("const d = Object.getOwnPropertyDescriptor(Array, 'prototype');"
                + "!d.writable && !d.enumerable && !d.configurable"));
        assertTrue(flag("const d = Object.getOwnPropertyDescriptor(function f() {}, 'prototype');"
                + "d.writable && !d.enumerable && !d.configurable"));
    }

    // An object literal is linked to %Object.prototype%
    @Test
    public void test_object_literal_is_linked_to_object_prototype() {
        assertTrue(flag("Object.getPrototypeOf({}) === Object.prototype"));
        assertTrue(flag("Object.create({}) instanceof Object"));
    }

    @Test
    public void valuesAndEntriesIncludeAnArraysNamedProperties() {
        assertEquals("1,2,3", str("const a = [1, 2]; a.x = 3; Object.values(a).join(',')"));
        assertEquals("0=1,1=2,x=3",
                str("const a = [1, 2]; a.x = 3; Object.entries(a).map(e => e[0] + '=' + e[1]).join(',')"));
    }

    @Test
    public void assignRejectsANullishTarget() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.assign(null, {a: 1})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.assign(undefined, {a: 1})"));
    }

    @Test
    public void assignThrowsWhenAWriteIsRejected() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.assign(Object.freeze({a: 1}), {a: 2})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const s = Symbol('s');
                const target = Object.preventExtensions({});
                const source = {};
                source[s] = 2;
                Object.assign(target, source)
                """));
    }

    @Test
    public void createRejectsANonObjectPrototypeAndNullProperties() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.create(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.create('x')"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.create({}, null)"));
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
        assertEquals(1, num("Object.defineProperty(globalThis, 'gDefined', { value: 1 });"
                + " Object.getOwnPropertyDescriptor(globalThis, 'gDefined').value"));
        assertEquals(7, num("globalThis.gAssigned = 1;"
                + " Object.defineProperty(globalThis, 'gAssigned', { value: 7 }); gAssigned"));
        // A top-level `var` is a non-configurable but *writable* global property, so redefining its
        // value is legal; only a non-writable one rejects.
        assertEquals(7, num("var gVar = 1;" + " Object.defineProperty(globalThis, 'gVar', { value: 7 }); gVar"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty(globalThis, 'NaN', { value: 7 })"));
        assertEquals("undefined", str("typeof Object.getOwnPropertyDescriptor(globalThis, Symbol('never'))"));
    }

    @Test
    public void definePropertyMaterialisesCallableMetadata() {
        assertTrue(flag("function f() {} Object.defineProperty(f, 'prototype', { value: { tag: 1 } });"
                + " f.prototype.tag === 1"));
        assertTrue(flag(
                "function f() {} Object.defineProperty(f, 'name', { value: 'renamed' });" + " f.name === 'renamed'"));
        assertTrue(flag("function f(a, b) {} Object.defineProperty(f, 'length', { value: 9 }); f.length === 9"));
    }

    @Test
    public void arrayLengthRedefinitionIsChecked() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'length', { configurable: true })"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'length', { enumerable: true })"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [1];
                Object.defineProperty(a, 'length', { writable: false });
                Object.defineProperty(a, 'length', { writable: true })
                """));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'length', { value: -1 })"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'length', { value: 1.5 })"));
    }

    @Test
    public void arrayIndexRedefinitionIsChecked() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [1];
                Object.defineProperty(a, '0', { configurable: false, enumerable: true });
                Object.defineProperty(a, '0', { enumerable: false })
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [1];
                Object.defineProperty(a, '0', { writable: false, configurable: false });
                Object.defineProperty(a, '0', { writable: true })
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [1];
                Object.defineProperty(a, '0', { configurable: false });
                Object.defineProperty(a, '0', { get() { return 2; } })
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [];
                Object.preventExtensions(a);
                Object.defineProperty(a, '0', { get() { return 2; } })
                """));
    }

    @Test
    public void arrayIndexAccessorsAreValidated() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([1], '0', { get: 1, configurable: true })"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([1], '0', { set: 1, configurable: true })"));
        assertEquals(5, num("const a = [1]; Object.defineProperty(a, '0', { get() { return 5; } }); a[0]"));
        assertEquals(6, num("""
                const a = [1];
                let seen = 0;
                Object.defineProperty(a, '0', { get() { return seen; }, set(v) { seen = v + 1; } });
                a[0] = 5;
                a[0]
                """));
    }

    @Test
    public void arrayOwnPropertyDescriptorsCoverEveryKeyShape() {
        assertEquals("undefined", str("typeof Object.getOwnPropertyDescriptor([], Symbol('x'))"));
        assertEquals(2, num("Object.getOwnPropertyDescriptor([1, 2], 'length').value"));
        assertEquals("undefined", str("typeof Object.getOwnPropertyDescriptor([], 'nope')"));
        assertEquals(3, num("const a = []; a.x = 3; Object.getOwnPropertyDescriptor(a, 'x').value"));
        assertTrue(flag("""
                const a = [1];
                Object.defineProperty(a, '0', { get() { return 1; }, configurable: true });
                const d = Object.getOwnPropertyDescriptor(a, '0');
                typeof d.get === 'function' && d.set === undefined && d.configurable === true
                """));
        assertTrue(flag("""
                const a = [];
                Object.defineProperty(a, 'x', { set(v) {}, enumerable: true });
                const d = Object.getOwnPropertyDescriptor(a, 'x');
                d.get === undefined && typeof d.set === 'function' && d.enumerable === true
                """));
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

    // 'get' and 'set' both present but neither callable is still an accessor: it reads as undefined
    @Test
    public void anAllUndefinedAccessorReadsAsUndefined() {
        assertEquals("undefined",
                str("const o = {}; Object.defineProperty(o, 'x', { get: undefined, set: undefined }); typeof o.x"));
    }

    @Test
    public void redefiningAnAccessorClearsTheSideTheDescriptorNames() {
        assertTrue(flag("""
                const o = {};
                Object.defineProperty(o, 'x', { get() { return 1; }, set(v) {}, configurable: true });
                Object.defineProperty(o, 'x', { set: undefined, configurable: true });
                const d = Object.getOwnPropertyDescriptor(o, 'x');
                typeof d.get === 'function' && d.set === undefined
                """));
        assertTrue(flag("""
                const o = {};
                Object.defineProperty(o, 'x', { get() { return 1; }, set(v) {}, configurable: true });
                Object.defineProperty(o, 'x', { get: undefined, configurable: true });
                const d = Object.getOwnPropertyDescriptor(o, 'x');
                d.get === undefined && typeof d.set === 'function'
                """));
    }

    @Test
    public void symbolKeyedDescriptorsGoThroughTheSameSlotProtocol() {
        assertTrue(flag("""
                const s = Symbol('s');
                const o = {};
                Object.defineProperty(o, s, { get() { return 1; }, configurable: true });
                Object.defineProperty(o, s, { set(v) {}, configurable: true });
                const d = Object.getOwnPropertyDescriptor(o, s);
                typeof d.get === 'function' && typeof d.set === 'function'
                """));
        assertTrue(flag("""
                const s = Symbol('s');
                const o = {};
                Object.defineProperty(o, s, { get() { return 1; }, set(v) {}, configurable: true });
                Object.defineProperty(o, s, { get: undefined, configurable: true });
                Object.defineProperty(o, s, { set: undefined, configurable: true });
                const d = Object.getOwnPropertyDescriptor(o, s);
                d.get === undefined && d.set === undefined
                """));
        assertEquals(5, num("""
                const s = Symbol('s');
                const o = {};
                Object.defineProperty(o, s, { get() { return 1; }, configurable: true });
                Object.defineProperty(o, s, { value: 5, configurable: true });
                o[s]
                """));
    }

    @Test
    public void ownPropertyNamesCoverClassesAndExoticTargets() {
        assertTrue(flag("class C { static x = 1; } Object.getOwnPropertyNames(C).includes('x')"));
        assertTrue(flag("const s = Symbol('s'); class C { static [s] = 1; } Object.hasOwn(C, s)"));
        assertEquals("x", str("const m = new Map(); Object.defineProperty(m, 'x', { value: 1 });"
                + " Object.getOwnPropertyNames(m).join(',')"));
    }

    @Test
    public void ownPropertyDescriptorsWalkSymbolsAndSkipAbsentProxyKeys() {
        assertEquals(0, num(
                "Object.keys(Object.getOwnPropertyDescriptors(new Proxy({}, " + "{ ownKeys: () => ['a'] }))).length"));
        assertTrue(flag("""
                const s = Symbol('s');
                const o = {};
                o[s] = 1;
                Object.getOwnPropertyDescriptors(o)[s].value === 1
                """));
    }

    // A non-symbol key runs through ToPropertyKey, so a user toString/valueOf decides the name
    @Test
    public void definePropertyCoercesKeyThroughToPropertyKey() {
        assertEquals(7, num("""
                const o = {};
                Object.defineProperty(o, { toString() { return 'k'; } }, { value: 7 });
                o.k
                """));
        assertEquals(3, num("const o = {}; Object.defineProperty(o, 2, { value: 3 }); o['2']"));
        assertTrue(flag("""
                const o = { a: 1 };
                Object.hasOwn(o, { toString() { return 'a'; } })
                """));
        assertTrue(flag("""
                const o = { a: 1 };
                delete o[{ toString() { return 'a'; } }];
                !('a' in o)
                """));
        assertEquals(1, num("""
                const o = {};
                Object.defineProperty(o, 'x', { value: 1 });
                Object.getOwnPropertyDescriptor(o, { toString() { return 'x'; } }).value
                """));
    }

    // ToPropertyDescriptor accepts any object (a function, an array, a Date), not just a literal
    @Test
    public void acceptsNonPlainObjectDescriptor() {
        assertEquals(5, num("""
                const o = {};
                const d = function () {};
                d.value = 5;
                Object.defineProperty(o, 'x', d);
                o.x
                """));
        assertEquals(4, num("""
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

    // ToPropertyKey(P) runs before the descriptor is inspected, so a poisoned key coercion is what
    // escapes even when the descriptor is not an object at all.
    @Test
    public void definePropertyCoercesTheKeyBeforeReadingTheDescriptor() {
        assertEquals("key", str("""
                let order = [];
                const key = { toString() { order.push('key'); return 'k'; } };
                try { Object.defineProperty({}, key, 1); } catch (e) { order.push('desc'); }
                order.join(',')
                """).split(",")[0]);
        assertEquals("key,desc", str("""
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
        assertEquals("enumerable,configurable,value,writable,get,set", str("""
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
        assertEquals("enumerable", str("""
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
        assertTrue(flag("""
                const o = { x: 1 };
                Object.defineProperty(o, 'x', { writable: false });
                Object.getOwnPropertyDescriptor(o, 'x').writable === false
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("const o = { x: 1 }; Object.defineProperty(o, 'x', { writable: false }); o.x = 2"));
        assertTrue(flag("""
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
        assertEquals("dateData", str("""
                const d = new Date(0);
                Object.defineProperties(d, { tag: { value: 'dateData', enumerable: true } });
                d.tag
                """));
        assertEquals("mapData", str("""
                const m = new Map();
                Object.defineProperty(m, 'tag', { value: 'mapData' });
                m.tag
                """));
        assertEquals("setData", str("const s = new Set(); s.tag = 'setData'; s.tag"));
        assertEquals("bufferData", str("const b = new ArrayBuffer(1); b.tag = 'bufferData'; b.tag"));
        // The internal slot still wins: a Map's `size` is not shadowed by the table.
        assertEquals(1, num("const m = new Map([[1, 2]]); m.tag = 'x'; m.size"));
    }

    // ArraySetLength: an out-of-range length is a RangeError (it fails the numeric conversion), while
    // a length write refused by a non-writable `length` is a TypeError.
    @Test
    public void arraySetLengthDistinguishesRangeErrorFromTypeError() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("const a = [1]; a.length = -1"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("const a = [1]; a.length = 1.5"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("const a = [1]; a.length = 4294967296"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [1, 2, 3];
                Object.defineProperty(a, 'length', { writable: false });
                a.length = 2
                """));
        // The range check precedes the writability check, so an invalid value still reports RangeError.
        assertThrows(RangeErrorException.class, () -> Interpreter.run("""
                const a = [1, 2, 3];
                Object.defineProperty(a, 'length', { writable: false });
                a.length = -1
                """));
    }

    // Truncation deletes indices top-down and stops at the first non-configurable one, leaving
    // `length` just above it.
    @Test
    public void arraySetLengthTruncationStopsAtANonConfigurableIndex() {
        assertEquals(3, num("""
                const a = [0, 1, 2, 3];
                Object.defineProperty(a, '2', { configurable: false });
                try { a.length = 0; } catch (e) {}
                a.length
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [0, 1, 2, 3];
                Object.defineProperty(a, '2', { configurable: false });
                a.length = 0
                """));
        assertEquals(1, num("const a = [0, 1, 2, 3]; a.length = 1; a.length"));
    }

    // An index write with no own property consults the prototype chain's setter instead of creating
    // one, and a getter-only inherited accessor refuses the write.
    @Test
    public void arrayIndexWriteConsultsAnInheritedSetter() {
        assertEquals("42", str("""
                let seen = [];
                const proto = {};
                Object.defineProperty(proto, '0', { set(v) { seen.push(v); }, get() { return 'G'; } });
                const a = [];
                Object.setPrototypeOf(a, proto);
                a[0] = 42;
                seen.join(',')
                """));
        assertEquals(0, num("""
                const proto = {};
                Object.defineProperty(proto, '0', { set(v) {}, get() { return 'G'; } });
                const a = [];
                Object.setPrototypeOf(a, proto);
                a[0] = 42;
                a.length
                """));
        assertEquals("7", str("""
                let seen = [];
                const proto = {};
                Object.defineProperty(proto, 'x', { set(v) { seen.push(v); } });
                const a = [];
                Object.setPrototypeOf(a, proto);
                a.x = 7;
                seen.join(',')
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const proto = {};
                Object.defineProperty(proto, '0', { get() { return 'G'; } });
                const a = [];
                Object.setPrototypeOf(a, proto);
                a[0] = 1
                """));
        // An own index still wins over the inherited accessor.
        assertEquals(9, num("""
                const proto = {};
                Object.defineProperty(proto, '0', { set(v) {}, get() { return 'G'; } });
                const a = [5];
                Object.setPrototypeOf(a, proto);
                a[0] = 9;
                a[0]
                """));
    }

    // Redirecting an array's [[Prototype]] redirects its inherited reads too, so the Array.prototype
    // method surface is genuinely gone rather than resolved behind the new link.
    @Test
    public void settingAnArrayPrototypeRedirectsInheritedReads() {
        assertEquals("inherited", str("const a = []; Object.setPrototypeOf(a, { tag: 'inherited' }); a.tag"));
        assertEquals("undefined", str("""
                const a = [];
                Object.setPrototypeOf(a, {});
                typeof a.map
                """));
        assertTrue(flag("const a = [1]; Object.getPrototypeOf(a) === Array.prototype"));
    }

    // delete on the global object removes the binding, so hasOwnProperty agrees afterwards and a
    // descriptor round-trip restores it.
    @Test
    public void deleteOnTheGlobalObjectRemovesTheBinding() {
        assertTrue(flag("""
                const had = Object.prototype.hasOwnProperty.call(globalThis, 'JSON');
                const gone = delete globalThis.JSON;
                had && gone && !Object.prototype.hasOwnProperty.call(globalThis, 'JSON')
                    && typeof JSON === 'undefined'
                """));
        assertTrue(flag("""
                const desc = Object.getOwnPropertyDescriptor(globalThis, 'Math');
                delete globalThis.Math;
                Object.defineProperty(globalThis, 'Math', desc);
                Object.prototype.hasOwnProperty.call(globalThis, 'Math') && typeof Math.max === 'function'
                """));
        // A non-configurable global refuses the delete.
        assertThrows(TypeErrorException.class, () -> Interpreter.run("delete globalThis.NaN"));
        assertTrue(flag("Object.getOwnPropertyDescriptor(globalThis, 'JSON').configurable === true"));
    }

    // hasOwnProperty/propertyIsEnumerable answer for symbol keys, and coerce their argument through
    // ToPropertyKey (so a @@toPrimitive wrapper yielding a symbol keys by that symbol).
    @Test
    public void objectPrototypeHelpersAnswerForSymbolKeys() {
        assertTrue(flag("""
                const o = {};
                const s = Symbol();
                o[s] = 0;
                const wrapper = {};
                wrapper[Symbol.toPrimitive] = () => s;
                o.hasOwnProperty(wrapper) && o.hasOwnProperty(s)
                """));
        assertTrue(flag("""
                const o = {};
                const enumerableSymbol = Symbol();
                const hiddenSymbol = Symbol();
                o[enumerableSymbol] = 1;
                Object.defineProperty(o, hiddenSymbol, { value: 1, enumerable: false });
                o.propertyIsEnumerable(enumerableSymbol) && !o.propertyIsEnumerable(hiddenSymbol)
                """));
        // ToPropertyKey precedes ToObject, so the key coercion is what escapes - the null receiver's
        // own TypeError is never reached.
        assertEquals("RangeError", str("""
                let caught = 'no throw';
                try {
                  Object.prototype.hasOwnProperty.call(null, { toString() { throw new RangeError('k'); } });
                } catch (e) { caught = e.name; }
                caught
                """));
    }

    // hasOwnProperty falls back to an exotic type's real PropertyTable instead of hard-coding false,
    // so an ad hoc write is reported even though the type has no dedicated hasOwnKey arm.
    @Test
    public void hasOwnPropertyFallsBackToAnExoticTypesTable() {
        assertTrue(flag("const d = new Date(0); d.foo = 1; d.hasOwnProperty('foo')"));
        assertTrue(flag("const m = new Map(); m.foo = 1; m.hasOwnProperty('foo')"));
        assertTrue(flag("const re = /x/; re.hasOwnProperty('lastIndex')"));
        assertTrue(flag("const p = Promise.resolve(1); p.foo = 1; p.hasOwnProperty('foo')"));
        // An accessor-only own property on a bound function is reported too, not only a data one.
        assertTrue(flag("""
                function f() {}
                const bound = f.bind({});
                Object.defineProperty(bound, 'x', { set(v) {} });
                bound.hasOwnProperty('x')
                """));
    }

    // hasOwnProperty and getOwnPropertySymbols consult a Proxy's traps instead of always answering
    // empty/false, since a Proxy carries no PropertyTable of its own.
    @Test
    public void hasOwnPropertyAndGetOwnPropertySymbolsAreProxyAware() {
        assertTrue(flag("""
                const target = { attr: 1 };
                const p = new Proxy(target, {});
                p.hasOwnProperty('attr')
                """));
        assertFalse(flag("""
                const p = new Proxy({}, { getOwnPropertyDescriptor: () => undefined });
                p.hasOwnProperty('attr')
                """));
        assertEquals(1, num("""
                const s = Symbol('s');
                const p = new Proxy({}, { ownKeys: () => [s] , getOwnPropertyDescriptor: () => ({
                    value: 1, writable: true, enumerable: true, configurable: true
                }) });
                Object.getOwnPropertySymbols(p).length
                """));
    }

    // Object.setPrototypeOf on a non-extensible target throws, but a no-op (setting the same
    // prototype it already has) is allowed even though the target isn't extensible.
    @Test
    public void setPrototypeOfRejectsANonExtensibleTarget() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const o = Object.preventExtensions({}); Object.setPrototypeOf(o, {})"));
        assertTrue(flag("""
                const proto = {};
                const o = Object.create(proto);
                Object.preventExtensions(o);
                Object.setPrototypeOf(o, proto) === o
                """));
        // A plain object literal is already linked to Object.prototype (never a bare Java null), so
        // retargeting a non-extensible one to null is a real change and still rejected.
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.setPrototypeOf(Object.preventExtensions({}), null)"));
    }

    // Object.setPrototypeOf validates its arguments before it ever looks at the target's
    // extensibility: a nullish target and a non-object/non-null proto both throw.
    @Test
    public void setPrototypeOfValidatesItsArguments() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.setPrototypeOf(undefined, {})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.setPrototypeOf(null, {})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.setPrototypeOf({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.setPrototypeOf({}, 1)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.setPrototypeOf({}, 'x')"));
        // A primitive target is simply returned unchanged once the proto argument passes validation.
        assertEquals(5, num("Object.setPrototypeOf(5, {})"));
    }

    // Object.assign ToObjects a primitive target (typeof becomes "object") and copies a String
    // source's index characters, an Array source/target's exotic length semantics, and a Proxy
    // source's own keys/values through the ops seam rather than special-casing each shape.
    @Test
    public void assignHandlesPrimitiveTargetsStringSourcesAndArrays() {
        assertEquals("object,object,object",
                str("typeof Object.assign(true) + ',' + typeof Object.assign(1) + ',' + typeof Object.assign('s')"));
        assertEquals("1,2,3", str("""
                const r = Object.assign({}, '123');
                r[0] + ',' + r[1] + ',' + r[2]
                """));
        assertEquals("1,8,3", str("""
                const target = [7, 8, 9];
                Object.assign(target, [1]);
                const sparse = [];
                sparse[2] = 3;
                Object.assign(target, sparse);
                target.join(',')
                """));
    }

    // A Proxy source's ownKeys/getOwnPropertyDescriptor/get traps are consulted exactly once per
    // key, and a trap throwing propagates rather than being swallowed.
    @Test
    public void assignConsultsAProxySourcesTrapsExactlyOnce() {
        assertEquals(1, num("""
                let calls = 0;
                const p = new Proxy({}, {
                    ownKeys: () => ['a'],
                    getOwnPropertyDescriptor: () => { calls++; return undefined; }
                });
                Object.assign({}, p);
                calls
                """));
        assertTrue(flag("""
                let caught = null;
                try {
                    const p = new Proxy({}, { ownKeys: () => { throw new RangeError('boom'); } });
                    Object.assign({}, p);
                } catch (e) { caught = e; }
                caught instanceof RangeError
                """));
    }

    // fromEntries: the result is proto-linked to %Object.prototype% (OrdinaryObjectCreate), symbol
    // keys are supported (ToPropertyKey can yield one), and "0"/"1" are read via Get rather than by
    // iterating the entry - an entry whose own [Symbol.iterator] throws must never be touched.
    @Test
    public void fromEntriesLinksPrototypeAndSupportsSymbolKeysAndPlainObjectEntries() {
        assertTrue(flag("Object.getPrototypeOf(Object.fromEntries([])) === Object.prototype"));
        assertTrue(flag("""
                const key = Symbol('k');
                Object.fromEntries([[key, 'value']])[key] === 'value'
                """));
        assertEquals("first value", str("""
                const entry = {
                    '0': 'first key',
                    '1': 'first value',
                    get [Symbol.iterator]() { throw new Error('must not iterate the entry'); },
                };
                Object.fromEntries([entry])['first key']
                """));
    }

    // A non-object entry (or an abrupt Get/ToPropertyKey) throws a TypeError and closes the source
    // iterator (IteratorClose) before the error propagates.
    @Test
    public void fromEntriesRejectsNonObjectEntriesAndClosesTheIterator() {
        assertTrue(flag("""
                let returned = false;
                const iterable = {
                    [Symbol.iterator]() {
                        let done = false;
                        return {
                            next() { const d = done; done = true; return { done: d, value: 'nope' }; },
                            return() { returned = true; },
                        };
                    },
                };
                let threw = false;
                try { Object.fromEntries(iterable); } catch (e) { threw = e instanceof TypeError; }
                threw && returned
                """));
    }

    // Object.groupBy: a non-callable callback throws synchronously, and the bucket key goes through
    // the real ToPropertyKey (a stringable object's toString(), not a raw String() coercion).
    @Test
    public void groupByRejectsNonCallableAndUsesRealToPropertyKey() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.groupBy([], null)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.groupBy([], {})"));
        assertEquals("1", str("""
                const stringable = { toString() { return 1; } };
                const g = Object.groupBy([1, '1', stringable], v => v);
                Object.keys(g).join(',')
                """));
    }

    // A poisoned ToPropertyKey on the callback's return value propagates instead of being swallowed.
    @Test
    public void groupByPropagatesAPoisonedPropertyKeyConversion() {
        assertTrue(flag("""
                let caught = null;
                try {
                    Object.groupBy([1], () => ({ toString() { throw new RangeError('nope'); } }));
                } catch (e) { caught = e; }
                caught instanceof RangeError
                """));
    }

    // Object.create's Properties argument is ToObject'd, so a non-empty string is walked by its
    // boxed index characters - each of which is a bare string value, not a descriptor object.
    @Test
    public void createRejectsANonEmptyStringPropertiesArgument() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.create({}, 'hello')"));
    }

    // Object.getPrototypeOf/getOwnPropertyDescriptor ToObject their argument, so null/undefined is
    // a TypeError rather than a silent null/undefined answer.
    @Test
    public void getPrototypeOfRequiresAnObjectCoercibleArgument() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.getPrototypeOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.getPrototypeOf(null)"));
        assertTrue(flag("Object.getPrototypeOf(5) === Number.prototype"));
    }

    // The descriptor object FromPropertyDescriptor builds is itself proto-linked to Object.prototype
    // (both the single- and the batch- getOwnPropertyDescriptor forms).
    @Test
    public void getOwnPropertyDescriptorResultIsAnInstanceOfObject() {
        assertTrue(flag("Object.getOwnPropertyDescriptor({p: 1}, 'p') instanceof Object"));
        assertTrue(flag("Object.getPrototypeOf(Object.getOwnPropertyDescriptors({})) === Object.prototype"));
    }

    // ObjectDefineProperties walks every own key - including symbols - calling
    // getOwnPropertyDescriptor on each one in key order, not skipping symbol keys ahead of that call.
    @Test
    public void definePropertiesConsultsEveryOwnKeyIncludingSymbols() {
        assertEquals("0,foo,symbol", str("""
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

    // Object.values/entries do not see a key removed by an earlier getter during the same
    // enumeration pass - the snapshot [[OwnPropertyKeys]] list stays fixed, but each key's presence
    // is re-checked right before it would be read.
    @Test
    public void valuesAndEntriesSkipAKeyDeletedByAnEarlierGetter() {
        final var setup = """
                const o = { a: 'A', get b() { delete this.c; return 'B'; }, c: 'C' };
                """;
        assertEquals("A,B", str(setup + "Object.values(o).join(',')"));
        assertEquals("a,b", str(setup + "Object.entries(o).map(e => e[0]).join(',')"));
    }

    // Object.values/entries over a Proxy interleave getOwnPropertyDescriptor and get per key (not a
    // getOwnPropertyDescriptor batch followed by a get batch).
    @Test
    public void valuesOverAProxyInterleavesDescriptorCheckAndGetPerKey() {
        assertEquals("|ownKeys|getOwnPropertyDescriptor:a|get:a|getOwnPropertyDescriptor:b|get:b",
                str("""
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
