package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.host.ResourceLimits;

public class ResourceLimitsTest {
    // A constructed ResourceLimits exposes its three budgets
    @Test
    public void test_fields() {
        final var limits = new ResourceLimits(100, 200, 3);
        assertEquals(100, limits.instructionBudget());
        assertEquals(200, limits.wallClockMillis());
        assertEquals(3, limits.maxDepth());
    }

    // unlimited() disables every budget with -1
    @Test
    public void test_unlimited() {
        final var limits = ResourceLimits.unlimited();
        assertEquals(-1, limits.instructionBudget());
        assertEquals(-1, limits.wallClockMillis());
        assertEquals(-1, limits.maxDepth());
    }

    // Text import is a capability, not a budget: every shorter constructor leaves it off
    @Test
    public void test_default_text_import_disabled() {
        assertFalse(new ResourceLimits(100, 200, 3).textImportEnabled());
        assertFalse(new ResourceLimits(100, 200, 3, true).textImportEnabled());
        assertFalse(new ResourceLimits(100, 200, 3, true, true).textImportEnabled());
        assertFalse(new ResourceLimits(100, 200, 3, true, false, List.of(), -1, -1).textImportEnabled());
        assertFalse(new ResourceLimits(100, 200, 3, true, false, List.of(), -1, -1, true).textImportEnabled());
    }

    // unlimited() budgets do not imply new capabilities, matching fetchEnabled
    @Test
    public void test_unlimited_keeps_text_import_disabled() {
        assertFalse(ResourceLimits.unlimited().textImportEnabled());
        assertFalse(ResourceLimits.unlimited().fetchEnabled());
    }

    // The module depth cap defaults to the shared constant on every shorter constructor
    @Test
    public void test_default_max_module_depth() {
        assertEquals(ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, new ResourceLimits(100, 200, 3).maxModuleDepth());
        assertEquals(ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, ResourceLimits.unlimited().maxModuleDepth());
        assertEquals(16, ResourceLimits.DEFAULT_MAX_MODULE_DEPTH);
    }

    // The canonical constructor carries both new fields through
    @Test
    public void test_canonical_constructor_carries_new_fields() {
        final var limits = new ResourceLimits(1, 2, 3, true, false, List.of(), -1, -1, false, true, 7);
        assertTrue(limits.textImportEnabled());
        assertEquals(7, limits.maxModuleDepth());
    }

    // Every convenience constructor supplies the default log caps
    @Test
    public void test_defaults_supply_log_caps() {
        assertEquals(ResourceLimits.DEFAULT_MAX_LOG_LINES, new ResourceLimits(100, 200, 3).maxLogLines());
        assertEquals(ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS, new ResourceLimits(100, 200, 3).maxLogLineChars());
        assertEquals(ResourceLimits.DEFAULT_MAX_LOG_LINES, new ResourceLimits(100, 200, 3, true).maxLogLines());
        assertEquals(ResourceLimits.DEFAULT_MAX_LOG_LINES, new ResourceLimits(100, 200, 3, true, true).maxLogLines());
        assertEquals(ResourceLimits.DEFAULT_MAX_LOG_LINES,
                new ResourceLimits(100, 200, 3, true, false, List.of(), -1, -1).maxLogLines());
        assertEquals(ResourceLimits.DEFAULT_MAX_LOG_LINES,
                new ResourceLimits(100, 200, 3, true, false, List.of(), -1, -1, true).maxLogLines());
        assertEquals(ResourceLimits.DEFAULT_MAX_LOG_LINES,
                new ResourceLimits(100, 200, 3, true, false, List.of(), -1, -1, true, true, 4).maxLogLines());
    }

    // An unlimited compute budget still caps logs: an unbounded buffer is a heap risk regardless
    @Test
    public void test_unlimited_still_caps_logs() {
        final var limits = ResourceLimits.unlimited();
        assertEquals(ResourceLimits.DEFAULT_MAX_LOG_LINES, limits.maxLogLines());
        assertEquals(ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS, limits.maxLogLineChars());
    }

    // The full canonical constructor carries explicit log caps through
    @Test
    public void test_explicit_log_caps() {
        final var limits = new ResourceLimits(100, 200, 3, true, false, List.of(), -1, -1, false, false, 4, 7, 9);
        assertEquals(7, limits.maxLogLines());
        assertEquals(9, limits.maxLogLineChars());
    }

    // The canonical constructor carries the memory budget, and the short form sets only it
    @Test
    public void test_memory_budget_carried_by_canonical_constructor() {
        final var limits = new ResourceLimits(100, 200, 3, true, false, List.of(), -1, -1, false, false, 4, 7, 9, 4096);
        assertEquals(4096, limits.memoryBudget());
        assertEquals(2048, new ResourceLimits(100, 200, 3, 2048L).memoryBudget());
    }

    // Every pre-existing constructor leaves memory unlimited, which is what keeps the test262 worker
    // and every embedding on their previous behaviour
    @Test
    public void test_existing_overloads_leave_memory_unlimited() {
        assertEquals(-1, new ResourceLimits(100, 200, 3).memoryBudget());
        assertEquals(-1, new ResourceLimits(100, 200, 3, true).memoryBudget());
        assertEquals(-1, new ResourceLimits(100, 200, 3, true, true).memoryBudget());
        assertEquals(-1, new ResourceLimits(100, 200, 3, true, false, List.of(), -1, -1).memoryBudget());
        assertEquals(-1, new ResourceLimits(100, 200, 3, true, false, List.of(), -1, -1, true).memoryBudget());
        assertEquals(-1, new ResourceLimits(100, 200, 3, true, false, List.of(), -1, -1, true, true, 4).memoryBudget());
        assertEquals(-1,
                new ResourceLimits(100, 200, 3, true, false, List.of(), -1, -1, true, true, 4, 7, 9).memoryBudget());
        assertEquals(-1, ResourceLimits.unlimited().memoryBudget());
    }
}
