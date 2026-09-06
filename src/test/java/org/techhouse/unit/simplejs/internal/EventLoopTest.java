package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.ScriptTimeoutException;
import org.techhouse.simplejs.internal.EventLoop;

public class EventLoopTest {
    // microtasks run in FIFO order when drained
    @Test
    public void test_fifo_order() {
        final var loop = new EventLoop();
        final var order = new ArrayList<Integer>();
        loop.queueMicrotask(() -> order.add(1));
        loop.queueMicrotask(() -> order.add(2));
        loop.queueMicrotask(() -> order.add(3));
        loop.drain();
        assertEquals(java.util.List.of(1, 2, 3), order);
    }

    // a microtask that enqueues another is drained to quiescence
    @Test
    public void test_microtask_enqueues_more() {
        final var loop = new EventLoop();
        final var order = new ArrayList<Integer>();
        loop.queueMicrotask(() -> {
            order.add(1);
            loop.queueMicrotask(() -> order.add(2));
        });
        loop.drain();
        assertEquals(java.util.List.of(1, 2), order);
    }

    // draining an empty loop is a no-op
    @Test
    public void test_drain_empty() {
        final var loop = new EventLoop();
        assertDoesNotThrow(() -> loop.drain());
    }

    // microtasks run before a zero-delay timer
    @Test
    public void test_timer_fires_after_microtasks() {
        final var loop = new EventLoop();
        final var order = new ArrayList<String>();
        loop.setTimer(() -> order.add("timer"), 0, false);
        loop.queueMicrotask(() -> order.add("micro"));
        loop.drain();
        assertEquals(java.util.List.of("micro", "timer"), order);
    }

    // timers fire in ascending due-time order
    @Test
    public void test_timers_fire_in_due_order() {
        final var loop = new EventLoop();
        final var order = new ArrayList<Integer>();
        loop.setTimer(() -> order.add(20), 20, false);
        loop.setTimer(() -> order.add(5), 5, false);
        loop.setTimer(() -> order.add(10), 10, false);
        loop.drain();
        assertEquals(java.util.List.of(5, 10, 20), order);
    }

    // equal-delay timers fire in scheduling order (seq tie-break)
    @Test
    public void test_equal_delay_timers_fire_fifo() {
        final var loop = new EventLoop();
        final var order = new ArrayList<Integer>();
        loop.setTimer(() -> order.add(1), 0, false);
        loop.setTimer(() -> order.add(2), 0, false);
        loop.setTimer(() -> order.add(3), 0, false);
        loop.drain();
        assertEquals(java.util.List.of(1, 2, 3), order);
    }

    // a cleared timer never fires
    @Test
    public void test_cleared_timer_does_not_fire() {
        final var loop = new EventLoop();
        final var order = new ArrayList<Integer>();
        final var id = loop.setTimer(() -> order.add(1), 0, false);
        loop.clearTimer(id);
        loop.drain();
        assertTrue(order.isEmpty());
    }

    // an interval reschedules until it is cleared from within its own callback
    @Test
    public void test_interval_reschedules_until_cleared() {
        final var loop = new EventLoop();
        final var counter = new int[]{0};
        final long[] id = new long[1];
        id[0] = loop.setTimer(() -> {
            counter[0]++;
            if (counter[0] == 3) {
                loop.clearTimer(id[0]);
            }
        }, 0, true);
        loop.drain();
        assertEquals(3, counter[0]);
    }

    // a timer callback that throws does not abort the drain; later timers still fire
    @Test
    public void test_callback_throw_does_not_abort() {
        final var loop = new EventLoop();
        final var order = new ArrayList<Integer>();
        loop.setTimer(() -> {
            throw new org.techhouse.simplejs.exceptions.JsThrowException(
                    org.techhouse.simplejs.values.JsUndefined.getInstance());
        }, 0, false);
        loop.setTimer(() -> order.add(2), 0, false);
        loop.drain();
        assertEquals(java.util.List.of(2), order);
    }

    // draining actually waits real wall-clock time for a delayed timer
    @Test
    public void test_wait_actually_elapses() {
        final var loop = new EventLoop();
        loop.setTimer(() -> {
        }, 20, false);
        final var startNanos = System.nanoTime();
        loop.drain(-1);
        final var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        assertTrue(elapsedMillis >= 15, "expected at least ~15ms elapsed, got " + elapsedMillis);
    }

    // a timer due after the deadline raises ScriptTimeoutException
    @Test
    public void test_drain_throws_when_timer_due_past_deadline() {
        final var loop = new EventLoop();
        loop.setTimer(() -> {
        }, 10_000, false);
        final var deadline = System.nanoTime() + 20_000_000L;
        assertThrows(ScriptTimeoutException.class, () -> loop.drain(deadline));
    }

    // a timer due before the deadline fires normally
    @Test
    public void test_drain_deadline_allows_timer_due_before_it() {
        final var loop = new EventLoop();
        final var order = new ArrayList<Integer>();
        loop.setTimer(() -> order.add(1), 0, false);
        final var deadline = System.nanoTime() + 5_000_000_000L;
        assertDoesNotThrow(() -> loop.drain(deadline));
        assertEquals(java.util.List.of(1), order);
    }
}
