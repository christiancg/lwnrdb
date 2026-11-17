package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;

public class RefusalHistoryTest {
    private static final String INDEX = "db|coll|field";
    private static final long SPACING_NANOS = TimeUnit.MILLISECONDS.toNanos(Globals.REFUSAL_MIN_SPACING_MS);

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final RefusalHistory history = new RefusalHistory(clock::get);

    @Test
    public void twoRefusalsWithinTheSpacingCountOnce() {
        history.record(INDEX);
        clock.addAndGet(SPACING_NANOS - 1);
        history.record(INDEX);

        assertFalse(history.refusedRepeatedly(INDEX), "one query reading the index twice is one refusal");
    }

    @Test
    public void twoRefusalsFartherApartThanTheSpacingCountTwice() {
        history.record(INDEX);
        clock.addAndGet(SPACING_NANOS + 1);
        history.record(INDEX);

        assertTrue(history.refusedRepeatedly(INDEX));
    }

    @Test
    public void aRepeatWithinTheSpacingDoesNotMoveTheFirstRefusal() {
        history.record(INDEX);
        clock.addAndGet(SPACING_NANOS / 2);
        history.record(INDEX);
        clock.addAndGet(SPACING_NANOS / 2 + 1);
        history.record(INDEX);

        assertTrue(history.refusedRepeatedly(INDEX), "the spacing counts from the last refusal that counted");
    }

    @Test
    public void refusalsOutsideTheWindowDoNotCount() {
        history.record(INDEX);
        clock.addAndGet(RefusalHistory.WINDOW_NANOS + 1);
        history.record(INDEX);
        clock.addAndGet(RefusalHistory.WINDOW_NANOS + 1);

        assertFalse(history.refusedRepeatedly(INDEX));
    }
}
