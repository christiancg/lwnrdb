package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
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

    // freeze blocks further writes
    @Test
    public void test_freeze() {
        assertEquals(1, num("let o = Object.freeze({a: 1}); o.a = 5; o.a"));
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

    // defineProperty on a frozen object is a no-op
    @Test
    public void test_define_property_frozen() {
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("let o = Object.freeze({}); Object.defineProperty(o, 'v', {value: 5}); o.v"));
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
        assertEquals(1,
                num("let o = {}; Object.defineProperty(o, 'v', {get: function() { return 1; }}); o.v = 5; o.v"));
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
}
