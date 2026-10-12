package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.CancellationToken;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptErrorNames;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class NativeLoopBudgetTest {
    private static final Duration PROMPTLY = Duration.ofSeconds(10);
    private static final String SPARSE = "const a = new Array(2 ** 32 - 1); ";

    private record CancellableHost(ResourceLimits limits, CancellationToken cancellation) implements HostBindings {
        @Override
        public JsonObject args() {
            return new JsonObject();
        }

        @Override
        public DatabaseAccess database() {
            return null;
        }

        @Override
        public java.util.function.Consumer<String> console() {
            return null;
        }
    }

    private static ScriptResult run(String source, ResourceLimits limits) {
        return assertTimeoutPreemptively(PROMPTLY,
                () -> new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(), null, null, limits)));
    }

    private static ScriptResult runBudgeted(String source) {
        return run(source, new ResourceLimits(200_000, 60_000, -1, true));
    }

    private static void assertBudgetExhausted(ScriptResult result) {
        assertTrue(result.isError());
        assertEquals(ScriptErrorNames.LIMIT, result.getErrorName());
    }

    @Test
    public void test_indexof_on_4_billion_sparse_array_hits_instruction_budget() {
        assertBudgetExhausted(runBudgeted(SPARSE + "return a.indexOf(1);"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.lastIndexOf(1)", "a.includes(1)", "a.slice(0, 2 ** 32 - 2).length", "a.splice(0).length",
            "a.copyWithin(0, 1)", "a.some(x => x)", "a.every(x => false)", "a.reverse()", "a.join('').length",
            "a.findLastIndex(x => false)", "a.forEach(x => x)"})
    public void test_sparse_native_scans_hit_instruction_budget(String scan) {
        assertBudgetExhausted(runBudgeted(SPARSE + "return " + scan + ";"));
    }

    @Test
    public void test_sparse_array_like_scan_hits_instruction_budget() {
        assertBudgetExhausted(runBudgeted("return Array.prototype.indexOf.call({ length: 2 ** 53 - 1 }, 1);"));
    }

    @Test
    public void test_sparse_scan_is_cancelled_by_token() {
        final var cancelled = new AtomicBoolean();
        final CancellationToken token = cancelled::get;
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            cancelled.set(true);
        });
        final var host = new CancellableHost(new ResourceLimits(-1, -1, -1, true), token);
        final var result = assertTimeoutPreemptively(PROMPTLY,
                () -> new SimpleJs().run(SPARSE + "return a.indexOf(1);", host));
        assertTrue(result.isError());
        assertEquals(ScriptErrorNames.CANCELLED, result.getErrorName());
    }

    @Test
    public void test_sparse_scan_hits_deadline() {
        final var result = run(SPARSE + "return a.indexOf(1);", new ResourceLimits(-1, 200, -1, true));
        assertTrue(result.isError());
        assertEquals(ScriptErrorNames.TIMEOUT, result.getErrorName());
    }

    @Test
    public void test_dense_small_array_scan_unaffected() {
        final var source = "const a = Array.from({ length: 5000 }, (_, i) => i); let found = 0;"
                + " for (let i = 0; i < 100; i++) { found += a.indexOf(4999); } return found;";
        final var result = run(source, new ResourceLimits(2_000_000, 60_000, -1, true));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(499_900, result.getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_short_scans_are_not_charged_a_pacing_block() {
        final var source = "const a = [1, 2, 3]; let n = 0;"
                + " for (let i = 0; i < 2000; i++) { n += a.indexOf(3) + (a.includes(2) ? 1 : 0); } return n;";
        final var result = run(source, new ResourceLimits(200_000, 60_000, -1, true));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(6000, result.getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_unlimited_budget_run_not_throttled() {
        final var source = "const a = new Array(3000); a[2999] = 7; return a.indexOf(7);";
        final var result = run(source, ResourceLimits.unlimited());
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(2999, result.getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_length_exactly_a_multiple_of_the_pacing_block() {
        final var source = "const a = new Array(2048).fill(0); a[2047] = 1;"
                + " return [a.indexOf(1), a.lastIndexOf(0), [].indexOf(1)];";
        final var result = run(source, new ResourceLimits(100_000, 60_000, -1, true));
        assertFalse(result.isError(), result.getErrorMessage());
        final var answer = result.getValue().asJsonArray();
        assertEquals(2047, answer.get(0).asJsonNumber().asInteger());
        assertEquals(2046, answer.get(1).asJsonNumber().asInteger());
        assertEquals(-1, answer.get(2).asJsonNumber().asInteger());
    }

    @Test
    public void test_typed_array_scans_are_paced() {
        final var source = "const t = new Uint8Array(4_000_000); return [t.indexOf(1), t.includes(1), t.join('').length];";
        assertBudgetExhausted(run(source, new ResourceLimits(1_000_000, 60_000, -1, 64L << 20)));
    }
}
