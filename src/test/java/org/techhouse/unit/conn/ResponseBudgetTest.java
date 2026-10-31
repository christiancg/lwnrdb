package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.conn.ResponseBudget;
import org.techhouse.ops.OperationType;

public class ResponseBudgetTest {
    private static final long LIMIT = 1024L * 1024;
    private static final long FLOOR = Globals.RESPONSE_BUDGET_FLOOR_BYTES;
    private final ResponseBudget budget = new ResponseBudget(() -> LIMIT);

    @Test
    public void reserveSettleAndReleaseKeepAnExactTally() {
        assertTrue(budget.tryReserve(100_000));
        assertEquals(100_000, budget.unsentBytes());

        budget.settle(100_000, 120_000);
        assertEquals(120_000, budget.unsentBytes());
        budget.settle(120_000, 90_000);
        assertEquals(90_000, budget.unsentBytes());

        budget.release(90_000);
        assertEquals(0, budget.unsentBytes());
    }

    @Test
    public void aReservationPastTheLimitIsRefusedAndChargesNothing() {
        assertTrue(budget.tryReserve(LIMIT - FLOOR));
        assertFalse(budget.tryReserve(FLOOR + 1));
        assertEquals(LIMIT - FLOOR, budget.unsentBytes());
        assertTrue(budget.tryReserve(FLOOR), "a reservation landing exactly on the limit fits");
        assertEquals(LIMIT, budget.unsentBytes());
    }

    @Test
    public void aResponseUnderTheFloorIsAlwaysAdmittedAndCharged() {
        assertTrue(budget.tryReserve(LIMIT));
        assertFalse(budget.tryReserve(FLOOR), "a response exactly at the floor is checked against the limit");

        assertTrue(budget.tryReserve(FLOOR - 1));
        assertEquals(LIMIT + FLOOR - 1, budget.unsentBytes());
    }

    @Test
    public void aForcedReservationIgnoresTheLimit() {
        assertTrue(budget.tryReserve(LIMIT));
        budget.reserve(5 * LIMIT);

        assertEquals(6 * LIMIT, budget.unsentBytes());
        assertFalse(budget.tryReserve(FLOOR));
    }

    @Test
    public void aZeroLimitResolvesToAQuarterOfTheHeap() {
        final var resolved = new ResponseBudget(() -> 0L);

        assertEquals(Runtime.getRuntime().maxMemory() / 4, resolved.limit());
        assertEquals(LIMIT, budget.limit());
    }

    @Test
    public void theLimitIsReadOnEveryReservation() {
        final var configured = new AtomicLong(FLOOR);
        final var live = new ResponseBudget(configured::get);

        assertFalse(live.tryReserve(FLOOR + 1));
        configured.set(LIMIT);
        assertTrue(live.tryReserve(FLOOR + 1));
    }

    @Test
    public void onlyPureReadsAreRefusable() {
        final var reads = EnumSet.of(OperationType.FIND_BY_ID, OperationType.AGGREGATE, OperationType.LIST_DATABASES,
                OperationType.LIST_COLLECTIONS, OperationType.GET_SCHEMA, OperationType.LIST_USERS,
                OperationType.GET_DATABASE_STATS, OperationType.LIST_TRANSACTIONS, OperationType.LIST_PROCEDURES,
                OperationType.LIST_TRIGGERS, OperationType.LIST_SCHEDULES, OperationType.LIST_SCRIPTS,
                OperationType.LIST_TRIGGER_RUNS);
        for (final var type : OperationType.values()) {
            assertEquals(reads.contains(type), ResponseBudget.isRefusable(type), type.name());
        }
    }

    @Test
    public void writesTransactionControlAndScriptsAreNeverRefusable() {
        for (final var type : EnumSet.of(OperationType.SAVE, OperationType.BULK_SAVE, OperationType.DELETE,
                OperationType.COMMIT_TRANSACTION, OperationType.START_TRANSACTION, OperationType.ROLLBACK_TRANSACTION,
                OperationType.CALL_PROCEDURE, OperationType.RUN_SCRIPT, OperationType.CREATE_COLLECTION,
                OperationType.LISTEN, OperationType.AUTHENTICATE, OperationType.CLOSE_CONNECTION)) {
            assertFalse(ResponseBudget.isRefusable(type), type.name());
        }
    }

    @Test
    public void concurrentReserveAndReleaseNeverGoNegativeAndEndAtZero() throws Exception {
        final var threads = 8;
        final var rounds = 20_000;
        final var lowest = new AtomicLong();
        final var highest = new AtomicLong();
        final var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (var t = 0; t < threads; t++) {
                final var size = FLOOR + t * 1000L;
                pool.execute(() -> {
                    awaitQuietly(start);
                    for (var i = 0; i < rounds; i++) {
                        if (budget.tryReserve(size)) {
                            highest.accumulateAndGet(budget.unsentBytes(), Math::max);
                            budget.release(size);
                        }
                        lowest.accumulateAndGet(budget.unsentBytes(), Math::min);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        }
        assertTrue(lowest.get() >= 0, String.valueOf(lowest.get()));
        assertTrue(highest.get() <= LIMIT, String.valueOf(highest.get()));
        assertEquals(0, budget.unsentBytes());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
