package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.host.ScriptRunMetrics;

public class ScriptRunMetricsTest {
    @Test
    public void test_empty_reports_zeroes_and_absent_budgets() {
        final var metrics = ScriptRunMetrics.EMPTY;
        assertEquals(0L, metrics.instructions());
        assertEquals(-1L, metrics.instructionBudget());
        assertEquals(0L, metrics.peakMemoryBytes());
        assertEquals(-1L, metrics.memoryBudget());
        assertEquals(0L, metrics.dbOperations());
        assertEquals(0L, metrics.durationMs());
    }

    @Test
    public void test_to_json_carries_every_field() {
        final var json = new ScriptRunMetrics(11L, 100L, 22L, 200L, 3L, 44L).toJson();
        assertEquals(11L, json.get("instructions").asJsonNumber().getValue().longValue());
        assertEquals(100L, json.get("instructionBudget").asJsonNumber().getValue().longValue());
        assertEquals(22L, json.get("peakMemoryBytes").asJsonNumber().getValue().longValue());
        assertEquals(200L, json.get("memoryBudget").asJsonNumber().getValue().longValue());
        assertEquals(3L, json.get("dbOperations").asJsonNumber().getValue().longValue());
        assertEquals(44L, json.get("durationMs").asJsonNumber().getValue().longValue());
    }

    @Test
    public void test_with_host_counters_replaces_only_the_host_figures() {
        final var metrics = new ScriptRunMetrics(11L, 100L, 22L, 200L, 0L, 0L).withHostCounters(7L, 500L);
        assertEquals(11L, metrics.instructions());
        assertEquals(100L, metrics.instructionBudget());
        assertEquals(22L, metrics.peakMemoryBytes());
        assertEquals(200L, metrics.memoryBudget());
        assertEquals(7L, metrics.dbOperations());
        assertEquals(500L, metrics.durationMs());
    }
}
