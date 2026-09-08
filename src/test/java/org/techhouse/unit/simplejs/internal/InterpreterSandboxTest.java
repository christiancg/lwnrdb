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

    // A single oversized allocation is aborted before it is attempted
    @Test
    public void test_memory_budget_single_allocation() {
        final var result = run("return \"x\".repeat(100000000);", new ResourceLimits(-1, -1, -1, 1_000_000L));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // Doubling a string reaches gigabytes in a few instructions, so tick() cannot see it
    @Test
    public void test_memory_budget_string_doubling() {
        final var result = run("let s = \"x\"; for (let i = 0; i < 40; i++) { s = s + s; } return s.length;",
                new ResourceLimits(-1, -1, -1, 1_000_000L));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // padStart is sized by its argument, like repeat
    @Test
    public void test_memory_budget_pad_start() {
        final var result = run("return \"x\".padStart(100000000).length;", new ResourceLimits(-1, -1, -1, 1_000_000L));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // Dense array growth is charged per materialised slot
    @Test
    public void test_memory_budget_dense_array_growth() {
        final var result = run("return new Array(1000000).fill(0).length;", new ResourceLimits(-1, -1, -1, 100_000L));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // A typed array is charged its exact byte length
    @Test
    public void test_memory_budget_typed_array() {
        final var result = run("return new Uint8Array(50000000).length;", new ResourceLimits(-1, -1, -1, 1_000_000L));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // join multiplies length by separator length, so it amplifies without allocating per instruction
    @Test
    public void test_memory_budget_join_amplification() {
        final var result = run("return new Array(100000).fill(1).join(\"xxxxxxxxxx\").length;",
                new ResourceLimits(-1, -1, -1, 500_000L));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // A pre-sized snapshot of a claimed length must not allocate the backing array first
    @Test
    public void test_memory_budget_array_like_pre_sizing() {
        final var result = run("return Array.from({length: 2000000000}).length;",
                new ResourceLimits(-1, -1, -1, 1_000_000L));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // A user try/catch cannot swallow a memory abort - it still ends the script
    @Test
    public void test_memory_abort_not_catchable() {
        final var result = run("try { return \"x\".repeat(100000000); } catch (e) { return \"caught\"; }",
                new ResourceLimits(-1, -1, -1, 1_000_000L));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // A finally block cannot keep running user code past a memory abort
    @Test
    public void test_memory_abort_skips_finalizer() {
        final var result = run("try { return \"x\".repeat(100000000); } finally { while (true) {} }",
                new ResourceLimits(-1, -1, -1, 1_000_000L));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // An unlimited budget leaves a large-but-bounded allocation alone
    @Test
    public void test_unlimited_memory_budget() {
        final var result = run("return \"x\".repeat(1000000).length;", new ResourceLimits(-1, -1, -1, -1L));
        assertFalse(result.isError());
        assertEquals(1000000, result.getValue().asJsonNumber().asInteger());
    }

    // Incremental string building is charged its delta, so an honest append loop is not penalised
    @Test
    public void test_small_churn_is_not_charged() {
        final var result = run("let s = \"\"; for (let i = 0; i < 20000; i++) { s = s + \"x\"; } return s.length;",
                new ResourceLimits(-1, -1, -1, 1_000_000L));
        assertFalse(result.isError());
        assertEquals(20000, result.getValue().asJsonNumber().asInteger());
    }

    // A length past the dense cap is stored sparsely, so it must not be charged as materialised slots
    @Test
    public void test_sparse_array_is_not_charged() {
        final var result = run("const a = new Array(4294967295); return a.length;",
                new ResourceLimits(-1, -1, -1, 100_000L));
        assertFalse(result.isError());
        assertEquals(4294967295d, result.getValue().asJsonNumber().getValue().doubleValue());
    }

    // The same exemption applies to a length assignment above the dense cap
    @Test
    public void test_length_assignment_above_dense_cap_is_not_charged() {
        final var result = run("const a = []; a.length = 4294967295; return a.length;",
                new ResourceLimits(-1, -1, -1, 100_000L));
        assertFalse(result.isError());
        assertEquals(4294967295d, result.getValue().asJsonNumber().getValue().doubleValue());
    }

    // A native loop bounded only by a claimed length is now visible to the instruction budget
    @Test
    public void test_unticked_native_loop_is_bounded() {
        final var result = run("return String.raw({raw: {length: 1000000000}});", new ResourceLimits(10000, -1, -1));
        assertTrue(result.isError());
        assertEquals("ScriptLimitError", result.getErrorName());
    }

    // A result length past the engine's string ceiling is a RangeError, not a saturated cast
    @Test
    public void test_repeat_rejects_a_result_over_the_max_string_length() {
        final var result = run("return \"xx\".repeat(3000000000);", ResourceLimits.unlimited());
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }
}
