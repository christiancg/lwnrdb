package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class QueueDrainTest {
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(TriggerExecutor.class).stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static TriggerEvent event(String name) {
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, name, "proc", false, List.of(),
                "alice", 0, null);
    }

    @Test
    public void test_drain_runs_everything_queued_before_stopping() {
        final var ran = new AtomicInteger();
        triggerExecutor.start(_ -> ran.incrementAndGet());
        for (var i = 0; i < 50; i++) {
            triggerExecutor.submit(event("t" + i));
        }

        final var drained = triggerExecutor.drain(10_000L);

        assertTrue(drained, "the queue should have drained within the budget");
        assertEquals(50, ran.get(), "every queued trigger should have run");
        assertEquals(0, triggerExecutor.pending());
    }

    @Test
    public void test_drain_gives_up_when_the_budget_expires() throws Exception {
        final var blocked = new CountDownLatch(1);
        final var started = new CountDownLatch(1);
        triggerExecutor.start(_ -> {
            started.countDown();
            try {
                if (!blocked.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the blocked trigger was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        triggerExecutor.submit(event("stuck"));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        final var drained = triggerExecutor.drain(300L);

        assertFalse(drained, "a stuck trigger must not hang the shutdown");
        blocked.countDown();
    }

    @Test
    public void test_draining_refuses_new_triggers() {
        final var ran = new AtomicInteger();
        triggerExecutor.start(_ -> ran.incrementAndGet());
        triggerExecutor.drain(2_000L);

        triggerExecutor.submit(event("after"));

        assertEquals(0, triggerExecutor.pending());
    }

    @Test
    public void test_background_queue_drains_and_reports_empty() {
        backgroundTaskManager.startBackgroundWorkers();

        final var drained = backgroundTaskManager.drain(10_000L);

        assertTrue(drained);
        assertEquals(0, backgroundTaskManager.pending());
    }

    @Test
    public void test_background_queue_refuses_new_work_while_draining() {
        backgroundTaskManager.startBackgroundWorkers();
        backgroundTaskManager.drain(2_000L);

        backgroundTaskManager.submitBackgroundTask(new org.techhouse.bckg_ops.events.UsageProfileCleanupEvent());

        assertEquals(0, backgroundTaskManager.pending());
    }
}
