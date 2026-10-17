package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class EventLoopChargingTest {
    private static final SimpleJs simpleJs = new SimpleJs();
    private static final long MEMORY_BUDGET = 8L * 1024 * 1024;

    private static ScriptResult run(String source) {
        return simpleJs.run(source, new SimpleHostBindings(new JsonObject(), null, null,
                new ResourceLimits(-1, 30_000L, 200, MEMORY_BUDGET)));
    }

    private static void assertMemoryError(String source) {
        final var result = run(source);
        assertTrue(result.isError(), source);
        assertEquals("ScriptMemoryError", result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void test_allocating_many_promises_hits_the_memory_budget() {
        assertMemoryError("for (let i = 0; i < 200000; i++) { Promise.resolve(i); } return 1;");
    }

    @Test
    public void test_scheduling_many_timers_hits_the_memory_budget() {
        assertMemoryError("for (let i = 0; i < 200000; i++) { setTimeout(() => {}, 1e9); } return 1;");
    }

    @Test
    public void test_starting_many_generators_hits_the_memory_budget() {
        assertMemoryError("function* g() { yield 1; } for (let i = 0; i < 20000; i++) { g(); } return 1;");
    }

    @Test
    public void test_a_queued_microtask_backlog_hits_the_memory_budget() {
        assertMemoryError("for (let i = 0; i < 200000; i++) { queueMicrotask(() => {}); } return 1;");
    }

    @Test
    public void test_drained_microtasks_and_fired_timers_release_their_charge() {
        final var result = run("let fired = 0; for (let round = 0; round < 100; round++) {"
                + " for (let i = 0; i < 2000; i++) { queueMicrotask(() => { fired++; }); }"
                + " for (let i = 0; i < 2000; i++) { setTimeout(() => { fired++; }, 0); }"
                + " await new Promise(resolve => setTimeout(resolve, 0)); } return fired;");
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(400000, result.getValue().asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_a_finalizer_that_spawns_a_coroutine_does_not_break_cleanup() {
        final var source = "function* inner() { yield 1; yield 2; }"
                + " function* outer() { try { yield 1; } finally { const g = inner(); g.next(); } }"
                + " const o = outer(); o.next(); return 'done';";
        final var first = run(source);
        assertFalse(first.isError(), first.getErrorName() + ": " + first.getErrorMessage());
        assertEquals("done", first.getValue().asJsonString().getValue());
        final var second = run("return 'next run';");
        assertFalse(second.isError(), second.getErrorMessage());
    }
}
