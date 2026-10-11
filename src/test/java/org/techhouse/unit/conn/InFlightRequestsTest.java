package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.conn.InFlightRequests;

public class InFlightRequestsTest {
    @Test
    public void test_entering_and_leaving_are_counted() {
        final var inFlight = new InFlightRequests();

        inFlight.enter();
        inFlight.enter();
        assertEquals(2, inFlight.current());
        inFlight.exit();
        assertEquals(1, inFlight.current());
    }

    @Test
    public void test_an_idle_tracker_is_idle_at_once() throws Exception {
        assertTrue(new InFlightRequests().awaitIdle(0));
    }

    @Test
    public void test_a_busy_tracker_times_out() throws Exception {
        final var inFlight = new InFlightRequests();
        inFlight.enter();

        assertFalse(inFlight.awaitIdle(150));
    }

    @Test
    public void test_waiting_ends_when_the_last_request_leaves() throws Exception {
        final var inFlight = new InFlightRequests();
        inFlight.enter();
        final var leaving = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.exit();
        });
        leaving.start();

        assertTrue(inFlight.awaitIdle(5000));
        leaving.join();
        assertEquals(0, inFlight.current());
    }
}
