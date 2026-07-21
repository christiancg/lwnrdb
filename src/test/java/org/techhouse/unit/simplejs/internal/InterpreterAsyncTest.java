package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
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
}
