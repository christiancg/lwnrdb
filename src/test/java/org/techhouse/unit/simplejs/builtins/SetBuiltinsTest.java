package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // union yields every element of either set, without duplicates, in encounter order
    @Test
    public void test_union() {
        assertEquals("1,2,3,4", str("[...new Set([1, 2, 3]).union(new Set([3, 4]))].join(',')"));
    }

    // intersection yields elements present in both sets
    @Test
    public void test_intersection() {
        assertEquals("2,3", str("[...new Set([1, 2, 3]).intersection(new Set([2, 3, 5]))].join(',')"));
    }

    // difference yields elements in this set but not the other
    @Test
    public void test_difference() {
        assertEquals("1", str("[...new Set([1, 2, 3]).difference(new Set([2, 3]))].join(',')"));
    }

    // symmetricDifference yields elements in exactly one of the two sets
    @Test
    public void test_symmetric_difference() {
        assertEquals("1,4", str("[...new Set([1, 2, 3]).symmetricDifference(new Set([2, 3, 4]))].join(',')"));
    }

    // isSubsetOf / isSupersetOf / isDisjointFrom answer the set relations
    @Test
    public void test_relations() {
        assertTrue(bool("new Set([1, 2]).isSubsetOf(new Set([1, 2, 3]))"));
        assertFalse(bool("new Set([1, 4]).isSubsetOf(new Set([1, 2, 3]))"));
        assertTrue(bool("new Set([1, 2, 3]).isSupersetOf(new Set([1, 2]))"));
        assertFalse(bool("new Set([1, 2]).isSupersetOf(new Set([1, 4]))"));
        assertTrue(bool("new Set([1, 2]).isDisjointFrom(new Set([3, 4]))"));
        assertFalse(bool("new Set([1, 2]).isDisjointFrom(new Set([2, 3]))"));
    }

    // a set method rejects a non-Set argument
    @Test
    public void test_non_set_argument_throws() {
        Assertions.assertThrows(TypeErrorException.class, () -> Interpreter.run("new Set([1]).union([2])"));
    }

    private static final String SET_LIKE = "let setLike = { size: 2, has: v => v === 2 || v === 3, "
            + "keys: () => [2, 3][Symbol.iterator]() }; ";

    @Test
    public void acceptsSetLikeArgument() {
        assertEquals("1,2,3", str(SET_LIKE + "[...new Set([1, 2]).union(setLike)].join(',')"));
        assertEquals("2", str(SET_LIKE + "[...new Set([1, 2]).intersection(setLike)].join(',')"));
        assertEquals("1", str(SET_LIKE + "[...new Set([1, 2]).difference(setLike)].join(',')"));
        assertEquals("1,3", str(SET_LIKE + "[...new Set([1, 2]).symmetricDifference(setLike)].join(',')"));
        assertTrue(bool(SET_LIKE + "new Set([2]).isSubsetOf(setLike)"));
        assertTrue(bool(SET_LIKE + "new Set([1, 2, 3]).isSupersetOf(setLike)"));
        assertTrue(bool(SET_LIKE + "new Set([1]).isDisjointFrom(setLike)"));
    }

    @Test
    public void coercesSizeThroughToNumber() {
        final var source = "let setLike = { size: { valueOf: () => 1 }, has: () => true, "
                + "keys: () => [7][Symbol.iterator]() }; [...new Set([7]).intersection(setLike)].join(',')";
        assertEquals("7", str(source));
    }

    @Test
    public void throwsOnNaNSize() {
        Assertions.assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Set([1]).union({ has: () => true, keys: () => [][Symbol.iterator]() })"));
        Assertions.assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Set([1]).union({ size: 1, keys: () => [][Symbol.iterator]() })"));
        Assertions.assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Set([1]).union({ size: 1, has: () => true })"));
    }

    @Test
    public void resultIsPlainSetNotSubclass() {
        final var source = "class S extends Set {}; "
                + "String(Object.getPrototypeOf(new S([1]).union(new Set([2]))) === Set.prototype)";
        assertEquals("true", str(source));
    }

    @Test
    public void intersectionPreservesSpecOrdering() {
        assertEquals("2,3", str("[...new Set([3, 2, 1]).intersection(new Set([2, 3]))].join(',')"));
        assertEquals("3,2", str("[...new Set([3, 2]).intersection(new Set([1, 2, 3]))].join(',')"));
    }

    @Test
    public void weakSetPrototypeHasOnlyWeakMethods() {
        assertEquals("undefined", str("typeof WeakSet.prototype.union"));
        assertEquals("undefined", str("typeof WeakSet.prototype.forEach"));
        Assertions.assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Set.prototype.add.call(new WeakSet(), {})"));
    }
}
