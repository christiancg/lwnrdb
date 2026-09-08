package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.CancellationToken;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class SimpleJsMetricsTest {
    private final SimpleJs engine = new SimpleJs();

    private record LimitedBindings(JsonObject args, DatabaseAccess database, Consumer<String> console,
            ResourceLimits limits, CancellationToken cancellation) implements HostBindings {
    }

    private static HostBindings withLimits(ResourceLimits limits) {
        return new LimitedBindings(new JsonObject(), null, null, limits, null);
    }

    private static HostBindings cancelledAfter(ResourceLimits limits) {
        return new LimitedBindings(new JsonObject(), null, null, limits, new CancellationToken() {
            private int seen;

            @Override
            public boolean isCancelled() {
                return ++seen > 20;
            }
        });
    }

    private ScriptResult run(String source) {
        return engine.run(source, SimpleHostBindings.empty());
    }

    @Test
    public void test_a_successful_run_reports_instructions() {
        final var metrics = run("let total = 0; for (let i = 0; i < 50; i++) { total += i; } return total;")
                .getMetrics();
        assertTrue(metrics.instructions() > 0, "expected a positive instruction count");
    }

    @Test
    public void test_a_bigger_loop_costs_more_instructions() {
        final var small = run("let t = 0; for (let i = 0; i < 10; i++) { t += i; } return t;").getMetrics();
        final var big = run("let t = 0; for (let i = 0; i < 200; i++) { t += i; } return t;").getMetrics();
        assertTrue(big.instructions() > small.instructions(),
                "expected " + big.instructions() + " > " + small.instructions());
    }

    // An unlimited budget still reports a figure: the count is not derived from what is left.
    @Test
    public void test_unlimited_budget_still_counts_instructions() {
        final var result = run("let t = 0; for (let i = 0; i < 20; i++) { t += i; } return t;");
        assertEquals(-1L, result.getMetrics().instructionBudget());
        assertTrue(result.getMetrics().instructions() > 0);
    }

    @Test
    public void test_a_thrown_error_still_reports_metrics() {
        final var result = run("for (let i = 0; i < 10; i++) {} throw new Error('boom');");
        assertTrue(result.isError());
        assertTrue(result.getMetrics().instructions() > 0);
    }

    // The holder is filled by the interpreter's finally, so an abort reports what it burned.
    @Test
    public void test_an_exhausted_instruction_budget_still_reports_metrics() {
        final var result = engine.run("while (true) {}", withLimits(new ResourceLimits(500, -1, 100)));
        assertTrue(result.isError());
        assertEquals("ScriptLimitError", result.getErrorName());
        assertEquals(500L, result.getMetrics().instructionBudget());
        assertTrue(result.getMetrics().instructions() > 0);
    }

    @Test
    public void test_a_timed_out_run_still_reports_metrics() {
        final var result = engine.run("while (true) {}", withLimits(new ResourceLimits(-1, 50, 100)));
        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName());
        assertTrue(result.getMetrics().instructions() > 0);
    }

    @Test
    public void test_a_cancelled_run_still_reports_metrics() {
        final var result = engine.run("while (true) {}", cancelledAfter(new ResourceLimits(-1, -1, 100)));
        assertTrue(result.isError());
        assertEquals("ScriptCancelledError", result.getErrorName());
        assertTrue(result.getMetrics().instructions() > 0);
    }

    @Test
    public void test_memory_budget_is_reported_and_peaks() {
        final var result = engine.run("const s = 'x'.repeat(4096); return s.length;",
                withLimits(new ResourceLimits(-1, -1, 100, 1_000_000L)));
        assertEquals(1_000_000L, result.getMetrics().memoryBudget());
        assertTrue(result.getMetrics().peakMemoryBytes() > 0);
    }

    // A parse failure never reached the interpreter, so it reports the empty metrics rather than zeroes
    // that look like a run which did nothing.
    @Test
    public void test_a_parse_failure_reports_empty_metrics() {
        final var result = run("function (");
        assertTrue(result.isError());
        assertEquals(0L, result.getMetrics().instructions());
        assertEquals(-1L, result.getMetrics().instructionBudget());
    }
}
