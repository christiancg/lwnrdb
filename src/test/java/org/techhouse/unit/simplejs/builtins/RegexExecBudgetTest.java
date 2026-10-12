package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.CancellationToken;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;

public class RegexExecBudgetTest {
    private static final Duration GENEROUS = Duration.ofSeconds(20);
    private static final String CATASTROPHIC = "return /(x+x+)+y/.test('x'.repeat(800) + '!');";

    private final SimpleJs engine = new SimpleJs();

    private record LimitedBindings(JsonObject args, DatabaseAccess database, Consumer<String> console,
            ResourceLimits limits, CancellationToken cancellation) implements HostBindings {
    }

    private ScriptResult run(String source, ResourceLimits limits, CancellationToken cancellation) {
        return engine.run(source, new LimitedBindings(new JsonObject(), null, null, limits, cancellation));
    }

    private ScriptResult runCatastrophic(ResourceLimits limits, CancellationToken cancellation) {
        return assertTimeoutPreemptively(GENEROUS, () -> run(CATASTROPHIC, limits, cancellation));
    }

    @Test
    public void test_instruction_budget_reaches_inside_a_catastrophic_regex() {
        final var result = runCatastrophic(new ResourceLimits(5_000, -1, 100), null);
        assertTrue(result.isError());
        assertEquals("ScriptLimitError", result.getErrorName());
    }

    @Test
    public void test_deadline_reaches_inside_a_catastrophic_regex() {
        final var result = runCatastrophic(new ResourceLimits(-1, 200, 100), null);
        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName());
    }

    @Test
    public void test_cancellation_reaches_inside_a_catastrophic_regex() {
        final var checks = new AtomicInteger();
        final CancellationToken token = () -> checks.incrementAndGet() > 1_000;
        final var result = runCatastrophic(new ResourceLimits(-1, -1, 100), token);
        assertTrue(result.isError());
        assertEquals("ScriptCancelledError", result.getErrorName());
    }

    @Test
    public void test_a_benign_regex_is_unaffected() {
        final var result = run("const m = /a(b+)c/.exec('xabbbc'); return m[1] + ':' + m.index;",
                new ResourceLimits(5_000, -1, 100), null);
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals("bbb:1", result.getValue().asJsonString().getValue());
    }

    @Test
    public void test_regex_steps_cost_about_one_instruction_per_64() {
        final var prefix = "const r = /(x+x+)+y/; const s = 'x'.repeat(20) + '!'; return r.test(";
        final var heavy = run(prefix + "s);", new ResourceLimits(-1, -1, 100), null);
        final var light = run(prefix + "'y');", new ResourceLimits(-1, -1, 100), null);
        final var extra = heavy.getMetrics().instructions() - light.getMetrics().instructions();
        assertTrue(extra >= 140 && extra <= 160, "expected about 9577 / 64 extra instructions, was " + extra);
    }
}
