package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundLanes;
import org.techhouse.bckg_ops.BackgroundProcessorThread;
import org.techhouse.bckg_ops.IdleSignal;
import org.techhouse.bckg_ops.ScheduleExecutor;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.config.Configuration;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenProcessorThread;
import org.techhouse.listen.ListenRegistration;
import org.techhouse.test.TestUtils;

public class WorkerErrorSurvivalTest {
    private TriggerExecutor executor;

    @AfterEach
    void stop() throws Exception {
        if (executor != null) {
            executor.stop();
        }
        TestUtils.setPrivateField(Configuration.getInstance(), "triggerThreads", 2);
    }

    private static TriggerEvent event(String name) {
        return new TriggerEvent(EventType.CREATED, "db", "coll", name, "p", false, List.of(), "alice", 0);
    }

    @Test
    public void test_a_trigger_worker_keeps_running_after_an_error() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "triggerThreads", 1);
        executor = new TriggerExecutor();
        final var seen = new CopyOnWriteArrayList<String>();
        executor.start(event -> {
            if (event.getTriggerName().equals("poison")) {
                throw new OutOfMemoryError("simulated");
            }
            seen.add(event.getTriggerName());
        });
        executor.submit(event("poison"));
        executor.submit(event("after"));

        assertTrue(executor.drain(5_000));
        assertEquals(List.of("after"), seen);
        assertEquals(1L, executor.getFailed());
        assertEquals(1L, executor.getFired());
    }

    @Test
    public void test_a_background_worker_keeps_running_after_an_error() throws Exception {
        final var queue = new BackgroundLanes(1);
        final var inFlight = new AtomicInteger(1);
        final var processed = new AtomicInteger();
        queue.add(new Event(EventType.CREATED) {
            @Override
            public String toString() {
                throw new StackOverflowError("simulated");
            }
        });
        final var idleSignal = new IdleSignal();
        final var thread = new Thread(new BackgroundProcessorThread(queue, 0, inFlight, new AtomicInteger(),
                new AtomicInteger(1), idleSignal));
        thread.start();
        try {
            assertTrue(idleSignal.awaitIdle(() -> inFlight.get() <= 0, 5000L));
            inFlight.incrementAndGet();
            queue.add(new Event(EventType.CREATED) {
                @Override
                public String toString() {
                    processed.incrementAndGet();
                    return "after-the-error";
                }
            });
            assertTrue(idleSignal.awaitIdle(() -> inFlight.get() <= 0, 5000L));
            assertTrue(processed.get() >= 1, "the event after the error was never processed");
            assertTrue(thread.isAlive());
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
    }

    @Test
    public void test_a_schedule_registry_refresh_survives_an_error() throws Exception {
        final var executor = new ScheduleExecutor();
        final var registry = mock(ScheduleRegistry.class);
        doThrow(new StackOverflowError("simulated")).when(registry).loadAll();
        TestUtils.setPrivateField(executor, "registry", registry);
        final var refresh = ScheduleExecutor.class.getDeclaredMethod("refreshRegistry");
        refresh.setAccessible(true);

        assertDoesNotThrow(() -> refresh.invoke(executor));
        verify(registry).loadAll();
    }

    @Test
    public void test_a_listen_worker_keeps_running_after_an_error() throws Exception {
        final var calls = new AtomicInteger();
        final var bothProcessed = new CountDownLatch(2);
        final var manager = new ListenManager() {
            @Override
            public ListenRegistration getRegistration(UUID listenId) {
                bothProcessed.countDown();
                if (calls.incrementAndGet() == 1) {
                    throw new OutOfMemoryError("simulated");
                }
                return null;
            }
        };
        final var queue = new LinkedBlockingQueue<UUID>();
        queue.offer(UUID.randomUUID());
        queue.offer(UUID.randomUUID());
        final var thread = new Thread(new ListenProcessorThread(queue, manager));
        thread.setDaemon(true);
        thread.start();
        try {
            assertTrue(bothProcessed.await(5, TimeUnit.SECONDS), "the listen after the error was never processed");
            assertTrue(thread.isAlive());
        } finally {
            thread.interrupt();
            thread.join(1000);
        }
    }
}
