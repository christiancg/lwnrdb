package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterForLoopTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_for_of_array() {
        assertEquals(6, num("let s = 0; for (const x of [1, 2, 3]) s += x; s"));
    }

    @Test
    public void test_for_of_string() {
        assertEquals("abc", str("let s = ''; for (const c of 'abc') s += c; s"));
    }

    @Test
    public void test_for_of_with_destructuring() {
        assertEquals(10, num("let s = 0; for (const [a, b] of [[1, 2], [3, 4]]) s += a + b; s"));
    }

    @Test
    public void test_for_of_let_closure_per_iteration() {
        final var source = """
                let fns = [];
                for (const x of [1, 2, 3]) fns.push(() => x);
                fns[0]() + fns[1]() + fns[2]()
                """;
        assertEquals(6, num(source));
    }

    @Test
    public void test_for_of_break_continue() {
        assertEquals(3, num("let s = 0; for (const x of [1, 2, 3, 4]) { if (x === 3) break; s += x; } s"));
        assertEquals(4, num("let s = 0; for (const x of [1, 2, 3, 4]) { if (x % 2 === 0) continue; s += x; } s"));
    }

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

    @Test
    public void test_for_of_non_iterable_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("for (const x of 5) {}"));
    }

    @Test
    public void test_for_in_object_keys() {
        assertEquals("ab", str("let s = ''; for (const k in {a: 1, b: 2}) s += k; s"));
    }

    @Test
    public void test_for_in_array_indices() {
        assertEquals("012", str("let s = ''; for (const i in [9, 8, 7]) s += i; s"));
    }

    @Test
    public void test_for_in_string_indices() {
        assertEquals("01", str("let s = ''; for (const i in 'ab') s += i; s"));
    }

    @Test
    public void test_for_in_over_null_undefined_no_iteration() {
        assertEquals(0, num("let s = 0; for (const k in null) s++; for (const k in undefined) s++; s"));
    }

    @Test
    public void test_for_in_assignment_target() {
        assertEquals("y", str("let k; let last = ''; for (k in {x: 1, y: 2}) last = k; last"));
    }

    @Test
    public void test_for_in_skips_key_deleted_during_iteration() {
        final var source = """
                let obj = {aa: 1, ba: 2, ca: 3};
                let out = '';
                for (const key in obj) {
                    if (key === 'aa') delete obj.ba;
                    out += key + obj[key];
                }
                out
                """;
        assertEquals("aa1ca3", str(source));
    }

    @Test
    public void test_for_const_binding_update_throws_type_error() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("for (const i = 0; i < 1; i++) {}"));
    }

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

    @Test
    public void test_for_of_plain_object_not_iterable() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("for (const x of {a: 1}) {}"));
    }

    @Test
    public void test_iterator_to_array_reduce_for_each() {
        assertEquals("2,4,6", str("function* g(){yield 1;yield 2;yield 3;} g().map(x=>x*2).toArray().join(',')"));
        assertEquals(6, num("function* g(){yield 1;yield 2;yield 3;} g().reduce((a, b) => a + b, 0)"));
        assertEquals(6, num("function* g(){yield 1;yield 2;yield 3;} let s=0; g().forEach(x=>s+=x); s"));
    }

    @Test
    public void test_for_of_string_astral() {
        assertEquals(2, num("let n = 0; for (const c of 'a\\u{1F600}') { n++; } n"));
        assertEquals("😀", str("let last = ''; for (const c of 'a\\u{1F600}') { last = c; } last"));
    }

    @Test
    public void test_for_of_string_break() {
        assertEquals(1, num("let n = 0; for (const c of 'a\\u{1F600}b') { n++; break; } n"));
    }

    @Test
    public void test_for_of_over_object_with_generator_symbol_iterator() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 7; } }
                let s = 0; for (const x of new C()) s += x; s
                """;
        assertEquals(7, num(source));
    }

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
}
