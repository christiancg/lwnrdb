package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
