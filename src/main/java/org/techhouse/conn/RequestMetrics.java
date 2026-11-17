package org.techhouse.conn;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.techhouse.metrics.Histogram;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class RequestMetrics {
    public static final String UNKNOWN_OPERATION = "UNKNOWN";
    public static final String FORWARDED_STATUS = "FORWARDED";
    private static final OperationType[] OPERATIONS = OperationType.values();
    private static final OperationStatus[] STATUSES = OperationStatus.values();
    private static final int UNKNOWN_SLOT = OPERATIONS.length;
    private static final int FORWARDED_SLOT = STATUSES.length;
    private final LongAdder[][] byStatus = new LongAdder[OPERATIONS.length + 1][STATUSES.length + 1];
    private final ConcurrentHashMap<String, CodeCounts> byErrorCode = new ConcurrentHashMap<>();
    private final Histogram[] durations = new Histogram[OPERATIONS.length + 1];
    private final LongAdder[] refused = new LongAdder[RefusalReason.values().length];

    public enum RefusalReason {
        MAX_CONNECTIONS("max_connections"), UNAUTHENTICATED_PER_ADDRESS("unauthenticated_per_address");

        private final String label;

        RefusalReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @FunctionalInterface
    public interface CountVisitor {
        void visit(String operation, String status, String errorCode, long count);
    }

    @FunctionalInterface
    public interface DurationVisitor {
        void visit(String operation, Histogram durations);
    }

    private record CodeCounts(String status, LongAdder[] perOperation) {
    }

    public RequestMetrics() {
        for (var op = 0; op < byStatus.length; op++) {
            for (var status = 0; status < byStatus[op].length; status++) {
                byStatus[op][status] = new LongAdder();
            }
            durations[op] = new Histogram();
        }
        for (var i = 0; i < refused.length; i++) {
            refused[i] = new LongAdder();
        }
    }

    void record(OperationType type, ResponseFrames.Reserved response, long nanos) {
        record(type, response.status(), response.errorCode(), nanos);
    }

    public void record(OperationType type, OperationStatus status, String errorCode, long nanos) {
        final var slot = type == null ? UNKNOWN_SLOT : type.ordinal();
        if (errorCode == null) {
            byStatus[slot][status == null ? FORWARDED_SLOT : status.ordinal()].increment();
        } else {
            countsOf(errorCode, status).perOperation()[slot].increment();
        }
        durations[slot].observeNanos(nanos);
    }

    private CodeCounts countsOf(String errorCode, OperationStatus status) {
        final var known = byErrorCode.get(errorCode);
        if (known != null) {
            return known;
        }
        return byErrorCode.computeIfAbsent(errorCode,
                _ -> newCodeCounts(status == null ? FORWARDED_STATUS : status.name()));
    }

    private static CodeCounts newCodeCounts(String status) {
        final var perOperation = new LongAdder[OPERATIONS.length + 1];
        for (var i = 0; i < perOperation.length; i++) {
            perOperation[i] = new LongAdder();
        }
        return new CodeCounts(status, perOperation);
    }

    public void refused(RefusalReason reason) {
        refused[reason.ordinal()].increment();
    }

    public long refusedCount(RefusalReason reason) {
        return refused[reason.ordinal()].sum();
    }

    public void forEachCount(CountVisitor visitor) {
        for (var op = 0; op < byStatus.length; op++) {
            for (var status = 0; status < byStatus[op].length; status++) {
                final var count = byStatus[op][status].sum();
                if (count > 0) {
                    visitor.visit(operationName(op), statusName(status), "", count);
                }
            }
        }
        byErrorCode.forEach((code, counts) -> {
            for (var op = 0; op < counts.perOperation().length; op++) {
                final var count = counts.perOperation()[op].sum();
                if (count > 0) {
                    visitor.visit(operationName(op), counts.status(), code, count);
                }
            }
        });
    }

    public void forEachDuration(DurationVisitor visitor) {
        for (var op = 0; op < durations.length; op++) {
            if (durations[op].count() > 0) {
                visitor.visit(operationName(op), durations[op]);
            }
        }
    }

    private static String operationName(int slot) {
        return slot == UNKNOWN_SLOT ? UNKNOWN_OPERATION : OPERATIONS[slot].name();
    }

    private static String statusName(int slot) {
        return slot == FORWARDED_SLOT ? FORWARDED_STATUS : STATUSES[slot].name();
    }
}
