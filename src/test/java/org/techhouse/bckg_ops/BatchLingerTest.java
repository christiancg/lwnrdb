package org.techhouse.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;

public class BatchLingerTest {
    private static final long NOW = 5_000_000_000L;

    @Test
    public void test_an_idle_worker_does_not_linger() {
        assertFalse(BatchLinger.applies(NOW - 1_000_000_000L, NOW, 1));
    }

    @Test
    public void test_a_busy_worker_lingers_below_the_target() {
        assertTrue(BatchLinger.applies(NOW - 100_000L, NOW, 1));
        assertTrue(BatchLinger.applies(NOW - 100_000L, NOW, Globals.BACKGROUND_LINGER_EVENTS - 1));
    }

    @Test
    public void test_a_full_enough_batch_does_not_linger() {
        assertFalse(BatchLinger.applies(NOW - 100_000L, NOW, Globals.BACKGROUND_LINGER_EVENTS));
        assertFalse(BatchLinger.applies(NOW - 100_000L, NOW, Globals.BACKGROUND_LINGER_EVENTS * 4));
    }

    @Test
    public void test_the_busy_window_boundary() {
        final var window = Globals.BACKGROUND_LINGER_BUSY_WINDOW_NANOS;

        assertTrue(BatchLinger.applies(NOW - window + 1, NOW, 1));
        assertFalse(BatchLinger.applies(NOW - window, NOW, 1));
    }
}
