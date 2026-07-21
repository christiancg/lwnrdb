package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterIterationTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
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
}
