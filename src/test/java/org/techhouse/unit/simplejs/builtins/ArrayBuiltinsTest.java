package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class ArrayBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Array is callable as a constructor with a length or with elements
    @Test
    public void test_array_constructor() {
        assertEquals(3, num("Array(3).length"));
        assertEquals("1,2", str("Array(1, 2).join(',')"));
        assertTrue(bool("Array.isArray([1])"));
        assertFalse(bool("Array.isArray('x')"));
    }

    // map/filter/reduce transform elements
    @Test
    public void test_map_filter_reduce() {
        assertEquals("2,4,6", str("[1, 2, 3].map(x => x * 2).join(',')"));
        assertEquals("2,4", str("[1, 2, 3, 4].filter(x => x % 2 === 0).join(',')"));
        assertEquals(10, num("[1, 2, 3, 4].reduce((a, b) => a + b, 0)"));
        assertEquals(24, num("[1, 2, 3, 4].reduce((a, b) => a * b)"));
    }

    // find/some/every/forEach iterate with a predicate
    @Test
    public void test_predicates_and_foreach() {
        assertEquals(3, num("[1, 2, 3, 4].find(x => x > 2)"));
        assertTrue(bool("[1, 2, 3].some(x => x === 2)"));
        assertTrue(bool("[2, 4, 6].every(x => x % 2 === 0)"));
        assertEquals(6, num("let s = 0; [1, 2, 3].forEach(x => { s += x; }); s"));
    }

    // includes/indexOf use strict equality
    @Test
    public void test_includes_indexof() {
        assertTrue(bool("[1, 2, 3].includes(2)"));
        assertFalse(bool("[1, 2, 3].includes(9)"));
        assertEquals(1, num("['a', 'b', 'c'].indexOf('b')"));
        assertEquals(-1, num("[1].indexOf(9)"));
    }

    // slice/splice/concat/flat build new arrays
    @Test
    public void test_slice_splice_concat_flat() {
        assertEquals("2,3", str("[1, 2, 3, 4].slice(1, 3).join(',')"));
        assertEquals("3,4", str("[1, 2, 3, 4].slice(-2).join(',')"));
        assertEquals("2,3", str("let a = [1, 2, 3, 4]; a.splice(1, 2).join(',')"));
        assertEquals("1,9,4", str("let a = [1, 2, 3, 4]; a.splice(1, 2, 9); a.join(',')"));
        assertEquals("1,2,3", str("[1].concat([2, 3]).join(',')"));
        assertEquals("1,2,3,4", str("[1, [2, [3, 4]]].flat(2).join(',')"));
    }

    // push/pop/shift/unshift mutate and return the expected values
    @Test
    public void test_mutators() {
        assertEquals(3, num("let a = [1, 2]; a.push(3)"));
        assertEquals(3, num("let a = [1, 2, 3]; a.pop()"));
        assertEquals(1, num("let a = [1, 2, 3]; a.shift()"));
        assertEquals(3, num("let a = [2, 3]; a.unshift(1)"));
    }

    // sort orders by string by default and by a comparator when given
    @Test
    public void test_sort() {
        assertEquals("1,2,3", str("[3, 1, 2].sort().join(',')"));
        assertEquals("3,2,1", str("[1, 3, 2].sort((a, b) => b - a).join(',')"));
    }

    // reduce on an empty array without an initial value throws
    @Test
    public void test_reduce_empty_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[].reduce((a, b) => a + b)"));
    }

    // calling a callback method without a function throws
    @Test
    public void test_missing_callback_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1].map()"));
    }

    // findIndex/findLast/findLastIndex/lastIndexOf locate elements
    @Test
    public void test_find_variants() {
        assertEquals(1, num("[1, 2, 3].findIndex(x => x === 2)"));
        assertEquals(-1, num("[1, 2, 3].findIndex(x => x === 9)"));
        assertEquals(4, num("[1, 4, 2, 4, 3].findLast(x => x === 4)"));
        assertEquals(3, num("[1, 4, 2, 4, 3].findLastIndex(x => x === 4)"));
        assertEquals(3, num("[1, 2, 1, 2].lastIndexOf(2)"));
    }

    // reduceRight folds from the right, with and without an initial value
    @Test
    public void test_reduce_right() {
        assertEquals("3,2,1", str("['1', '2', '3'].reduceRight((a, b) => a + ',' + b)"));
        assertEquals(6, num("[1, 2, 3].reduceRight((a, b) => a + b, 0)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[].reduceRight((a, b) => a + b)"));
    }

    // flatMap maps then flattens one level
    @Test
    public void test_flatmap() {
        assertEquals("1,1,2,2", str("[1, 2].flatMap(x => [x, x]).join(',')"));
        assertEquals("1,2,3,4", str("[[1, 2], [3, 4]].flatMap(x => x).join(',')"));
    }

    // fill and copyWithin mutate in place
    @Test
    public void test_fill_copywithin() {
        assertEquals("0,9,9,3", str("let a = [0, 1, 2, 3]; a.fill(9, 1, 3); a.join(',')"));
        assertEquals("9,9,9,9", str("[1, 2, 3, 4].fill(9).join(',')"));
        assertEquals("4,5,3,4,5", str("let a = [1, 2, 3, 4, 5]; a.copyWithin(0, 3); a.join(',')"));
    }

    // reverse and at
    @Test
    public void test_reverse_at() {
        assertEquals("3,2,1", str("[1, 2, 3].reverse().join(',')"));
        assertEquals(3, num("[1, 2, 3].at(-1)"));
        assertEquals(1, num("[1, 2, 3].at(0)"));
        assertTrue(bool("[1, 2, 3].at(9) === undefined"));
    }

    // keys/values/entries return iterators consumable by for-of
    @Test
    public void test_iterators() {
        assertEquals("0,1,2", str("let r = []; for (const k of ['a', 'b', 'c'].keys()) r.push(k); r.join(',')"));
        assertEquals("a,b", str("let r = []; for (const v of ['a', 'b'].values()) r.push(v); r.join(',')"));
        assertEquals("0:a,1:b",
                str("let r = []; for (const e of ['a', 'b'].entries()) r.push(e[0] + ':' + e[1]); r.join(',')"));
    }

    // Array.from and Array.of build arrays
    @Test
    public void test_from_of() {
        assertEquals("1,2,3", str("Array.of(1, 2, 3).join(',')"));
        assertEquals("a,b", str("Array.from('ab').join(',')"));
        assertEquals("2,4", str("Array.from([1, 2], x => x * 2).join(',')"));
        assertEquals("1,2,3", str("Array.from(new Set([1, 2, 3])).join(',')"));
    }

    // the not-found and non-array branches
    @Test
    public void test_find_and_flatmap_edges() {
        assertTrue(bool("[1, 2].findLast(x => x === 9) === undefined"));
        assertEquals(-1, num("[1, 2].findLastIndex(x => x === 9)"));
        assertEquals(-1, num("[1, 2].lastIndexOf()"));
        assertEquals("1,2", str("[1, 2].flatMap(x => x).join(',')"));
        assertEquals("4,2,3,4,5", str("let a = [1, 2, 3, 4, 5]; a.copyWithin(0, 3, 4); a.join(',')"));
    }

    // toReversed returns a reversed copy and leaves the original untouched
    @Test
    public void test_to_reversed() {
        assertEquals("3,2,1", str("[1, 2, 3].toReversed().join(',')"));
        assertEquals("1,2,3", str("let a = [1, 2, 3]; a.toReversed(); a.join(',')"));
    }

    // toSorted returns a sorted copy without mutating the original
    @Test
    public void test_to_sorted() {
        assertEquals("1,2,3", str("[3, 1, 2].toSorted().join(',')"));
        assertEquals("3,1,2", str("let a = [3, 1, 2]; a.toSorted(); a.join(',')"));
        assertEquals("3,2,1", str("[1, 2, 3].toSorted((x, y) => y - x).join(',')"));
    }

    // toSpliced returns a copy with the splice applied, leaving the original intact
    @Test
    public void test_to_spliced() {
        assertEquals("1,9,4", str("[1, 2, 3, 4].toSpliced(1, 2, 9).join(',')"));
        assertEquals("1,2,3,4", str("let a = [1, 2, 3, 4]; a.toSpliced(1, 2, 9); a.join(',')"));
    }

    // with returns a copy with one index replaced; negative indices count from the end
    @Test
    public void test_with() {
        assertEquals("1,9,3", str("[1, 2, 3].with(1, 9).join(',')"));
        assertEquals("1,2,9", str("[1, 2, 3].with(-1, 9).join(',')"));
        assertEquals("1,2,3", str("let a = [1, 2, 3]; a.with(0, 9); a.join(',')"));
    }

    // with throws a RangeError for an out-of-bounds index
    @Test
    public void test_with_out_of_range_throws() {
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("[1, 2, 3].with(5, 9)"));
    }

    // toLocaleString joins per-element toLocaleString results; null/undefined become empty
    @Test
    public void test_to_locale_string() {
        assertEquals("1,2,3", str("[1, 2, 3].toLocaleString()"));
        assertEquals("a,,b", str("['a', null, 'b'].toLocaleString()"));
    }

    // toString joins with the default separator
    @Test
    public void test_to_string() {
        assertEquals("1,2", str("[1, 2].toString()"));
        assertEquals("", str("[].toString()"));
    }

    // concat splats an object that opts in via Symbol.isConcatSpreadable
    @Test
    public void test_is_concat_spreadable() {
        assertEquals(3, num("const o = {0: 'a', 1: 'b'}; o[Symbol.isConcatSpreadable] = true; [1].concat(o).length"));
        assertEquals("a", str("const o = {0: 'a'}; o[Symbol.isConcatSpreadable] = true; [1].concat(o)[1]"));
        assertEquals(2, num("[1].concat({a: 1}).length"));
        assertEquals(3, num("[1].concat([2, 3]).length"));
    }

    // Array.fromAsync drains an async iterable and a sync iterable of promises
    @Test
    public void test_from_async() {
        assertEquals(2, asyncLength("async function* g() { yield 1; yield 2 } out.v = await Array.fromAsync(g())"));
        assertEquals(2, asyncLength("out.v = await Array.fromAsync([Promise.resolve(1), 2])"));
        assertTrue(bool("typeof Array.fromAsync === 'function'"));
    }

    // The result of an async body is observed after the event loop has drained
    private static double asyncLength(String body) {
        final var out = (org.techhouse.simplejs.values.JsObject) Interpreter
                .run("const out = {}; (async () => { " + body + " })(); out");
        return ((org.techhouse.simplejs.values.JsArray) out.get("v")).length();
    }

    // includes uses SameValueZero, so NaN finds itself
    @Test
    public void test_includes_same_value_zero() {
        assertTrue(bool("[NaN].includes(NaN)"));
        assertTrue(bool("[-0].includes(0)"));
        assertTrue(bool("[0].includes(-0)"));
    }

    // indexOf keeps strict equality, so NaN is never found
    @Test
    public void test_index_of_nan_unchanged() {
        assertEquals(-1, num("[NaN].indexOf(NaN)"));
        assertEquals(0, num("[-0].indexOf(0)"));
    }

    // a hole reads as undefined for includes but is skipped by indexOf
    @Test
    public void test_includes_finds_hole_as_undefined() {
        assertTrue(bool("[,].includes(undefined)"));
        assertEquals(-1, num("[,].indexOf(undefined)"));
    }

    // includes honours the fromIndex argument
    @Test
    public void test_includes_from_index() {
        assertFalse(bool("[1, 2].includes(1, 1)"));
        assertTrue(bool("[1, 2].includes(2, 1)"));
        assertTrue(bool("[1, 2, 3].includes(3, -1)"));
        assertFalse(bool("[1, 2, 3].includes(1, -1)"));
    }

    // includes with no argument searches for undefined
    @Test
    public void test_includes_no_argument() {
        assertTrue(bool("[undefined].includes()"));
        assertFalse(bool("[1].includes()"));
    }
}
