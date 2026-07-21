package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class InterpreterSandboxTest {
    private final SimpleJs engine = new SimpleJs();

    private ScriptResult run(String source, ResourceLimits limits) {
        return engine.run(source, new SimpleHostBindings(new JsonObject(), null, null, limits));
    }

    // An unbounded loop is aborted once the wall-clock deadline passes
    @Test
    public void test_wall_clock_timeout() {
        final var result = run("while (true) {}", new ResourceLimits(-1, 50, -1));
        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName());
    }

    // A loop that exceeds the instruction budget is aborted
    @Test
    public void test_instruction_budget() {
        final var result = run("let i = 0; while (i < 1000000) { i = i + 1; }", new ResourceLimits(1000, -1, -1));
        assertTrue(result.isError());
        assertEquals("ScriptLimitError", result.getErrorName());
    }

    // Unbounded recursion is aborted by the depth cap
    @Test
    public void test_depth_cap() {
        final var result = run("function f(n) { return f(n + 1); } return f(0);", new ResourceLimits(-1, -1, 100));
        assertTrue(result.isError());
        assertEquals("ScriptLimitError", result.getErrorName());
    }

    // A user try/catch cannot swallow a limit abort — it still ends the script
    @Test
    public void test_limit_not_catchable() {
        final var result = run("try { while (true) {} } catch (e) { }", new ResourceLimits(-1, 50, -1));
        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName());
    }

    // A finally block cannot keep running user code past a limit abort
    @Test
    public void test_limit_skips_finalizer() {
        final var result = run("try { while (true) {} } finally { while (true) {} }", new ResourceLimits(-1, 50, -1));
        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName());
    }

    // A bounded script within the limits runs to completion
    @Test
    public void test_within_limits() {
        final var result = run("let i = 0; while (i < 10) { i = i + 1; } return i;",
                new ResourceLimits(100000, 5000, 100));
        assertFalse(result.isError());
        assertEquals(10, result.getValue().asJsonNumber().asInteger());
    }
}
