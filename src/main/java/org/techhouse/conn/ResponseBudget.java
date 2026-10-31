package org.techhouse.conn;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ops.OperationType;

public class ResponseBudget {
    private static final Configuration configuration = Configuration.getInstance();
    private static final Set<OperationType> REFUSABLE_READS = EnumSet.of(OperationType.FIND_BY_ID,
            OperationType.AGGREGATE, OperationType.LIST_DATABASES, OperationType.LIST_COLLECTIONS,
            OperationType.GET_SCHEMA, OperationType.LIST_USERS, OperationType.GET_DATABASE_STATS,
            OperationType.LIST_TRANSACTIONS, OperationType.LIST_PROCEDURES, OperationType.LIST_TRIGGERS,
            OperationType.LIST_SCHEDULES, OperationType.LIST_SCRIPTS, OperationType.LIST_TRIGGER_RUNS);
    private final AtomicLong unsent = new AtomicLong();
    private final LongSupplier configuredLimit;

    public ResponseBudget() {
        this(() -> configuration.sizeValue(ConfigKey.MAX_UNSENT_RESPONSE_BYTES));
    }

    public ResponseBudget(LongSupplier configuredLimit) {
        this.configuredLimit = configuredLimit;
    }

    public static boolean isRefusable(OperationType requestType) {
        return requestType != null && REFUSABLE_READS.contains(requestType);
    }

    public long limit() {
        final var configured = configuredLimit.getAsLong();
        return configured == 0 ? Runtime.getRuntime().maxMemory() / 4 : configured;
    }

    public boolean tryReserve(long bytes) {
        if (bytes < Globals.RESPONSE_BUDGET_FLOOR_BYTES) {
            unsent.addAndGet(bytes);
            return true;
        }
        final var limit = limit();
        while (true) {
            final var current = unsent.get();
            if (current + bytes > limit) {
                return false;
            }
            if (unsent.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    public void reserve(long bytes) {
        unsent.addAndGet(bytes);
    }

    public void settle(long reserved, long actual) {
        if (actual != reserved) {
            unsent.addAndGet(actual - reserved);
        }
    }

    public void release(long bytes) {
        if (bytes != 0) {
            unsent.addAndGet(-bytes);
        }
    }

    public long unsentBytes() {
        return unsent.get();
    }
}
