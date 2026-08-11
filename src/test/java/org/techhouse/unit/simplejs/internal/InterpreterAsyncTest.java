package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterAsyncTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static JsArray arr(String source) {
        return (JsArray) Interpreter.run(source);
    }

    private static double first(JsArray array) {
        return ((JsNumber) array.get(0)).getValue();
    }

    // an async function returns a promise with a then method
    @Test
    public void test_async_returns_promise() {
        assertEquals("object", str("typeof (async function() {})()"));
        assertEquals("function", str("typeof (async function() {})().then"));
    }

    // await Promise.allSettled resolves to the per-element status objects
    @Test
    public void test_await_all_settled() {
        final var source = """
                let out = [];
                async function f() {
                    let r = await Promise.allSettled([Promise.resolve(1), Promise.reject('e')]);
                    return r[0].value + ':' + r[1].reason;
                }
                f().then(v => out.push(v));
                out
                """;
        assertEquals("1:e", ((JsString) arr(source).get(0)).getValue());
    }

    // await Promise.any resolves to the first fulfilment
    @Test
    public void test_await_any() {
        final var source = """
                let out = [];
                async function f() { return await Promise.any([Promise.reject('a'), Promise.resolve(7)]); }
                f().then(v => out.push(v));
                out
                """;
        assertEquals(7, first(arr(source)));
    }

    // await resolves a promise value; the microtask queue drains before the run returns
    @Test
    public void test_await_resolves_value() {
        final var source = """
                let out = [];
                async function f() { return await Promise.resolve(41) + 1; }
                f().then(x => out.push(x));
                out
                """;
        assertEquals(42, first(arr(source)));
    }

    // a rejected await throws into the async body and is catchable
    @Test
    public void test_await_rejection_throws_into_try_catch() {
        final var source = """
                let out = [];
                async function f() {
                    try { await Promise.reject('bad'); } catch (e) { out.push('caught:' + e); }
                }
                f();
                out
                """;
        assertEquals("caught:bad", ((JsString) arr(source).get(0)).getValue());
    }

    // async arrow functions await and resolve
    @Test
    public void test_async_arrow() {
        final var source = """
                let out = [];
                let f = async (x) => await Promise.resolve(x * 2);
                f(21).then(v => out.push(v));
                out
                """;
        assertEquals(42, first(arr(source)));
    }

    // an async class method returns a promise
    @Test
    public void test_async_class_method() {
        final var source = """
                let out = [];
                class C { async m() { return await Promise.resolve(7); } }
                new C().m().then(v => out.push(v));
                out
                """;
        assertEquals(7, first(arr(source)));
    }

    // await outside an async function is a runtime syntax error
    @Test
    public void test_await_outside_async_is_syntax_error() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("function f() { return await Promise.resolve(1); } f()"));
    }

    // chained awaits accumulate results in order
    @Test
    public void test_chained_awaits() {
        final var source = """
                let out = [];
                async function f() {
                    let a = await Promise.resolve(1);
                    let b = await Promise.resolve(2);
                    return a + b;
                }
                f().then(v => out.push(v));
                out
                """;
        assertEquals(3, first(arr(source)));
    }

    // await at the top level of a script resolves a promise value
    @Test
    public void test_top_level_await_resolves_value() {
        final var source = """
                let out = [];
                out.push(await Promise.resolve(42));
                out
                """;
        assertEquals(42, first(arr(source)));
    }

    // top-level awaits run sequentially, accumulating in order
    @Test
    public void test_top_level_await_chained() {
        final var source = """
                let out = [];
                out.push(await Promise.resolve(1));
                out.push(await Promise.resolve(2));
                out
                """;
        final var array = arr(source);
        assertEquals(1, ((JsNumber) array.get(0)).getValue());
        assertEquals(2, ((JsNumber) array.get(1)).getValue());
    }

    // awaiting a non-promise at the top level yields the value itself
    @Test
    public void test_top_level_await_of_non_promise() {
        assertEquals(5, ((JsNumber) Interpreter.run("await 5")).getValue());
    }

    // a rejected top-level await propagates as a thrown error
    @Test
    public void test_top_level_await_rejection_propagates() {
        assertThrows(JsThrowException.class, () -> Interpreter.run("await Promise.reject('bad')"));
    }

    // await inside a plain (non-async) arrow called from an async context is still a syntax error
    @Test
    public void test_await_inside_plain_arrow_is_syntax_error() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("const f = () => await Promise.resolve(1); f()"));
    }

    // yield at the top level is a syntax error
    @Test
    public void test_top_level_yield_is_syntax_error() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("yield 1;"));
    }

    // await inside a plain (sync) generator is a syntax error, surfaced when the body runs
    @Test
    public void test_await_inside_sync_generator_is_syntax_error() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("function* g() { await Promise.resolve(1); } g().next()"));
    }

    // for await inside a plain (sync) generator is a syntax error, surfaced when the body runs
    @Test
    public void test_for_await_inside_sync_generator_is_syntax_error() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("function* g() { for await (const x of []) {} } g().next()"));
    }

    // yield inside a plain async function (not a generator) is a syntax error, surfaced as a rejection
    @Test
    public void test_yield_in_plain_async_function_rejects() {
        final var source = """
                let out = [];
                async function f() { yield 1; }
                f().catch(e => out.push(e.name));
                out
                """;
        assertEquals("SyntaxError", ((JsString) arr(source).get(0)).getValue());
    }

    // Promise.prototype methods live on a real prototype, so a script can patch them
    @Test
    public void test_promise_prototype_is_patchable() {
        assertEquals("patched", str("""
                Promise.prototype.then = function() { return 'patched'; };
                Promise.resolve(1).then(v => v)
                """));
        assertEquals("f", str("""
                Promise.prototype.finally = function() { return 'f'; };
                Promise.resolve(1).finally(() => {})
                """));
        assertEquals("x", str("""
                Promise.prototype.tap = function() { return 'x'; };
                Promise.resolve(1).tap()
                """));
    }

    // The moved then/catch/finally implementations still behave as before
    @Test
    public void test_promise_then_semantics_unchanged() {
        assertEquals(2, first(arr("""
                let out = [];
                Promise.resolve(1).then(v => v + 1).then(v => out.push(v));
                out
                """)));
        assertEquals("caught", ((JsString) arr("""
                let out = [];
                Promise.reject(new Error('x')).catch(() => 'caught').then(v => out.push(v));
                out
                """).get(0)).getValue());
        assertEquals("done", ((JsString) arr("""
                let out = [];
                Promise.resolve(1).finally(() => out.push('done'));
                out
                """).get(0)).getValue());
    }

}
