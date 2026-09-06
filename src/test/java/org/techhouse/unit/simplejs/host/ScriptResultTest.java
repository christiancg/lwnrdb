package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.host.ScriptResult;

public class ScriptResultTest {
    // A value result carries the EJson value and is not an error
    @Test
    public void test_value_result() {
        final var result = ScriptResult.value(new JsonString("hello"));
        assertFalse(result.isError());
        assertEquals("hello", result.getValue().asJsonString().getValue());
        assertNull(result.getErrorName());
        assertNull(result.getErrorMessage());
    }

    @Test
    public void test_error_result_carries_a_stack() {
        final var result = ScriptResult.error("TypeError", "boom", java.util.List.of("f (main:1:1)"),
                java.util.List.of(), false);
        assertTrue(result.isError());
        assertEquals(java.util.List.of("f (main:1:1)"), result.getErrorStack());
    }

    @Test
    public void test_value_result_has_no_stack() {
        assertNull(ScriptResult.value(new JsonString("ok")).getErrorStack());
    }

    @Test
    public void test_error_result_without_a_stack_reports_null() {
        assertNull(ScriptResult.error("TypeError", "boom").getErrorStack());
    }

    // An error result carries name + message and no value
    @Test
    public void test_error_result() {
        final var result = ScriptResult.error("TypeError", "boom");
        assertTrue(result.isError());
        assertNull(result.getValue());
        assertEquals("TypeError", result.getErrorName());
        assertEquals("boom", result.getErrorMessage());
    }

    // The legacy two-argument factories carry empty, untruncated logs
    @Test
    public void test_legacy_factories_yield_empty_logs() {
        assertTrue(ScriptResult.value(new JsonString("x")).getLogs().isEmpty());
        assertFalse(ScriptResult.value(new JsonString("x")).isLogsTruncated());
        assertTrue(ScriptResult.error("TypeError", "boom").getLogs().isEmpty());
        assertFalse(ScriptResult.error("TypeError", "boom").isLogsTruncated());
    }

    // A value result carries the captured logs and the truncation flag
    @Test
    public void test_value_result_carries_logs() {
        final var result = ScriptResult.value(new JsonString("x"), List.of("a", "b"), true);
        assertEquals(List.of("a", "b"), result.getLogs());
        assertTrue(result.isLogsTruncated());
    }

    // An error result carries the captured logs too
    @Test
    public void test_error_result_carries_logs() {
        final var result = ScriptResult.error("Error", "boom", List.of("a"), false);
        assertEquals(List.of("a"), result.getLogs());
        assertFalse(result.isLogsTruncated());
    }

    // The log list is defensively copied and exposed immutably. Modifying the immutable list is the
    // assertion itself, not a mistake, so the data-flow warning about it is suppressed here.
    @SuppressWarnings("DataFlowIssue")
    @Test
    public void test_logs_are_defensively_copied() {
        final var source = new ArrayList<String>();
        source.add("a");
        final var result = ScriptResult.value(new JsonString("x"), source, false);
        source.add("b");
        assertEquals(List.of("a"), result.getLogs());
        assertThrows(UnsupportedOperationException.class, () -> result.getLogs().add("c"));
    }

    // A null log list is normalized to an empty one
    @Test
    public void test_null_logs_become_empty() {
        assertTrue(ScriptResult.value(new JsonString("x"), null, false).getLogs().isEmpty());
    }

    @Test
    public void test_metrics_default_to_empty_and_can_be_replaced() {
        final var result = ScriptResult.value(new JsonString("ok"));
        assertEquals(org.techhouse.simplejs.host.ScriptRunMetrics.EMPTY, result.getMetrics());

        final var measured = result
                .withMetrics(new org.techhouse.simplejs.host.ScriptRunMetrics(5L, 10L, 1L, 2L, 3L, 4L));
        assertEquals(5L, measured.getMetrics().instructions());
        assertEquals("ok", measured.getValue().asJsonString().getValue());

        assertEquals(org.techhouse.simplejs.host.ScriptRunMetrics.EMPTY, measured.withMetrics(null).getMetrics());
    }
}
