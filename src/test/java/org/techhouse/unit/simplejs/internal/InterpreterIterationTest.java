package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

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
}
