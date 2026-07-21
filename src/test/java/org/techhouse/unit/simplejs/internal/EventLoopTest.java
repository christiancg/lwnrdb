package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
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
        assertDoesNotThrow(loop::drain);
    }
}
