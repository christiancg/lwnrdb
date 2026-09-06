package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;

/**
 * The outcome mapping of the CompiledScript overload. It repeats the source overload's cascade, so each arm
 * needs its own reachable case.
 */
public class SimpleJsCompiledRunTest {
    private final SimpleJs simpleJs = new SimpleJs();

    private static SimpleHostBindings host(ResourceLimits limits) {
        return new SimpleHostBindings(new JsonObject(), null, null, limits);
    }

    private static SimpleHostBindings unlimited() {
        return host(ResourceLimits.unlimited());
    }

    private static ResourceLimits limits(long instructions, long millis, int depth, long memory) {
        return new ResourceLimits(instructions, millis, depth, true, false, List.of(), -1, -1, false, false,
                ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, ResourceLimits.DEFAULT_MAX_LOG_LINES,
                ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS, memory);
    }

    @Test
    public void test_returns_the_value() {
        final var result = simpleJs.run(simpleJs.compile("return 1 + 1;", false), unlimited());
        assertFalse(result.isError());
        assertEquals(2d, result.getValue().asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_undefined_result_becomes_json_null() {
        final var result = simpleJs.run(simpleJs.compile("let x = 1;", false), unlimited());
        assertFalse(result.isError());
        assertTrue(result.getValue().isJsonNull());
    }

    @Test
    public void test_thrown_error_is_reported_with_its_name_and_message() {
        final var result = simpleJs.run(simpleJs.compile("throw new RangeError('out of range');", false), unlimited());
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
        assertEquals("out of range", result.getErrorMessage());
    }

    @Test
    public void test_thrown_non_object_is_reported_as_an_error() {
        final var result = simpleJs.run(simpleJs.compile("throw 'a bare string';", false), unlimited());
        assertTrue(result.isError());
        assertEquals("Error", result.getErrorName());
        assertEquals("a bare string", result.getErrorMessage());
    }

    @Test
    public void test_runtime_type_error_is_reported() {
        final var result = simpleJs.run(simpleJs.compile("const x = null; return x.missing;", false), unlimited());
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_reference_error_is_reported() {
        final var result = simpleJs.run(simpleJs.compile("return notDeclaredAnywhere;", false), unlimited());
        assertTrue(result.isError());
        assertEquals("ReferenceError", result.getErrorName());
    }

    @Test
    public void test_instruction_budget_is_reported_as_a_limit() {
        final var result = simpleJs.run(simpleJs.compile("let i = 0; while (true) { i++; }", false),
                host(limits(500, -1, -1, -1)));
        assertTrue(result.isError());
        assertEquals("ScriptLimitError", result.getErrorName());
    }

    @Test
    public void test_wall_clock_deadline_is_reported_as_a_timeout() {
        final var result = simpleJs.run(simpleJs.compile("while (true) { }", false), host(limits(-1, 50, -1, -1)));
        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName());
    }

    @Test
    public void test_memory_budget_is_reported_as_a_memory_error() {
        final var result = simpleJs.run(simpleJs.compile("return 'x'.repeat(10000000);", false),
                host(limits(-1, -1, -1, 1024)));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    @Test
    public void test_recursion_depth_is_reported_as_a_limit() {
        final var result = simpleJs.run(simpleJs.compile("function f() { return f(); } return f();", false),
                host(limits(-1, -1, 5, -1)));
        assertTrue(result.isError());
        assertEquals("ScriptLimitError", result.getErrorName());
    }

    @Test
    public void test_rejected_top_level_promise_becomes_the_error() {
        final var result = simpleJs.run(simpleJs.compile("return Promise.reject(new Error('nope'));", false),
                unlimited());
        assertTrue(result.isError());
        assertEquals("nope", result.getErrorMessage());
    }

    @Test
    public void test_named_exports_become_the_result() {
        final var result = simpleJs.run(simpleJs.compile("export const a = 1; export const b = 2;", false),
                unlimited());
        assertFalse(result.isError());
        assertEquals(1d, result.getValue().asJsonObject().get("a").asJsonNumber().getValue().doubleValue());
        assertEquals(2d, result.getValue().asJsonObject().get("b").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_export_default_becomes_the_result() {
        final var result = simpleJs.run(simpleJs.compile("export default 7;", false), unlimited());
        assertFalse(result.isError());
        assertEquals(7d, result.getValue().asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_console_output_is_captured_on_the_result() {
        final var result = simpleJs.run(simpleJs.compile("console.log('hello'); return 1;", false), unlimited());
        assertFalse(result.isError());
        assertEquals(List.of("hello"), result.getLogs());
        assertFalse(result.isLogsTruncated());
    }
}
