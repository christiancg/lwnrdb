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

public class ObjectAccessorBuiltinsTest {
    // freeze blocks further writes, which the always-strict engine reports as a TypeError
    @Test
    public void test_freeze() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("let o = Object.freeze({a: 1}); o.a = 5"));
        assertEquals(1, JsEval.num("let o = Object.freeze({a: 1}); try { o.a = 5; } catch (e) { } o.a"));
        assertTrue(JsEval.bool("let o = {}; Object.freeze(o) === o"));
    }

    // a getter-only accessor makes assignment a no-op
    @Test
    public void test_getter_only_accessor() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("let o = {}; Object.defineProperty(o, 'v', {get: function() { return 1; }}); o.v = 5"));
        assertEquals(1, JsEval.num(
                "let o = {}; Object.defineProperty(o, 'v', {get: function() { return 1; }}); try { o.v = 5; } catch (e) { } o.v"));
    }

    // a non-enumerable property is omitted from JSON.stringify
    @Test
    public void test_non_enumerable_json_stringify() {
        assertEquals("{\"a\":1}", JsEval.str(
                "let o = {a: 1}; Object.defineProperty(o, 'hidden', {value: 2, enumerable: false}); JSON.stringify(o)"));
    }

    // propertyIsEnumerable reflects the real flag
    @Test
    public void test_property_is_enumerable() {
        final var setup = "let o = {a: 1}; Object.defineProperty(o, 'hidden', {value: 2, enumerable: false}); ";
        assertTrue(JsEval.bool(setup + "o.propertyIsEnumerable('a')"));
        assertFalse(JsEval.bool(setup + "o.propertyIsEnumerable('hidden')"));
        assertFalse(JsEval.bool(setup + "o.propertyIsEnumerable('missing')"));
    }

    // freeze blocks add, modify and delete; isFrozen reports true
    @Test
    public void test_freeze_full() {
        assertEquals(1, JsEval.num("let o = Object.freeze({a: 1}); try { o.a = 5; o.b = 9; } catch (e) { } o.a"));
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("let o = Object.freeze({a: 1}); try { o.b = 9; } catch (e) { } o.b"));
        assertEquals(1, JsEval.num("let o = Object.freeze({a: 1}); try { delete o.a; } catch (e) { } o.a"));
        assertTrue(JsEval.bool("Object.isFrozen(Object.freeze({a: 1}))"));
        assertFalse(JsEval.bool("Object.isFrozen({a: 1})"));
    }

    // seal blocks adding keys but allows modifying existing ones; isSealed reports true
    @Test
    public void test_seal() {
        assertEquals(5, JsEval.num("let o = Object.seal({a: 1}); o.a = 5; o.a"));
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("let o = Object.seal({a: 1}); try { o.b = 9; } catch (e) { } o.b"));
        assertEquals(1, JsEval.num("let o = Object.seal({a: 1}); try { delete o.a; } catch (e) { } o.a"));
        assertTrue(JsEval.bool("Object.isSealed(Object.seal({a: 1}))"));
        assertFalse(JsEval.bool("Object.isSealed({a: 1})"));
        assertFalse(JsEval.bool("Object.isFrozen(Object.seal({a: 1}))"));
    }

    // an empty non-extensible object is both sealed and frozen
    @Test
    public void test_empty_non_extensible_is_frozen() {
        assertTrue(JsEval.bool("Object.isFrozen(Object.preventExtensions({}))"));
        assertTrue(JsEval.bool("Object.isSealed(Object.preventExtensions({}))"));
    }

    // an accessor property is an own key, so Object.keys lists it
    @Test
    public void test_object_keys_includes_accessor() {
        assertEquals("x", JsEval.str("Object.keys({get x() { return 1; }}).join(',')"));
    }

    // data and accessor properties share one insertion-ordered own-key list
    @Test
    public void test_own_key_order_mixes_data_and_accessor() {
        assertEquals("a,b,c", JsEval.str("Object.keys({a: 1, get b() { return 2; }, c: 3}).join(',')"));
    }

    // Object.values invokes the getter rather than reporting undefined
    @Test
    public void test_object_values_invokes_getter() {
        assertEquals("1", JsEval.str("Object.values({get x() { return 1; }}).join(',')"));
    }

    // Object.entries invokes the getter
    @Test
    public void test_object_entries_invokes_getter() {
        assertEquals("x,1", JsEval.str("Object.entries({get x() { return 1; }})[0].join(',')"));
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
        assertEquals("5,5,1", JsEval.str(source));
    }

    // getOwnPropertyNames lists accessor keys too
    @Test
    public void test_get_own_property_names_includes_accessor() {
        assertEquals("x", JsEval.str("Object.getOwnPropertyNames({get x() { return 1; }}).join(',')"));
    }

    // a non-enumerable accessor stays hidden from keys but visible to getOwnPropertyNames
    @Test
    public void test_non_enumerable_accessor_is_skipped() {
        final var source = """
                const o = {};
                Object.defineProperty(o, 'x', { get() { return 7; } });
                Object.keys(o).length + '|' + Object.getOwnPropertyNames(o).join(',')
                """;
        assertEquals("0|x", JsEval.str(source));
    }

    // delete drops the accessor entries along with the key
    @Test
    public void test_delete_removes_accessor() {
        final var source = """
                const o = { get x() { return 1; } };
                delete o.x;
                String(o.x) + '|' + Object.keys(o).length
                """;
        assertEquals("undefined|0", JsEval.str(source));
    }

    // freeze and seal still cover accessor keys after the ownKeys() collapse
    @Test
    public void test_freeze_still_covers_accessors() {
        assertTrue(JsEval.bool("const o = { get x() { return 1; } }; Object.freeze(o); Object.isFrozen(o)"));
        assertTrue(JsEval.bool("const o = { get x() { return 1; } }; Object.seal(o); Object.isSealed(o)"));
        assertFalse(JsEval.bool("const o = { a: 1, get x() { return 1; } }; Object.seal(o); Object.isFrozen(o)"));
    }

    // isFrozen/isSealed/isExtensible report the trivial defaults for non-object, non-array values
    @Test
    public void test_is_frozen_sealed_extensible_on_primitives() {
        assertTrue(JsEval.bool("Object.isFrozen(5)"));
        assertTrue(JsEval.bool("Object.isSealed('a')"));
        assertFalse(JsEval.bool("Object.isExtensible(true)"));
    }

    // delete on the global object removes the binding, so hasOwnProperty agrees afterwards and a
    // descriptor round-trip restores it.
    @Test
    public void deleteOnTheGlobalObjectRemovesTheBinding() {
        assertTrue(JsEval.bool("""
                const had = Object.prototype.hasOwnProperty.call(globalThis, 'JSON');
                const gone = delete globalThis.JSON;
                had && gone && !Object.prototype.hasOwnProperty.call(globalThis, 'JSON')
                    && typeof JSON === 'undefined'
                """));
        assertTrue(JsEval.bool("""
                const desc = Object.getOwnPropertyDescriptor(globalThis, 'Math');
                delete globalThis.Math;
                Object.defineProperty(globalThis, 'Math', desc);
                Object.prototype.hasOwnProperty.call(globalThis, 'Math') && typeof Math.max === 'function'
                """));
        // A non-configurable global refuses the delete.
        assertThrows(TypeErrorException.class, () -> Interpreter.run("delete globalThis.NaN"));
        assertTrue(JsEval.bool("Object.getOwnPropertyDescriptor(globalThis, 'JSON').configurable === true"));
    }

    // Object.values/entries do not see a key removed by an earlier getter during the same
    // enumeration pass - the snapshot [[OwnPropertyKeys]] list stays fixed, but each key's presence
    // is re-checked right before it would be read.
    @Test
    public void valuesAndEntriesSkipAKeyDeletedByAnEarlierGetter() {
        final var setup = """
                const o = { a: 'A', get b() { delete this.c; return 'B'; }, c: 'C' };
                """;
        assertEquals("A,B", JsEval.str(setup + "Object.values(o).join(',')"));
        assertEquals("a,b", JsEval.str(setup + "Object.entries(o).map(e => e[0]).join(',')"));
    }
}
