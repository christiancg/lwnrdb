package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;

public class InterpreterIterationTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // for-of sums array elements
    @Test
    public void test_for_of_array() {
        assertEquals(6, num("let s = 0; for (const x of [1, 2, 3]) s += x; s"));
    }

    // for-of iterates string characters
    @Test
    public void test_for_of_string() {
        assertEquals("abc", str("let s = ''; for (const c of 'abc') s += c; s"));
    }

    // for-of destructures each element
    @Test
    public void test_for_of_with_destructuring() {
        assertEquals(10, num("let s = 0; for (const [a, b] of [[1, 2], [3, 4]]) s += a + b; s"));
    }

    // let-bound for-of gives each iteration its own binding captured by closures
    @Test
    public void test_for_of_let_closure_per_iteration() {
        final var source = """
                let fns = [];
                for (const x of [1, 2, 3]) fns.push(() => x);
                fns[0]() + fns[1]() + fns[2]()
                """;
        assertEquals(6, num(source));
    }

    // break and continue control the for-of loop
    @Test
    public void test_for_of_break_continue() {
        assertEquals(3, num("let s = 0; for (const x of [1, 2, 3, 4]) { if (x === 3) break; s += x; } s"));
        assertEquals(4, num("let s = 0; for (const x of [1, 2, 3, 4]) { if (x % 2 === 0) continue; s += x; } s"));
    }

    // a labeled break exits the outer for-of
    @Test
    public void test_for_of_labeled_break() {
        final var source = """
                let s = 0;
                outer: for (const x of [1, 2, 3]) {
                    for (const y of [1, 2, 3]) {
                        if (x + y === 4) break outer;
                        s += 1;
                    }
                }
                s
                """;
        assertEquals(2, num(source));
    }

    // for-of over a non-iterable throws a TypeError
    @Test
    public void test_for_of_non_iterable_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("for (const x of 5) {}"));
    }

    // for-in enumerates object keys in insertion order
    @Test
    public void test_for_in_object_keys() {
        assertEquals("ab", str("let s = ''; for (const k in {a: 1, b: 2}) s += k; s"));
    }

    // for-in enumerates array indices as strings
    @Test
    public void test_for_in_array_indices() {
        assertEquals("012", str("let s = ''; for (const i in [9, 8, 7]) s += i; s"));
    }

    // for-in enumerates string indices
    @Test
    public void test_for_in_string_indices() {
        assertEquals("01", str("let s = ''; for (const i in 'ab') s += i; s"));
    }

    // for-in over null or undefined iterates nothing
    @Test
    public void test_for_in_over_null_undefined_no_iteration() {
        assertEquals(0, num("let s = 0; for (const k in null) s++; for (const k in undefined) s++; s"));
    }

    // for-in accepts an existing variable as the assignment target
    @Test
    public void test_for_in_assignment_target() {
        assertEquals("y", str("let k; let last = ''; for (k in {x: 1, y: 2}) last = k; last"));
    }

    // spread expands a generator into an array literal
    @Test
    public void test_spread_generator_into_array() {
        assertEquals("1,2,3", str("function* g() { yield 1; yield 2; yield 3; } [...g()].join(',')"));
    }

    // for-of drives an object exposing a custom [Symbol.iterator]
    @Test
    public void test_for_of_custom_iterable() {
        final var source = """
                let obj = {
                    [Symbol.iterator]() {
                        let i = 0;
                        return { next() { return i < 3 ? {value: i++, done: false} : {value: 0, done: true}; } };
                    }
                };
                let s = 0;
                for (const x of obj) s += x;
                s
                """;
        assertEquals(3, num(source));
    }

    // spread expands a custom iterable into an array literal
    @Test
    public void test_spread_custom_iterable() {
        final var source = """
                let obj = {
                    [Symbol.iterator]() {
                        let i = 1;
                        return { next() { return i <= 3 ? {value: i++, done: false} : {value: 0, done: true}; } };
                    }
                };
                [...obj].join(',')
                """;
        assertEquals("1,2,3", str(source));
    }

    // an early break calls the iterator's return() to close it
    @Test
    public void test_for_of_early_close_calls_return() {
        final var source = """
                let closed = false;
                let obj = {
                    [Symbol.iterator]() {
                        let i = 0;
                        return {
                            next() { return {value: i++, done: false}; },
                            return() { closed = true; return {done: true}; }
                        };
                    }
                };
                for (const x of obj) { if (x === 2) break; }
                closed
                """;
        assertTrue(((JsBoolean) Interpreter.run(source)).getValue());
    }

    // an object with no [Symbol.iterator] is not iterable
    @Test
    public void test_for_of_plain_object_not_iterable() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("for (const x of {a: 1}) {}"));
    }

    // iterator helpers chain lazily over a generator
    @Test
    public void test_iterator_map_filter_take_chain() {
        final var source = """
                function* g() { yield 1; yield 2; yield 3; yield 4; }
                [...g().map(x => x * 2).filter(x => x > 4).take(1)][0]
                """;
        assertEquals(6, num(source));
    }

    // toArray, reduce and forEach consume a generator eagerly
    @Test
    public void test_iterator_to_array_reduce_for_each() {
        assertEquals("2,4,6", str("function* g(){yield 1;yield 2;yield 3;} g().map(x=>x*2).toArray().join(',')"));
        assertEquals(6, num("function* g(){yield 1;yield 2;yield 3;} g().reduce((a, b) => a + b, 0)"));
        assertEquals(6, num("function* g(){yield 1;yield 2;yield 3;} let s=0; g().forEach(x=>s+=x); s"));
    }

    // drop skips a prefix and flatMap flattens one level
    @Test
    public void test_iterator_drop_flat_map() {
        assertEquals("3,4", str("function* g(){yield 1;yield 2;yield 3;yield 4;} g().drop(2).toArray().join(',')"));
        assertEquals("1,10,2,20",
                str("function* g(){yield 1;yield 2;} g().flatMap(x => [x, x * 10]).toArray().join(',')"));
    }

    // some, every and find short-circuit
    @Test
    public void test_iterator_some_every_find() {
        assertTrue(bool("function* g(){yield 1;yield 2;yield 3;} g().some(x => x === 2)"));
        assertFalse(bool("function* g(){yield 1;yield 2;yield 3;} g().every(x => x < 3)"));
        assertEquals(4, num("function* g(){yield 2;yield 4;yield 6;} g().find(x => x > 3)"));
    }

    // helpers are lazy: take(2) does not pull the whole source
    @Test
    public void test_iterator_helpers_are_lazy() {
        final var source = """
                let pulled = 0;
                function* g() { while (true) { pulled++; yield pulled; } }
                let taken = [...g().take(2)];
                taken.length * 100 + pulled
                """;
        assertEquals(202, num(source));
    }

    // built-in array iterators inherit the helpers
    @Test
    public void test_array_values_inherits_helpers() {
        assertEquals("2,3,4", str("[1,2,3].values().map(x => x + 1).toArray().join(',')"));
    }

    // Iterator.from wraps a plain iterator object
    @Test
    public void test_iterator_from_plain_iterator() {
        final var source = """
                let i = 0;
                const it = { next() { i++; return i <= 3 ? {value: i, done: false} : {value: undefined, done: true}; } };
                Iterator.from(it).map(x => x * x).toArray().join(',')
                """;
        assertEquals("1,4,9", str(source));
    }
    // The iterator protocol walks a string by code point, so an astral character is one step
    @Test
    public void test_spread_string_astral() {
        assertEquals(3, num("[...'ab\\u{1F600}'].length"));
        assertEquals("😀", str("[...'ab\\u{1F600}'][2]"));
        assertEquals(2, num("[...'\\u{1F600}\\u{1F600}'].length"));
        assertEquals(0, num("[...''].length"));
    }

    // for-of over a string yields whole code points
    @Test
    public void test_for_of_string_astral() {
        assertEquals(2, num("let n = 0; for (const c of 'a\\u{1F600}') { n++; } n"));
        assertEquals("😀", str("let last = ''; for (const c of 'a\\u{1F600}') { last = c; } last"));
    }

    // Array destructuring of a string follows the iterator, not the index properties
    @Test
    public void test_destructure_string_astral() {
        assertEquals("😀", str("const [a, b] = 'a\\u{1F600}'; b"));
    }

    // Array.from and the explicit Symbol.iterator agree with the spread form
    @Test
    public void test_array_from_string_astral() {
        assertEquals(2, num("Array.from('a\\u{1F600}').length"));
        assertEquals(1, num("[...('\\u{1F600}')[Symbol.iterator]()].length"));
    }

    // Indexed access, length and split('') stay code-unit based
    @Test
    public void test_string_index_stays_code_unit() {
        assertTrue(bool("'\\u{1F600}'[0].length === 1"));
        assertEquals(2, num("'\\u{1F600}'.length"));
        assertEquals(3, num("'a\\u{1F600}'.split('').length"));
        assertEquals("0,1", str("Object.keys({...'\\u{1F600}'}).join(',')"));
        assertEquals(2, num("Array.prototype.slice.call('\\u{1F600}').length"));
    }

    // A lone surrogate is yielded as its own single-unit string rather than dropped
    @Test
    public void test_lone_surrogate_is_preserved() {
        assertEquals(2, num("[...'\\uD800x'].length"));
        assertEquals(1, num("[...'\\uD800x'][0].length"));
    }

    // for-of over a string honours break
    @Test
    public void test_for_of_string_break() {
        assertEquals(1, num("let n = 0; for (const c of 'a\\u{1F600}b') { n++; break; } n"));
    }

    // a class whose [Symbol.iterator] is a generator method drives for-of
    @Test
    public void test_for_of_over_object_with_generator_symbol_iterator() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 7; } }
                let s = 0; for (const x of new C()) s += x; s
                """;
        assertEquals(7, num(source));
    }

    // spread consumes a generator-valued [Symbol.iterator]
    @Test
    public void test_spread_object_with_generator_symbol_iterator() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 1; yield 2; } }
                [...new C()].join(',')
                """;
        assertEquals("1,2", str(source));
    }

    // the object-literal form of a generator [Symbol.iterator] method works too
    @Test
    public void test_spread_object_literal_generator_method() {
        assertEquals("1", str("[...{ *[Symbol.iterator]() { yield 1; } }].join(',')"));
    }

    // a plain generator function stored under [Symbol.iterator] is equally iterable
    @Test
    public void test_symbol_iterator_as_plain_generator_function_property() {
        final var source = """
                const o = { [Symbol.iterator]: function* () { yield 1; yield 2; } };
                [...o].join(',')
                """;
        assertEquals("1,2", str(source));
    }

    // array destructuring pulls from a generator-valued iterable
    @Test
    public void test_array_destructuring_from_generator_iterable() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 4; yield 5; } }
                const [a, b] = new C(); a * 10 + b
                """;
        assertEquals(45, num(source));
    }

    // Array.from materialises a generator-valued iterable
    @Test
    public void test_array_from_generator_iterable() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 1; yield 2; yield 3; } }
                Array.from(new C()).length
                """;
        assertEquals(3, num(source));
    }

    // yield* delegates to a generator-valued iterable
    @Test
    public void test_yield_star_over_generator_iterable() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 1; yield 2; } }
                function* g() { yield* new C(); yield 3; }
                [...g()].join(',')
                """;
        assertEquals("1,2,3", str(source));
    }

    // new Set(iterable) accepts a generator-valued [Symbol.iterator]
    @Test
    public void test_new_set_from_generator_iterable() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 1; yield 1; yield 2; } }
                new Set(new C()).size
                """;
        assertEquals(2, num(source));
    }

    // an iterator built by another builtin (a Map iterator) is object-like enough to drive iteration
    @Test
    public void test_symbol_iterator_returning_map_iterator() {
        final var source = """
                const o = { [Symbol.iterator]() { return new Map([[1, 2]])[Symbol.iterator](); } };
                [...o][0].join(',')
                """;
        assertEquals("1,2", str(source));
    }

    // a primitive returned from [Symbol.iterator] is still rejected
    @Test
    public void test_symbol_iterator_returning_primitive_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[...{ [Symbol.iterator]() { return 1; } }]"));
    }

    // undefined returned from [Symbol.iterator] is still rejected
    @Test
    public void test_symbol_iterator_returning_undefined_throws() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("[...{ [Symbol.iterator]() { return undefined; } }]"));
    }

    // closing a generator-valued iterable through the member return() unwinds its finally
    @Test
    public void test_generator_iterable_close_runs_finally() {
        final var source = """
                let closed = false;
                class C {
                    *[Symbol.iterator]() {
                        try { yield 1; yield 2; } finally { closed = true; }
                    }
                }
                for (const x of new C()) { break; }
                closed
                """;
        assertTrue(bool(source));
    }

    // the member path honours a patched Generator.prototype.next. A generator instance's own
    // [[Prototype]] is its function's `prototype` object (one level below %GeneratorPrototype%), so
    // reaching the shared intrinsic that every generator - including the unrelated anonymous one
    // below - inherits from takes a double unwrap.
    @Test
    public void test_patched_next_on_generator_iterable() {
        final var source = """
                function* seed() { yield 1; }
                const proto = Object.getPrototypeOf(Object.getPrototypeOf(seed()));
                const original = proto.next;
                proto.next = function () { return { value: 9, done: true }; };
                const o = { [Symbol.iterator]() { return (function* () { yield 1; yield 2; })(); } };
                const count = [...o].length;
                proto.next = original;
                count
                """;
        assertEquals(0, num(source));
    }

    // isObjectLike is a deny-list: every non-primitive value counts as an object
    @Test
    public void test_is_object_like_covers_non_primitives() {
        assertTrue(InterpreterUtils.isObjectLike(new JsObject()));
        assertTrue(InterpreterUtils.isObjectLike(new JsArray()));
        assertTrue(InterpreterUtils.isObjectLike(new JsMap()));
        assertTrue(InterpreterUtils.isObjectLike(new JsSet()));
        assertTrue(InterpreterUtils.isObjectLike(new JsDate(0)));
        assertTrue(InterpreterUtils.isObjectLike(new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance())));
    }

    // isObjectLike rejects each of the seven primitive types
    @Test
    public void test_is_object_like_rejects_primitives() {
        assertFalse(InterpreterUtils.isObjectLike(JsUndefined.getInstance()));
        assertFalse(InterpreterUtils.isObjectLike(JsNull.getInstance()));
        assertFalse(InterpreterUtils.isObjectLike(JsBoolean.TRUE));
        assertFalse(InterpreterUtils.isObjectLike(new JsNumber(1)));
        assertFalse(InterpreterUtils.isObjectLike(new JsString("a")));
        assertFalse(InterpreterUtils.isObjectLike(new JsBigInt(java.math.BigInteger.ONE)));
        assertFalse(InterpreterUtils.isObjectLike(new JsSymbol("s")));
    }
    // @@iterator is a real own property of the intrinsic prototypes, shared with its named alias
    @Test
    public void test_iterator_symbol_is_a_real_prototype_property() {
        assertTrue(bool("Array.prototype.values === Array.prototype[Symbol.iterator]"));
        assertTrue(bool("Map.prototype.entries === Map.prototype[Symbol.iterator]"));
        assertTrue(bool("Set.prototype.values === Set.prototype[Symbol.iterator]"));
        assertTrue(bool("typeof String.prototype[Symbol.iterator] === 'function'"));
        assertTrue(bool("[1][Symbol.iterator] === Array.prototype[Symbol.iterator]"));
    }

    // a replaced Array.prototype[Symbol.iterator] is honoured by for-of, spread and destructuring
    @Test
    public void test_patched_array_iterator_is_used_by_every_iteration_form() {
        final var patch = """
                Array.prototype[Symbol.iterator] = function () {
                  let sent = false;
                  return { next() { return sent ? { done: true } : (sent = true, { value: 42, done: false }); } };
                };
                """;
        assertEquals(42, num(patch + "let r = 0; for (const x of [1, 2, 3]) r = x; r"));
        assertEquals(42, num(patch + "[...[1, 2, 3]][0]"));
        assertEquals(42, num(patch + "const [a] = [1, 2, 3]; a"));
    }

    // deleting Array.prototype[Symbol.iterator] makes an array non-iterable
    @Test
    public void test_deleted_array_iterator_makes_arrays_non_iterable() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("delete Array.prototype[Symbol.iterator]; const [a] = [1];"));
    }

    // the array iterator reads the live array, so a mutation between steps is observed
    @Test
    public void test_array_iterator_reads_the_array_lazily() {
        final var source = """
                const a = [1, 2, 3];
                const it = a[Symbol.iterator]();
                it.next();
                a[1] = 8;
                it.next().value
                """;
        assertEquals(8, num(source));
    }

    // a proxy over an array iterates through the target's intrinsic iterator
    @Test
    public void test_proxy_over_an_array_is_iterable() {
        assertEquals(6, num("let s = 0; for (const x of new Proxy([1, 2, 3], {})) s += x; s"));
    }
}
