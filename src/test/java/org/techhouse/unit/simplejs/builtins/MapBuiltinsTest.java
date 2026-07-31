package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class MapBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // set/get/has/delete/size round-trip
    @Test
    public void test_basic_operations() {
        assertEquals(2, num("let m = new Map(); m.set('a', 1); m.set('b', 2); m.size"));
        assertEquals(1, num("let m = new Map(); m.set('a', 1); m.get('a')"));
        assertTrue(bool("let m = new Map(); m.set('a', 1); m.has('a')"));
        assertTrue(bool("let m = new Map(); m.set('a', 1); m.delete('a'); !m.has('a')"));
        assertEquals("undefined", str("typeof new Map().get('missing')"));
    }

    // set returns the map for chaining
    @Test
    public void test_set_chaining() {
        assertEquals(3, num("new Map().set('a', 1).set('b', 2).set('c', 3).size"));
    }

    // object-identity keys are distinct; the same reference matches
    @Test
    public void test_object_identity_keys() {
        assertEquals(2, num("let a = {}; let b = {}; let m = new Map(); m.set(a, 1); m.set(b, 2); m.size"));
        assertEquals(9, num("let a = {}; let m = new Map(); m.set(a, 1); m.set(a, 9); m.get(a)"));
    }

    // NaN is a valid, self-equal key; +0 and -0 collapse
    @Test
    public void test_nan_and_zero_keys() {
        assertEquals(7, num("let m = new Map(); m.set(0/0, 7); m.get(0/0)"));
        assertEquals(1, num("let m = new Map(); m.set(0, 1); m.set(-0, 2); m.size"));
        assertEquals(2, num("let m = new Map(); m.set(0, 1); m.set(-0, 2); m.get(0)"));
    }

    // primitive keys of every kind compare by value
    @Test
    public void test_primitive_key_kinds() {
        assertEquals(4, num("let m = new Map(); m.set(true, 1); m.set(null, 2); m.set(undefined, 3); m.set(10n, 4);"
                + " m.get(true) + m.get(null) + m.get(undefined) + m.get(10n) - 6"));
        assertTrue(bool("let m = new Map(); m.set(true, 1); m.has(true) && !m.has(false)"));
        assertTrue(bool("let m = new Map(); m.set(5n, 1); m.has(5n)"));
    }

    // clear empties the map
    @Test
    public void test_clear() {
        assertEquals(0, num("let m = new Map([['a', 1], ['b', 2]]); m.clear(); m.size"));
    }

    // construction from an iterable of entries preserves insertion order
    @Test
    public void test_construct_from_iterable() {
        assertEquals("a=1,b=2",
                str("[...new Map([['a', 1], ['b', 2]]).entries()]" + ".map(e => e[0] + '=' + e[1]).join(',')"));
    }

    // for-of iterates [key, value] entries in insertion order
    @Test
    public void test_for_of_entries() {
        assertEquals("x:1,y:2", str("let m = new Map([['x', 1], ['y', 2]]); let out = [];"
                + " for (const [k, v] of m) out.push(k + ':' + v); out.join(',')"));
    }

    // keys() and values() iterators
    @Test
    public void test_keys_and_values() {
        assertEquals("a,b", str("[...new Map([['a', 1], ['b', 2]]).keys()].join(',')"));
        assertEquals("1,2", str("[...new Map([['a', 1], ['b', 2]]).values()].join(',')"));
    }

    // forEach passes (value, key, map)
    @Test
    public void test_for_each() {
        assertEquals("a=1;b=2;", str("let m = new Map([['a', 1], ['b', 2]]); let out = '';"
                + " m.forEach((v, k) => out += k + '=' + v + ';'); out"));
    }

    // empty map iterates to nothing
    @Test
    public void test_empty_iteration() {
        assertEquals(0, num("let n = 0; for (const e of new Map()) n++; n"));
    }

    // an unknown member is undefined
    @Test
    public void test_unknown_member() {
        assertEquals("undefined", str("typeof new Map().nope"));
    }

    // constructing with no/undefined argument yields an empty map
    @Test
    public void test_construct_empty() {
        assertEquals(0, num("new Map().size"));
        assertEquals(0, num("new Map(undefined).size"));
    }

    // JSON.stringify of a Map is an empty object (no own enumerable properties)
    @Test
    public void test_json_stringify() {
        assertEquals("{}", str("JSON.stringify(new Map([['a', 1]]))"));
    }

    // WeakMap rejects a primitive key
    @Test
    public void test_weakmap_primitive_key_throws() {
        Assertions.assertThrows(TypeErrorException.class, () -> Interpreter.run("new WeakMap().set(1, 2)"));
    }

    // WeakMap accepts object keys
    @Test
    public void test_weakmap_object_key() {
        assertEquals(5, num("let k = {}; let w = new WeakMap(); w.set(k, 5); w.get(k)"));
    }

    // Map.groupBy buckets items into a real Map, keyed by the callback's return value (not a string)
    @Test
    public void test_group_by() {
        final var setup = "let g = Map.groupBy([1, 2, 3, 4], n => n % 2); ";
        assertEquals("1,3", str(setup + "g.get(1).join(',')"));
        assertEquals("2,4", str(setup + "g.get(0).join(',')"));
        assertEquals(2, num(setup + "g.size"));
    }

    // Map.groupBy keys by object identity (SameValueZero), unlike Object.groupBy
    @Test
    public void test_group_by_object_key() {
        final var source = "let k = {}; let g = Map.groupBy([1, 2], () => k); g.get(k).join(',')";
        assertEquals("1,2", str(source));
    }

    // WeakMap has no groupBy static
    @Test
    public void test_weakmap_no_group_by() {
        assertEquals("undefined", str("typeof WeakMap.groupBy"));
    }
}
