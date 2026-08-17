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

    // A non-callable predicate throws immediately, even on an empty array
    @Test
    public void test_find_throws_on_non_callable_predicate_even_on_empty_array() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[].find(null)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1, 2, 3].find(null)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[].map(1)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[].forEach('')"));
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

    // Array.from falls back to array-like semantics for a non-iterable source
    @Test
    public void test_array_from_array_like_object() {
        assertEquals("a,b,c", str("Array.from({length: 3, 0: 'a', 1: 'b', 2: 'c'}).join(',')"));
        assertEquals("", str("Array.from({length: 0}).join(',')"));
    }

    // Array.from honours the mapfn's thisArg
    @Test
    public void test_array_from_map_this_arg() {
        assertEquals(5, num("let o = {v: 5}; Array.from([1], function() { return this.v; }, o)[0]"));
    }

    // Array.from called with a custom constructor builds via that constructor instead of a plain array
    @Test
    public void test_array_from_call_custom_constructor() {
        final var source = """
                function Ctor(len) { this.length = 0; this.fromCtor = true; }
                let a = Array.from.call(Ctor, [1, 2]);
                a.fromCtor + ',' + a[0] + ',' + a[1]
                """;
        assertEquals("true,1,2", str(source));
    }

    // A non-extensible custom-constructed target rejects the new indexed property with a TypeError
    @Test
    public void test_array_from_call_custom_constructor_rejects_definition() {
        final var source = """
                function Ctor() { this.length = 0; Object.preventExtensions(this); }
                Array.from.call(Ctor, [1]);
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
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

    // concat splats an object that opts in via Symbol.isConcatSpreadable, over its own length
    @Test
    public void test_is_concat_spreadable() {
        assertEquals(3, num(
                "const o = {0: 'a', 1: 'b', length: 2}; o[Symbol.isConcatSpreadable] = true; [1].concat(o).length"));
        assertEquals("a", str("const o = {0: 'a', length: 1}; o[Symbol.isConcatSpreadable] = true; [1].concat(o)[1]"));
        assertEquals(1, num("const o = {0: 'a'}; o[Symbol.isConcatSpreadable] = true; [1].concat(o).length"));
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

    // a raw primitive receiver is ToObject-boxed into an empty array-like rather than rejected
    @Test
    public void test_generic_methods_accept_primitive_receiver() {
        assertEquals(0, num("Array.prototype.map.call(5, x => x).length"));
        assertTrue(bool("Array.prototype.every.call(false, () => false)"));
        assertEquals(1, num("Array.prototype.push.call(true, 'x')"));
        assertEquals("object", str("Array.prototype.map.call('ab', (v, i, o) => typeof o)[0]"));
    }

    // the callback's third argument is the receiver itself, not a copy of it
    @Test
    public void test_callback_receives_original_receiver_not_snapshot() {
        assertTrue(bool("const o = {length: 1, 0: 'a'}; let seen; Array.prototype.forEach.call(o, (v, i, r) => "
                + "{ seen = r; }); seen === o"));
        assertTrue(bool("const a = [1]; let seen; a.map((v, i, r) => { seen = r; }); seen === a"));
        assertTrue(bool("const o = {length: 1, 0: 'a'}; let seen; Array.prototype.reduce.call(o, (acc, v, i, r) => "
                + "{ seen = r; return acc; }, 0); seen === o"));
    }

    // an exotic (non-JsObject) receiver is read through the member seam rather than rejected
    @Test
    public void test_accepts_exotic_array_like_receiver() {
        assertEquals("a-b", str("function f() {} Object.defineProperty(f, 'length', {value: 2});"
                + " f[0] = 'a'; f[1] = 'b'; Array.prototype.join.call(f, '-')"));
        assertEquals("a-b", str("(function() { return Array.prototype.join.call(arguments, '-'); })('a', 'b')"));
        assertEquals("a-b-c", str("Array.prototype.join.call(new String('abc'), '-')"));
    }

    // a mutating method writes through to a generic receiver instead of a discarded snapshot
    @Test
    public void test_writes_through_to_generic_receiver() {
        assertEquals("x,1", str("const o = {length: 0}; Array.prototype.push.call(o, 'x'); o[0] + ',' + o.length"));
        assertEquals("3,2,1", str("const o = {0: 1, 1: 2, 2: 3, length: 3}; Array.prototype.reverse.call(o);"
                + " o[0] + ',' + o[1] + ',' + o[2]"));
        assertEquals("b,1,false",
                str("const o = {0: 'a', 1: 'b', length: 2};" + " const first = Array.prototype.shift.call(o);"
                        + " o[0] + ',' + o.length + ',' + o.hasOwnProperty('1')"));
    }

    // a rejected [[Set]] on the receiver is a TypeError, not a silently dropped write
    @Test
    public void test_throws_on_frozen_receiver_write() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Array.prototype.push.call(Object.freeze([1]), 2)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Array.prototype.push.call('ab', 'c')"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const o = {length: 0}; Object.freeze(o); Array.prototype.push.call(o, 1)"));
    }

    // indexOf honours fromIndex, including the negative and non-finite forms
    @Test
    public void test_index_of_honours_from_index() {
        assertEquals(2, num("[1, 2, 1].indexOf(1, 1)"));
        assertEquals(-1, num("[1, 2, 1].indexOf(1, 3)"));
        assertEquals(2, num("[1, 2, 1].indexOf(1, -1)"));
        assertEquals(0, num("[1, 2, 1].indexOf(1, -9)"));
        assertEquals(-1, num("[1, 2, 1].indexOf(1, Infinity)"));
        assertEquals(0, num("[1, 2, 1].indexOf(1, 'one')"));
    }

    // lastIndexOf honours fromIndex, counting from the end for a negative one
    @Test
    public void test_last_index_of_honours_from_index() {
        assertEquals(1, num("[0, 1, 1].lastIndexOf(1, 1)"));
        assertEquals(2, num("[0, 1, 1].lastIndexOf(1)"));
        assertEquals(1, num("[0, 1, 1].lastIndexOf(1, -2)"));
        assertEquals(-1, num("[0, 1, 1].lastIndexOf(1, -9)"));
        assertEquals(-1, num("[0, 1, 1].lastIndexOf(1, -Infinity)"));
        assertEquals(2, num("[0, 1, 1].lastIndexOf(1, Infinity)"));
    }

    // concat consults Symbol.isConcatSpreadable before falling back to IsArray
    @Test
    public void test_concat_honours_is_concat_spreadable() {
        assertEquals(3, num(
                "const o = {0: 'a', 1: 'b', length: 2}; o[Symbol.isConcatSpreadable] = true; [1].concat(o).length"));
        assertEquals(2, num("const o = {0: 'a', length: 1}; [1].concat(o).length"));
        assertEquals(1,
                num("const o = {0: 'a', length: 1}; o[Symbol.isConcatSpreadable] = false; [].concat(o).length"));
    }

    // the default comparator compares the ToString of each element, and undefined sorts last
    @Test
    public void test_sort_default_comparator_uses_to_string() {
        assertEquals("1,10,9", str("[10, 9, 1].sort().join(',')"));
        assertEquals("1,10,9,", str("[10, 9, undefined, 1].sort().join(',')"));
        assertEquals("1,2,,3", str("const a = [2, , 1]; a.sort(); a.join(',') + ',' + a.length"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1, 2].sort('x')"));
    }

    // a length past the int range is walked lazily rather than materialised
    @Test
    public void test_length_beyond_integer_max_does_not_throw() {
        assertEquals(9007199254740990D,
                num("Array.prototype.lastIndexOf.call({length: 9007199254740991, 9007199254740990: 'c'}, 'c')"));
        assertEquals(9007199254740990D, num("let at = -1; Array.prototype.findLast.call({length: Number.MAX_VALUE},"
                + " (v, i) => { at = i; return true; }); at"));
        assertFalse(bool("Array.prototype.includes.call({length: Infinity, 0: 'a'}, 'a', 9007199254740990)"));
    }

    // splice shifts the tail in both directions and reports the removed elements
    @Test
    public void test_splice_grows_and_shrinks() {
        assertEquals("1,9,10,2,3", str("const a = [1, 2, 3]; a.splice(1, 0, 9, 10); a.join(',')"));
        assertEquals("1,3", str("const a = [1, 2, 3]; a.splice(1, 1); a.join(',')"));
        assertEquals("2,3", str("[1, 2, 3].splice(1).join(',')"));
        assertEquals("", str("[1, 2, 3].splice().join(',')"));
        assertEquals("1,9,10,3", str("const o = {0: 1, 1: 2, 2: 3, length: 3};"
                + " Array.prototype.splice.call(o, 1, 1, 9, 10); Array.prototype.join.call(o, ',')"));
    }

    // toSpliced builds the copy from the head, the insertions and the tail
    @Test
    public void test_to_spliced_variants() {
        assertEquals("1,9,10,2,3", str("[1, 2, 3].toSpliced(1, 0, 9, 10).join(',')"));
        assertEquals("1", str("[1, 2, 3].toSpliced(1).join(',')"));
        assertEquals("1,2,3", str("[1, 2, 3].toSpliced().join(',')"));
    }

    // copyWithin copies backwards when the ranges overlap, and deletes an absent source
    @Test
    public void test_copy_within_overlapping_and_holes() {
        assertEquals("1,1,2,3", str("[1, 2, 3, 4].copyWithin(1, 0).join(',')"));
        assertEquals("1,2,3", str("[1, 2, 3].copyWithin(0, 5).join(',')"));
        assertTrue(bool("const a = [, 2]; a.copyWithin(1, 0); !a.hasOwnProperty('1')"));
    }

    // ArraySpeciesCreate honours a species constructor and rejects a non-constructor one
    @Test
    public void test_species_create_uses_the_constructor() {
        assertTrue(bool("class C { constructor(n) { this.tag = true; } static get [Symbol.species]() { return C; } }"
                + " const a = [1, 2]; a.constructor = C; const r = a.map(x => x); r.tag === true && r[0] === 1"));
        assertTrue(bool("function D() { this.tag = true; }"
                + " const a = [1]; a.constructor = D; Array.isArray(a.map(x => x))"));
        assertEquals(2, num("class C { static get [Symbol.species]() { return C; } }"
                + " const a = [1, 2]; a.constructor = C; a.slice().length"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const a = [1]; a.constructor = 5; a.filter(x => true)"));
        assertEquals("1", str("const o = {0: 1, length: 1}; Array.prototype.slice.call(o).join(',')"));
    }

    // toString falls back to Object.prototype.toString when `join` is not callable
    @Test
    public void test_to_string_falls_back_to_object_to_string() {
        assertEquals("J", str("const a = [1, 2]; a.join = () => 'J'; a.toString()"));
        assertEquals("[object Array]", str("const a = [1, 2]; a.join = 1; a.toString()"));
    }

    // reverse swaps a hole with a value, so the hole moves rather than becoming undefined
    @Test
    public void test_reverse_moves_holes() {
        assertTrue(bool("const a = [, 1]; a.reverse(); a[0] === 1 && !a.hasOwnProperty('1')"));
        assertEquals("3,2,1", str("const o = {0: 1, 1: 2, 2: 3, length: 3};"
                + " Array.prototype.reverse.call(o); Array.prototype.join.call(o, ',')"));
    }

    // an out-of-range array length is a RangeError, from the constructor and from a by-copy method
    @Test
    public void test_invalid_array_length_throws() {
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class, () -> Interpreter.run("Array(-1)"));
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class, () -> Interpreter.run("Array(1.5)"));
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("Array.prototype.toReversed.call({length: 4294967295})"));
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("Array.prototype.sort.call({length: 9007199254740991})"));
    }

    // the separator and the elements coerce through ToPrimitive, not a bare toString
    @Test
    public void test_join_coerces_the_separator() {
        assertEquals("1foo2", str("[1, 2].join({toString: () => 'foo'})"));
        assertEquals("1bar2", str("[1, 2].join({toString: undefined, valueOf: () => 'bar'})"));
        assertEquals("102", str("[1, 2].join(0)"));
    }

    // IsArray sees through a proxy to its target, recognises the intrinsic Array.prototype and rejects
    // a revoked proxy. Array.prototype deliberately carries no own length: giving it one shadowed the
    // wrapped array's length for `class A extends Array`, breaking six subclassing tests.
    @Test
    public void test_is_array_covers_proxies_and_the_intrinsic_prototype() {
        assertTrue(bool("Array.isArray(new Proxy([], {}))"));
        assertFalse(bool("Array.isArray(new Proxy({}, {}))"));
        assertTrue(bool("Array.isArray(Array.prototype)"));
        assertTrue(bool("Array.prototype.length === undefined"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const h = Proxy.revocable([], {}); h.revoke(); Array.isArray(h.proxy)"));
    }

    // Array.of/from honour a constructor `this`: the iterator path constructs with no arguments and
    // the array-like path with the length, and both finish by setting `length` on the result
    @Test
    public void test_of_and_from_honour_a_constructor_receiver() {
        assertEquals(2, num("function C(n) { this.n = n; } Array.of.call(C, 1, 2).n"));
        assertTrue(bool("function C() {} Array.of.call(C, 1) instanceof C"));
        assertEquals(4, num("let seen; function C(n) { seen = n; }" + " Array.from.call(C, {length: 4, 0: 1}); seen"));
        assertEquals("undefined",
                str("let seen = 'x'; function C(n) { seen = String(n); }" + " Array.from.call(C, [1, 2]); seen"));
        assertEquals(1, num("let hits = 0; function C() {"
                + " Object.defineProperty(this, 'length', {set(v) { hits++; }}); }" + " Array.of.call(C, 'a'); hits"));
    }

    // Array.from walks an iterable lazily and closes it when the map function throws
    @Test
    public void test_from_is_lazy_and_closes_the_iterator() {
        assertEquals("0,1",
                str("const a = [0, 1, 2, 3];" + " Array.from(a, v => { a.length = 2; return v; }).join(',')"));
        assertEquals(1,
                num("let closed = 0; const items = {};"
                        + " items[Symbol.iterator] = () => ({next: () => ({done: false, value: 1}),"
                        + " return: () => { closed++; return {}; }});"
                        + " try { Array.from(items, () => { throw new Error('x'); }); } catch (e) {} closed"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Array.from([], null)"));
    }

    // the array-like path fills every index, so a length with no indexes yields real undefined values
    @Test
    public void test_from_array_like_has_no_holes() {
        assertTrue(bool("Array.from({length: 3}).hasOwnProperty(0)"));
        assertEquals(3, num("Array.from({length: 3}).map(() => 1).length"));
        assertEquals("1,1,1", str("Array.from({length: 3}).map(() => 1).join(',')"));
        assertEquals(0, num("Array.from(new ArrayBuffer(8)).length"));
    }

    // Symbol.isConcatSpreadable overrides IsArray in both directions, on an array and on any other
    // exotic object
    @Test
    public void test_concat_honours_is_concat_spreadable_on_exotic_objects() {
        assertEquals(1, num("const a = [1, 2]; a[Symbol.isConcatSpreadable] = false; [].concat(a).length"));
        assertEquals(1, num("const a = [1, 2]; a[Symbol.isConcatSpreadable] = null; [].concat(a).length"));
        assertEquals(3, num("const r = /x/; r[Symbol.isConcatSpreadable] = true;"
                + " r.length = 3; r[0] = 1; r[1] = 2; r[2] = 3; [].concat(r).length"));
        assertEquals("isConcatSpreadable",
                str("const a = []; const calls = [];" + " Object.defineProperty(a, Symbol.isConcatSpreadable,"
                        + " {get() { calls.push('isConcatSpreadable'); }}); a.concat(1); calls.join(',')"));
        assertEquals(3, num("[].concat([1, 2], 3).length"));
    }

    // flat flattens a proxy whose target is an array, because it asks IsArray rather than the type
    @Test
    public void test_flat_flattens_through_a_proxy() {
        assertEquals("1,2,3", str("[1, new Proxy([2, 3], {})].flat().join(',')"));
    }

    // an array iterator that has run out stays done, so an element pushed afterwards is never seen
    @Test
    public void test_array_iterator_stays_done() {
        assertTrue(bool("const a = []; const it = a.values(); a.push('a');"
                + " const first = it.next(); it.next(); a.push('b');"
                + " first.value === 'a' && it.next().done === true"));
    }

    // toLocaleString invokes each element's toLocaleString, falling back to its toString
    @Test
    public void test_to_locale_string_invokes_the_element_methods() {
        assertEquals("A,B", str("[{toLocaleString: () => 'A'}, {toLocaleString: () => 'B'}].toLocaleString()"));
        assertEquals("boolean,boolean", str("Boolean.prototype.toString = function() { return typeof this; };"
                + " [true, false].toLocaleString()"));
    }

    // push/pop/shift/unshift end in Set(O, "length", ...), which is a TypeError on a frozen length
    @Test
    public void test_length_write_rejection_throws_from_the_mutators() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const a = []; Object.defineProperty(a, 'length', {writable: false}); a.push()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const a = []; Object.defineProperty(a, 'length', {writable: false}); a.pop()"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("const a = []; Object.defineProperty(a, 'length', {writable: false}); a.shift()"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("const a = []; Object.defineProperty(a, 'length', {writable: false}); a.unshift()"));
    }

    // an index write the array does not own reaches a setter its prototype owns
    @Test
    public void test_index_write_reaches_an_inherited_setter() {
        assertEquals(1,
                num("let hits = 0;"
                        + " Object.defineProperty(Array.prototype, '0', {set(v) { hits++; }, configurable: true});"
                        + " const a = []; try { a.push(1); } catch (e) {} delete Array.prototype[0]; hits"));
    }
}
