package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class ScriptDeadlineGranularityTest {
    private final SimpleJs simpleJs = new SimpleJs();

    private static ResourceLimits withTimeout(long timeoutMs) {
        return new ResourceLimits(-1L, timeoutMs, -1, true, false, List.of(), -1, -1, false, false, 16, 1000, 1000, -1L,
                -1, -1, -1);
    }

    private static SimpleHostBindings host(long timeoutMs) {
        return new SimpleHostBindings(new JsonObject(), null, null, withTimeout(timeoutMs));
    }

    @Test
    public void test_an_infinite_loop_aborts_within_the_timeout() {
        final var start = System.nanoTime();
        final var result = simpleJs.run(simpleJs.compile("while (true) { }", false), host(100));
        final var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName());
        assertTrue(elapsedMs < 5000, "an infinite loop must abort promptly, took " + elapsedMs + "ms");
    }

    @Test
    public void test_an_infinite_recursion_of_calls_aborts_within_the_timeout() {
        final var start = System.nanoTime();
        final var result = simpleJs.run(
                simpleJs.compile("function spin(){ let i = 0; while (true) { i = i + 1; } } spin();", false),
                host(100));
        final var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName());
        assertTrue(elapsedMs < 5000, "a spinning call must abort promptly, took " + elapsedMs + "ms");
    }

    @Test
    public void test_a_tight_call_loop_aborts_within_the_timeout() {
        final var start = System.nanoTime();
        final var result = simpleJs.run(
                simpleJs.compile("function f(x){ return x + 1; } let s = 0; while (true) { s = f(s); }", false),
                host(100));
        final var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName());
        assertTrue(elapsedMs < 5000, "a tight call loop must abort promptly, took " + elapsedMs + "ms");
    }

    @Test
    public void test_the_instruction_budget_stays_exact() {
        final var result = simpleJs.run(simpleJs.compile("let i = 0; while (true) { i++; }", false),
                new SimpleHostBindings(new JsonObject(), null, null, new ResourceLimits(500L, -1L, -1, true, false,
                        List.of(), -1, -1, false, false, 16, 1000, 1000, -1L, -1, -1, -1)));

        assertTrue(result.isError());
        assertEquals("ScriptLimitError", result.getErrorName(), "the instruction budget must stay exact, not sampled");
    }

    @Test
    public void test_a_short_script_under_the_deadline_still_completes() {
        final var result = simpleJs.run(
                simpleJs.compile("let s = 0; for (let i = 0; i < 100; i++) s += i; return s;", false), host(30_000));

        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(4950d, result.getValue().asJsonNumber().getValue().doubleValue());
    }
}
