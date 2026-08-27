package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.config.Configuration;
import org.techhouse.test.TestUtils;

public class TriggerExecutorTest {
    private TriggerExecutor executor;

    @AfterEach
    void stop() throws Exception {
        if (executor != null) {
            executor.stop();
        }
        TestUtils.setPrivateField(Configuration.getInstance(), "triggerQueueSize", 10_000);
        TestUtils.setPrivateField(Configuration.getInstance(), "triggerThreads", 2);
    }

    private static TriggerEvent event(String name) {
        return new TriggerEvent(EventType.CREATED, "db", "coll", name, "p", false, List.of(), "alice", 0);
    }

    @Test
    public void test_runs_submitted_event() throws Exception {
        executor = new TriggerExecutor();
        final var latch = new CountDownLatch(1);
        final var seen = new CopyOnWriteArrayList<TriggerEvent>();
        executor.start(triggerEvent -> {
            seen.add(triggerEvent);
            latch.countDown();
        });
        executor.submit(event("t"));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("t", seen.getFirst().getTriggerName());
        assertEquals(1L, executor.getFired());
        assertEquals(0L, executor.getDropped());
    }

    // Nothing consumes the queue before start(), so submit must not silently accumulate events
    @Test
    public void test_submit_before_start_is_a_no_op() {
        executor = new TriggerExecutor();
        executor.submit(event("t"));
        assertEquals(0, executor.getQueued());
        assertEquals(0L, executor.getDropped());
    }

    // An unbounded queue of retained documents is a heap risk, so overflow drops the oldest and counts it
    @Test
    public void test_drops_oldest_when_queue_full() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "triggerQueueSize", 2);
        TestUtils.setPrivateField(Configuration.getInstance(), "triggerThreads", 1);
        executor = new TriggerExecutor();
        final var block = new CountDownLatch(1);
        final var started = new CountDownLatch(1);
        executor.start(_ -> {
            started.countDown();
            try {
                if (!block.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the worker was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        executor.submit(event("first"));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        // The single worker is now blocked, so the queue fills and then overflows.
        executor.submit(event("a"));
        executor.submit(event("b"));
        executor.submit(event("c"));
        assertTrue(executor.getDropped() >= 1, "expected an overflow drop, got " + executor.getDropped());
        block.countDown();
    }

    @Test
    public void test_count_failure_is_reported() {
        executor = new TriggerExecutor();
        executor.countFailure();
        executor.countFailure();
        assertEquals(2L, executor.getFailed());
    }

    // A dispatcher that throws must be counted, not kill the worker
    @Test
    public void test_dispatcher_failure_is_counted_and_the_worker_survives() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "triggerThreads", 1);
        executor = new TriggerExecutor();
        final var survived = new CountDownLatch(1);
        executor.start(triggerEvent -> {
            if ("sentinel".equals(triggerEvent.getTriggerName())) {
                survived.countDown();
                return;
            }
            throw new IllegalStateException("boom");
        });
        executor.submit(event("one"));
        executor.submit(event("two"));
        // The single worker counts a failure before taking the next event, so the sentinel running proves both
        // throws were already counted -- and that the worker outlived them.
        executor.submit(event("sentinel"));
        assertTrue(survived.await(5, TimeUnit.SECONDS));
        assertEquals(2L, executor.getFailed());
    }

    @Test
    public void test_stop_drains_and_is_restartable() throws Exception {
        executor = new TriggerExecutor();
        executor.start(_ -> {
        });
        executor.stop();
        assertEquals(0, executor.getQueued());
        // Submitting after stop is a no-op again, and a fresh start still consumes.
        executor.submit(event("ignored"));
        assertEquals(0, executor.getQueued());
        final var latch = new CountDownLatch(1);
        executor.start(_ -> latch.countDown());
        executor.submit(event("after-restart"));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
