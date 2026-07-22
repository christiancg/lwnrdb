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

public class SetBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // add/has/delete/size and de-duplication
    @Test
    public void test_basic_operations() {
        assertEquals(2, num("let s = new Set(); s.add(1); s.add(2); s.add(1); s.size"));
        assertTrue(bool("let s = new Set(); s.add('x'); s.has('x')"));
        assertTrue(bool("let s = new Set(); s.add('x'); s.delete('x'); !s.has('x')"));
    }

    // add returns the set for chaining
    @Test
    public void test_add_chaining() {
        assertEquals(3, num("new Set().add(1).add(2).add(3).size"));
    }

    // object identity: distinct references are distinct members
    @Test
    public void test_object_identity() {
        assertEquals(2, num("let a = {}; let b = {}; let s = new Set(); s.add(a); s.add(b); s.add(a); s.size"));
    }

    // NaN is a single self-equal member
    @Test
    public void test_nan_member() {
        assertEquals(1, num("let s = new Set(); s.add(0/0); s.add(0/0); s.size"));
        assertTrue(bool("let s = new Set(); s.add(0/0); s.has(0/0)"));
    }

    // construction from an iterable de-duplicates and keeps first-seen order
    @Test
    public void test_construct_from_iterable() {
        assertEquals("1,2,3", str("[...new Set([1, 2, 2, 3, 1])].join(',')"));
    }

    // spread of a set yields its values in insertion order
    @Test
    public void test_spread() {
        assertEquals("a,b,c", str("[...new Set(['a', 'b', 'c'])].join(',')"));
    }

    // for-of iterates values
    @Test
    public void test_for_of() {
        assertEquals("10,20",
                str("let s = new Set([10, 20]); let out = []; for (const v of s) out.push(v); out.join(',')"));
    }

    // forEach passes (value, value, set)
    @Test
    public void test_for_each() {
        assertEquals("1=1;2=2;",
                str("let s = new Set([1, 2]); let out = '';" + " s.forEach((v, k) => out += v + '=' + k + ';'); out"));
    }

    // clear empties the set
    @Test
    public void test_clear() {
        assertEquals(0, num("let s = new Set([1, 2, 3]); s.clear(); s.size"));
    }

    // keys() and values() are equivalent for a set; entries() yields [v, v]
    @Test
    public void test_keys_values_entries() {
        assertEquals("1,2", str("[...new Set([1, 2]).keys()].join(',')"));
        assertEquals("1,2", str("[...new Set([1, 2]).values()].join(',')"));
        assertEquals("1=1,2=2", str("[...new Set([1, 2]).entries()].map(e => e[0] + '=' + e[1]).join(',')"));
    }

    // an unknown member is undefined
    @Test
    public void test_unknown_member() {
        assertEquals("undefined", str("typeof new Set().nope"));
    }

    // constructing with no/undefined argument yields an empty set
    @Test
    public void test_construct_empty() {
        assertEquals(0, num("new Set().size"));
        assertEquals(0, num("new Set(undefined).size"));
    }

    // JSON.stringify of a Set is an empty object
    @Test
    public void test_json_stringify() {
        assertEquals("{}", str("JSON.stringify(new Set([1, 2]))"));
    }

    // WeakSet rejects a primitive value
    @Test
    public void test_weakset_primitive_throws() {
        Assertions.assertThrows(TypeErrorException.class, () -> Interpreter.run("new WeakSet().add(1)"));
    }

    // WeakSet accepts object members
    @Test
    public void test_weakset_object() {
        assertTrue(bool("let o = {}; let w = new WeakSet(); w.add(o); w.has(o)"));
    }
}
