package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.builtins.NativeDepth;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptErrorNames;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class SimpleJsFatalErrorTest {
    private static final Duration PROMPTLY = Duration.ofSeconds(10);
    private static final String NEST = "let a = []; for (let i = 1; i < %d; i++) { a = [a]; } ";

    private static ScriptResult run(String source) {
        return assertTimeoutPreemptively(PROMPTLY, () -> new SimpleJs().run(source,
                new SimpleHostBindings(new JsonObject(), null, null, new ResourceLimits(-1, 30_000, -1, true))));
    }

    private static ScriptResult runWithConsole(String source, Supplier<Error> failure) {
        final Consumer<String> console = _ -> {
            throw failure.get();
        };
        return assertTimeoutPreemptively(PROMPTLY, () -> new SimpleJs().run(source,
                new SimpleHostBindings(new JsonObject(), null, console, new ResourceLimits(-1, 30_000, -1, true))));
    }

    private static void assertRangeError(ScriptResult result) {
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
        assertEquals(NativeDepth.EXCEEDED, result.getErrorMessage());
    }

    private static String nested(int depth) {
        return NEST.formatted(depth);
    }

    @Test
    public void test_console_consumer_throwing_stack_overflow_ends_run_with_memory_error() {
        final var result = runWithConsole("console.log(1); return 2;", StackOverflowError::new);
        assertTrue(result.isError());
        assertEquals(ScriptErrorNames.MEMORY, result.getErrorName());
    }

    @Test
    public void test_console_consumer_throwing_out_of_memory_ends_run_with_memory_error() {
        final var result = runWithConsole("async function f() { console.log(1); } await f(); return 2;",
                OutOfMemoryError::new);
        assertTrue(result.isError());
        assertEquals(ScriptErrorNames.MEMORY, result.getErrorName());
    }

    @Test
    public void test_json_parse_20000_deep_is_a_range_error() {
        assertRangeError(run("return JSON.parse('['.repeat(20000) + ']'.repeat(20000)).length;"));
    }

    @Test
    public void test_json_parse_deep_objects_is_a_range_error() {
        assertRangeError(run("return JSON.parse('{\"a\":'.repeat(20000) + '1' + '}'.repeat(20000));"));
    }

    @Test
    public void test_json_parse_reviver_on_deep_data_is_a_range_error() {
        assertRangeError(run(nested(20000)
                + "return JSON.parse('[0,1]', function (k, v) { if (k === '0') { this[1] = a; } return v; });"));
    }

    @Test
    public void test_string_of_deeply_nested_array_is_a_range_error() {
        assertRangeError(run(nested(20000) + "return String(a).length;"));
    }

    @Test
    public void test_flat_infinity_on_deep_array_is_a_range_error() {
        assertRangeError(run(nested(20000) + "return a.flat(Infinity).length;"));
    }

    @Test
    public void test_structured_clone_deep_is_a_range_error() {
        assertRangeError(run(nested(20000) + "return structuredClone(a).length;"));
    }

    @Test
    public void test_json_stringify_deep_is_a_range_error() {
        assertRangeError(run(nested(20000) + "return JSON.stringify(a).length;"));
    }

    @Test
    public void test_nesting_at_the_limit_succeeds_on_a_coroutine_thread() {
        final var limit = NativeDepth.MAX - 1;
        final var result = run(nested(limit) + """
                async function deepest() {
                    await null;
                    const copy = structuredClone(a);
                    const text = JSON.stringify(copy);
                    const parsed = JSON.parse(text);
                    let depth = 0;
                    for (let x = parsed; Array.isArray(x); x = x[0]) { depth++; }
                    return [depth, copy.flat(Infinity).length, text.length];
                }
                return await deepest();
                """);
        assertFalse(result.isError(), result.getErrorMessage());
        final var answer = result.getValue().asJsonArray();
        assertEquals(limit, answer.get(0).asJsonNumber().asInteger());
        assertEquals(0, answer.get(1).asJsonNumber().asInteger());
        assertEquals(2 * limit, answer.get(2).asJsonNumber().asInteger());
    }

    @Test
    public void test_limit_nesting_under_the_deepest_script_call_stack_fits() {
        final var source = nested(NativeDepth.MAX - 2) + """
                function down(n) { return n === 0 ? JSON.stringify(structuredClone(a)).length : down(n - 1); }
                return down(190);
                """;
        final var result = assertTimeoutPreemptively(PROMPTLY, () -> new SimpleJs().run(source,
                new SimpleHostBindings(new JsonObject(), null, null, new ResourceLimits(-1, 30_000, 200, true))));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(2 * (NativeDepth.MAX - 2), result.getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_one_level_past_the_limit_is_a_range_error() {
        assertRangeError(run(nested(NativeDepth.MAX) + "return structuredClone(a).length;"));
    }

    @Test
    public void test_range_error_is_catchable_by_script() {
        final var result = run(nested(20000) + """
                let caught = "";
                try { JSON.stringify(a); } catch (e) { caught = e.name + ":" + e.message; }
                return [caught, JSON.stringify([[1]])];
                """);
        assertFalse(result.isError(), result.getErrorMessage());
        final var answer = result.getValue().asJsonArray();
        assertEquals("RangeError:" + NativeDepth.EXCEEDED, answer.get(0).asJsonString().getValue());
        assertEquals("[[1]]", answer.get(1).asJsonString().getValue());
    }
}
