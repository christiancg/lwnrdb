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

    // find/some/every/forEach iterate with a predicate
    @Test
    public void test_predicates_and_foreach() {
        assertEquals(3, num("[1, 2, 3, 4].find(x => x > 2)"));
        assertTrue(bool("[1, 2, 3].some(x => x === 2)"));
        assertTrue(bool("[2, 4, 6].every(x => x % 2 === 0)"));
        assertEquals(6, num("let s = 0; [1, 2, 3].forEach(x => { s += x; }); s"));
    }

    // push/pop/shift/unshift mutate and return the expected values
    @Test
    public void test_mutators() {
        assertEquals(3, num("let a = [1, 2]; a.push(3)"));
        assertEquals(3, num("let a = [1, 2, 3]; a.pop()"));
        assertEquals(1, num("let a = [1, 2, 3]; a.shift()"));
        assertEquals(3, num("let a = [2, 3]; a.unshift(1)"));
    }

    // calling a callback method without a function throws
    @Test
    public void test_missing_callback_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1].map()"));
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

    // concat consults Symbol.isConcatSpreadable before falling back to IsArray
    @Test
    public void test_concat_honours_is_concat_spreadable() {
        assertEquals(3, num(
                "const o = {0: 'a', 1: 'b', length: 2}; o[Symbol.isConcatSpreadable] = true; [1].concat(o).length"));
        assertEquals(2, num("const o = {0: 'a', length: 1}; [1].concat(o).length"));
        assertEquals(1,
                num("const o = {0: 'a', length: 1}; o[Symbol.isConcatSpreadable] = false; [].concat(o).length"));
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

    // IsArray sees through a proxy to its target, recognises the intrinsic Array.prototype and rejects
    // a revoked proxy. Array.prototype carries a real own "length" (0, writable/non-enumerable/
    // non-configurable per spec 22.1.3) - a prior attempt to add this shadowed a `class A extends
    // Array` instance's own length, breaking six subclassing tests, because the prototype-chain walk
    // in MemberEvaluator.getObjectMember found Array.prototype's own "length" before ever consulting
    // the instance's wrapped primitive. Fixed by giving the wrapped-primitive delegation unconditional
    // priority for "length" in both getObjectMember and setObjectMember (see the comments there and
    // JsArrayLengthTest's subclassing coverage) rather than omitting the prototype's own length.
    @Test
    public void test_is_array_covers_proxies_and_the_intrinsic_prototype() {
        assertTrue(bool("Array.isArray(new Proxy([], {}))"));
        assertFalse(bool("Array.isArray(new Proxy({}, {}))"));
        assertTrue(bool("Array.isArray(Array.prototype)"));
        assertTrue(bool("Array.prototype.length === 0"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const h = Proxy.revocable([], {}); h.revoke(); Array.isArray(h.proxy)"));
    }

    // %Array.prototype% is a genuine Array exotic object per spec (22.1.3): an index write on it
    // bumps "length" the way a real array's [[DefineOwnProperty]] would, and its own brand is
    // "Array" (test262 built-ins/Array/prototype/exotic-array.js). Regression test for the follow-up
    // fix that closed this without converting %Array.prototype%'s Java type away from JsObject - the
    // subclassing coverage above must stay green alongside this, since both share the same "length"
    // choke point in MemberEvaluator.
    @Test
    public void test_array_prototype_is_a_genuine_array_exotic_object() {
        assertEquals(3,
                num("Array.prototype[2] = 42; const len = Array.prototype.length; delete Array.prototype[2]; len"));
        assertTrue(bool("Object.prototype.toString.call(Array.prototype) === '[object Array]'"));
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
}
