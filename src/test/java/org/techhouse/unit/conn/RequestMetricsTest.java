package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.RequestMetrics;
import org.techhouse.metrics.Histogram;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class RequestMetricsTest {
    private static final long ONE_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);

    private static List<String> countsOf(RequestMetrics metrics) {
        final var counts = new ArrayList<String>();
        metrics.forEachCount(
                (operation, status, code, count) -> counts.add(operation + "/" + status + "/" + code + "=" + count));
        return counts;
    }

    private static Map<String, Histogram> durationsOf(RequestMetrics metrics) {
        final var durations = new HashMap<String, Histogram>();
        metrics.forEachDuration(durations::put);
        return durations;
    }

    @Test
    public void test_a_fresh_registry_reports_nothing() {
        final var metrics = new RequestMetrics();

        assertTrue(countsOf(metrics).isEmpty());
        assertTrue(durationsOf(metrics).isEmpty());
    }

    @Test
    public void test_counts_by_operation_and_status() {
        final var metrics = new RequestMetrics();

        metrics.record(OperationType.SAVE, OperationStatus.OK, null, ONE_MILLISECOND);
        metrics.record(OperationType.SAVE, OperationStatus.OK, null, ONE_MILLISECOND);
        metrics.record(OperationType.FIND_BY_ID, OperationStatus.OK, null, ONE_MILLISECOND);

        assertEquals(List.of("FIND_BY_ID/OK/=1", "SAVE/OK/=2"), countsOf(metrics).stream().sorted().toList());
    }

    @Test
    public void test_an_error_code_gets_its_own_series_with_its_status() {
        final var metrics = new RequestMetrics();
        final var notFound = ErrorCode.ENTRY_NOT_FOUND;

        metrics.record(OperationType.FIND_BY_ID, notFound.getStatus(), notFound.getCode(), ONE_MILLISECOND);
        metrics.record(OperationType.DELETE, notFound.getStatus(), notFound.getCode(), ONE_MILLISECOND);
        metrics.record(OperationType.FIND_BY_ID, notFound.getStatus(), notFound.getCode(), ONE_MILLISECOND);

        assertEquals(List.of("DELETE/NOT_FOUND/404-2=1", "FIND_BY_ID/NOT_FOUND/404-2=2"),
                countsOf(metrics).stream().sorted().toList());
    }

    @Test
    public void test_an_unparsed_request_and_a_forwarded_answer_have_their_own_labels() {
        final var metrics = new RequestMetrics();
        final var invalid = ErrorCode.INVALID_COMMAND;

        metrics.record(null, invalid.getStatus(), invalid.getCode(), ONE_MILLISECOND);
        metrics.record(OperationType.SAVE, null, null, ONE_MILLISECOND);
        metrics.record(OperationType.DELETE, null, "503-2", ONE_MILLISECOND);

        assertEquals(List.of("DELETE/FORWARDED/503-2=1", "SAVE/FORWARDED/=1", "UNKNOWN/ERROR/400-43=1"),
                countsOf(metrics).stream().sorted().toList());
    }

    @Test
    public void test_durations_are_recorded_per_operation() {
        final var metrics = new RequestMetrics();

        metrics.record(OperationType.SAVE, OperationStatus.OK, null, ONE_MILLISECOND);
        metrics.record(OperationType.SAVE, OperationStatus.ERROR, "500-1", 2 * ONE_MILLISECOND);
        metrics.record(null, OperationStatus.ERROR, "400-43", ONE_MILLISECOND);

        final var durations = durationsOf(metrics);
        assertEquals(2, durations.size());
        assertEquals(2L, durations.get("SAVE").count());
        assertEquals(1L, durations.get(RequestMetrics.UNKNOWN_OPERATION).count());
        assertEquals(0.003, durations.get("SAVE").snapshot().sumSeconds(), 1e-9);
    }

    @Test
    public void test_refusals_are_counted_per_reason() {
        final var metrics = new RequestMetrics();

        metrics.refused(RequestMetrics.RefusalReason.MAX_CONNECTIONS);
        metrics.refused(RequestMetrics.RefusalReason.MAX_CONNECTIONS);
        metrics.refused(RequestMetrics.RefusalReason.UNAUTHENTICATED_PER_ADDRESS);

        assertEquals(2L, metrics.refusedCount(RequestMetrics.RefusalReason.MAX_CONNECTIONS));
        assertEquals(1L, metrics.refusedCount(RequestMetrics.RefusalReason.UNAUTHENTICATED_PER_ADDRESS));
        assertEquals("max_connections", RequestMetrics.RefusalReason.MAX_CONNECTIONS.label());
        assertEquals("unauthenticated_per_address", RequestMetrics.RefusalReason.UNAUTHENTICATED_PER_ADDRESS.label());
    }
}
