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

    // the AggregateError global constructor stores the message and an errors array
    @Test
    public void test_aggregate_error_constructor() {
        final var source = "let e = new AggregateError(['x', 'y'], 'oops');"
                + " e.name + '|' + e.message + '|' + e.errors.join(',')";
        assertEquals("AggregateError|oops|x,y", ((JsString) Interpreter.run(source)).getValue());
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

    // Promise.allSettled reports per-element status, preserving input order
    @Test
    public void test_all_settled_mixed() {
        final var source = """
                let out = [];
                Promise.allSettled([Promise.resolve(1), Promise.reject('e')]).then(a =>
                    out.push(a[0].status + ',' + a[0].value + ',' + a[1].status + ',' + a[1].reason));
                out
                """;
        assertEquals("fulfilled,1,rejected,e", string(arr(source)));
    }

    // Promise.allSettled with an empty array resolves immediately with an empty array
    @Test
    public void test_all_settled_empty() {
        assertEquals(0, num(arr("let out = []; Promise.allSettled([]).then(a => out.push(a.length)); out"), 0));
    }

    // Promise.any resolves with the first fulfilment even if an earlier element rejects
    @Test
    public void test_any_first_fulfilment() {
        final var source = """
                let out = [];
                Promise.any([Promise.reject('a'), Promise.resolve('b')]).then(v => out.push(v));
                out
                """;
        assertEquals("b", string(arr(source)));
    }

    // Promise.any rejects with an AggregateError holding the reasons in input order when all reject
    @Test
    public void test_any_all_reject() {
        final var source = """
                let out = [];
                Promise.any([Promise.reject('a'), Promise.reject('b')]).catch(e =>
                    out.push(e.name + ':' + e.errors.join(',')));
                out
                """;
        assertEquals("AggregateError:a,b", string(arr(source)));
    }

    // Promise.any with an empty array rejects immediately with an empty AggregateError
    @Test
    public void test_any_empty() {
        final var source = """
                let out = [];
                Promise.any([]).catch(e => out.push(e.name + ':' + e.errors.length));
                out
                """;
        assertEquals("AggregateError:0", string(arr(source)));
    }

    // the combinators accept any iterable, not just arrays (Set here)
    @Test
    public void test_all_accepts_set() {
        final var source = """
                let out = [];
                Promise.all(new Set([Promise.resolve(1), Promise.resolve(2)])).then(a => out.push(a.join(',')));
                out
                """;
        assertEquals("1,2", string(arr(source)));
    }

    // Promise.withResolvers exposes the promise and its resolve function; resolving settles it
    @Test
    public void test_with_resolvers_resolve() {
        final var source = """
                let out = [];
                let { promise, resolve } = Promise.withResolvers();
                promise.then(v => out.push(v));
                resolve(7);
                out
                """;
        assertEquals(7, num(arr(source), 0));
    }

    // Promise.withResolvers exposes a reject function that rejects the promise
    @Test
    public void test_with_resolvers_reject() {
        final var source = """
                let out = [];
                let { promise, reject } = Promise.withResolvers();
                promise.catch(e => out.push(e));
                reject('boom');
                out
                """;
        assertEquals("boom", string(arr(source)));
    }

    // Promise.try runs the callback and fulfils with its return value, passing extra args
    @Test
    public void test_try_fulfils() {
        final var source = """
                let out = [];
                Promise.try((a, b) => a + b, 2, 3).then(v => out.push(v));
                out
                """;
        assertEquals(5, num(arr(source), 0));
    }

    // Promise.try turns a synchronous throw into a rejection
    @Test
    public void test_try_rejects_on_throw() {
        final var source = """
                let out = [];
                Promise.try(() => { throw 'nope'; }).catch(e => out.push(e));
                out
                """;
        assertEquals("nope", string(arr(source)));
    }

    // Promise.try adopts a promise returned by the callback
    @Test
    public void test_try_adopts_promise() {
        final var source = """
                let out = [];
                Promise.try(() => Promise.resolve(11)).then(v => out.push(v));
                out
                """;
        assertEquals(11, num(arr(source), 0));
    }

    // Promise.all on a non-iterable settles rejected instead of throwing out of the native call
    @Test
    public void test_all_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.all({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

    // Promise.race on a non-iterable settles rejected instead of throwing out of the native call
    @Test
    public void test_race_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.race({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

    // Promise.allSettled on a non-iterable settles rejected instead of throwing out of the native call
    @Test
    public void test_all_settled_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.allSettled({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

    // Promise.any on a non-iterable settles rejected instead of throwing out of the native call
    @Test
    public void test_any_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.any({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

    // Promise.resolve assimilates a user thenable instead of fulfilling with the thenable itself
    @Test
    public void test_resolve_assimilates_thenable() {
        final var source = """
                let out = [];
                Promise.resolve({ then(res) { res(42); } }).then(v => out.push(v));
                out
                """;
        assertEquals(42, num(arr(source), 0));
    }

    // a thenable whose then throws synchronously rejects the derived promise
    @Test
    public void test_thenable_sync_throw_rejects() {
        final var source = """
                let out = [];
                Promise.resolve({ then() { throw 'boom'; } }).catch(e => out.push(e));
                out
                """;
        assertEquals("boom", string(arr(source)));
    }

    // a thenable that never invokes its resolve/reject callback leaves the promise pending forever
    @Test
    public void test_thenable_never_settling_stays_pending() {
        final var source = """
                let out = [];
                Promise.resolve({ then() {} }).then(v => out.push(v), e => out.push(e));
                out
                """;
        assertEquals(0, arr(source).length());
    }
}
