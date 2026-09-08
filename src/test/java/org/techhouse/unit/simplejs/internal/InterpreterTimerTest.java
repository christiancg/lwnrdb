package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterTimerTest {
    private static JsArray arr(String source) {
        return (JsArray) Interpreter.run(source);
    }

    private static String str() {
        return ((JsString) Interpreter.run("typeof setTimeout(() => {}, 0)")).getValue();
    }

    private static double num(JsArray array, int index) {
        return ((JsNumber) array.get(index)).getValue();
    }

    // setTimeout runs its callback during the drain
    @Test
    public void test_set_timeout_runs_callback() {
        final var out = arr("let out = []; setTimeout(() => out.push(1), 0); out");
        assertEquals(1, num(out, 0));
    }

    // extra args passed to setTimeout are forwarded to the callback
    @Test
    public void test_set_timeout_passes_extra_args() {
        final var out = arr("let out = []; setTimeout((a, b) => out.push(a + b), 0, 2, 3); out");
        assertEquals(5, num(out, 0));
    }

    // setTimeout returns a numeric id
    @Test
    public void test_timeout_returns_numeric_id() {
        assertEquals("number", str());
    }

    // clearTimeout cancels a pending timer
    @Test
    public void test_clear_timeout_cancels() {
        final var out = arr("let out = []; let id = setTimeout(() => out.push(1), 0); clearTimeout(id); out");
        assertTrue(out.getElements().isEmpty());
    }

    // a promise reaction runs before a zero-delay timer
    @Test
    public void test_ordering_promise_before_timeout() {
        final var source = """
                let out = [];
                Promise.resolve().then(() => out.push('p'));
                setTimeout(() => out.push('t'), 0);
                out
                """;
        final var out = arr(source);
        assertEquals("p", ((JsString) out.get(0)).getValue());
        assertEquals("t", ((JsString) out.get(1)).getValue());
    }

    // timeouts fire in delay order regardless of scheduling order
    @Test
    public void test_timeouts_fire_in_delay_order() {
        final var source = """
                let out = [];
                setTimeout(() => out.push(2), 5);
                setTimeout(() => out.push(1), 1);
                out
                """;
        final var out = arr(source);
        assertEquals(1, num(out, 0));
        assertEquals(2, num(out, 1));
    }

    // setInterval reschedules and stops when cleared from the callback
    @Test
    public void test_set_interval_and_clear() {
        final var source = """
                let out = [];
                let n = 0;
                let id = setInterval(() => {
                    n = n + 1;
                    out.push(n);
                    if (n === 3) clearInterval(id);
                }, 0);
                out
                """;
        final var out = arr(source);
        assertEquals(3, out.getElements().size());
        assertEquals(3, num(out, 2));
    }

    // an async body may await inside a timer callback and still land its result
    @Test
    public void test_await_inside_timer_callback() {
        final var source = """
                let out = [];
                setTimeout(() => {
                    (async () => { out.push(await Promise.resolve(42)); })();
                }, 0);
                out
                """;
        assertEquals(42, num(arr(source), 0));
    }

    // a non-function callback raises a TypeError
    @Test
    public void test_non_function_callback_throws() {
        try {
            Interpreter.run("setTimeout(5, 0)");
            throw new AssertionError("expected a TypeError");
        } catch (TypeErrorException expected) {
            assertTrue(expected.getMessage().contains("setTimeout"));
        }
    }

    // an uncaught throw in a timer callback does not abort the script
    @Test
    public void test_callback_error_does_not_abort_script() {
        final var source = """
                let out = [];
                setTimeout(() => { throw new Error('boom'); }, 0);
                setTimeout(() => out.push(1), 0);
                out
                """;
        final var out = arr(source);
        assertEquals(1, num(out, 0));
    }

    // a timer whose delay exceeds the wall-clock budget times out the script
    @Test
    public void test_timer_respects_wall_clock_deadline() {
        final var engine = new SimpleJs();
        final ScriptResult result = engine.run("setTimeout(() => {}, 100000);",
                new SimpleHostBindings(new JsonObject(), null, null, new ResourceLimits(-1, 50, -1)));
        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName());
    }
}
