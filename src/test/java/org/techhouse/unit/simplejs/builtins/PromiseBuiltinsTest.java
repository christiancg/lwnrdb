package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class PromiseBuiltinsTest {
    private static JsArray arr(String source) {
        return (JsArray) Interpreter.run(source);
    }

    private static double num(JsArray array, int index) {
        return ((JsNumber) array.get(index)).getValue();
    }

    private static String string(JsArray array) {
        return ((JsString) array.get(0)).getValue();
    }

    // Promise.resolve settles fulfilled
    @Test
    public void test_resolve() {
        assertEquals(5, num(arr("let out = []; Promise.resolve(5).then(v => out.push(v)); out"), 0));
    }

    // Promise.reject is caught by catch
    @Test
    public void test_reject_and_catch() {
        assertEquals("c:e", string(arr("let out = []; Promise.reject('e').catch(v => out.push('c:' + v)); out")));
    }

    // then handlers chain, each transforming the resolved value
    @Test
    public void test_then_chaining() {
        assertEquals(2, num(arr("let out = []; Promise.resolve(1).then(v => v + 1).then(v => out.push(v)); out"), 0));
    }

    // finally runs before the following then and passes the value through
    @Test
    public void test_finally() {
        final var out = arr(
                "let out = []; Promise.resolve(1).finally(() => out.push('f')).then(v => out.push(v)); out");
        assertEquals("f", string(out));
        assertEquals(1, num(out, 1));
    }

    // a throwing then handler rejects the derived promise
    @Test
    public void test_then_handler_throw_rejects() {
        final var source = """
                let out = [];
                Promise.resolve(1).then(() => { throw 'oops'; }).catch(e => out.push('c:' + e));
                out
                """;
        assertEquals("c:oops", string(arr(source)));
    }

    // new Promise runs the executor and resolves via its resolve callback
    @Test
    public void test_new_promise_resolve() {
        assertEquals(9, num(arr("let out = []; new Promise((res, rej) => res(9)).then(v => out.push(v)); out"), 0));
    }

    // new Promise rejects via its reject callback
    @Test
    public void test_new_promise_reject() {
        assertEquals("x",
                string(arr("let out = []; new Promise((res, rej) => rej('x')).catch(v => out.push(v)); out")));
    }

    // a throwing executor rejects the promise
    @Test
    public void test_new_promise_executor_throw_rejects() {
        final var source = """
                let out = [];
                new Promise((res, rej) => { throw 'bad'; }).catch(e => out.push(e));
                out
                """;
        assertEquals("bad", string(arr(source)));
    }

    // a non-function resolver throws a TypeError
    @Test
    public void test_new_promise_bad_resolver_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Promise(5)"));
    }

    // Promise.all resolves with all values, coercing non-promises
    @Test
    public void test_all_fulfils() {
        final var source = """
                let out = [];
                Promise.all([Promise.resolve(1), Promise.resolve(2), 3]).then(a => out.push(a.join(',')));
                out
                """;
        assertEquals("1,2,3", string(arr(source)));
    }

    // Promise.all rejects on the first rejection
    @Test
    public void test_all_rejects() {
        final var source = """
                let out = [];
                Promise.all([Promise.resolve(1), Promise.reject('bad')]).catch(e => out.push(e));
                out
                """;
        assertEquals("bad", string(arr(source)));
    }

    // Promise.all with an empty array resolves immediately with an empty array
    @Test
    public void test_all_empty() {
        assertEquals(0, num(arr("let out = []; Promise.all([]).then(a => out.push(a.length)); out"), 0));
    }

    // Promise.race settles with the first settled promise
    @Test
    public void test_race() {
        final var source = """
                let out = [];
                Promise.race([Promise.resolve('first'), Promise.resolve('second')]).then(v => out.push(v));
                out
                """;
        assertEquals("first", string(arr(source)));
    }
}
