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

    @Test
    public void test_drain_empty() {
        final var loop = new EventLoop();
        assertDoesNotThrow(() -> loop.drain()); // NOPMD - overloaded drain() makes the method ref ambiguous
    }

    @Test
    public void test_timer_fires_after_microtasks() {
        final var loop = new EventLoop();
        final var order = new ArrayList<String>();
        loop.setTimer(() -> order.add("timer"), 0, false);
        loop.queueMicrotask(() -> order.add("micro"));
        loop.drain();
        assertEquals(java.util.List.of("micro", "timer"), order);
    }

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

    @Test
    public void test_cleared_timer_does_not_fire() {
        final var loop = new EventLoop();
        final var order = new ArrayList<Integer>();
        final var id = loop.setTimer(() -> order.add(1), 0, false);
        loop.clearTimer(id);
        loop.drain();
        assertTrue(order.isEmpty());
    }

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

    @Test
    public void test_drain_throws_when_timer_due_past_deadline() {
        final var loop = new EventLoop();
        loop.setTimer(() -> {
        }, 10_000, false);
        final var deadline = System.nanoTime() + 20_000_000L;
        assertThrows(ScriptTimeoutException.class, () -> loop.drain(deadline));
    }

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
