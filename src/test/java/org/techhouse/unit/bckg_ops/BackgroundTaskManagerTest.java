package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class BackgroundTaskManagerTest {

    @Test
    void testSubmitBackgroundTask() throws NoSuchFieldException, IllegalAccessException {
        var manager = new BackgroundTaskManager();
        var event = new EntityEvent(EventType.CREATED, "test", "test", new DbEntry());

        manager.submitBackgroundTask(event);

        final var type = new ReflectionUtils.TypeToken<LinkedBlockingQueue<Event>>() {
        };
        LinkedBlockingQueue<Event> queue = TestUtils.getPrivateField(manager, "queue", type);

        assertTrue(queue.contains(event), "Queue should contain the event after submission");
    }

    @Test
    void testStartBackgroundWorkers() throws NoSuchFieldException, IllegalAccessException {
        var manager = new BackgroundTaskManager();
        var pool = mock(ExecutorService.class);

        Field poolField = BackgroundTaskManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        poolField.set(manager, pool);

        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "backgroundProcessingThreads", 3);

        manager.startBackgroundWorkers();

        verify(pool, times(3)).execute(any(Runnable.class));
    }

    @Test
    void testStartBackgroundWorkersWhenZeroThreads() throws NoSuchFieldException, IllegalAccessException {
        var manager = new BackgroundTaskManager();
        var pool = mock(ExecutorService.class);

        Field poolField = BackgroundTaskManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        poolField.set(manager, pool);

        var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "backgroundProcessingThreads", 0);

        manager.startBackgroundWorkers();

        verify(pool, never()).execute(any(Runnable.class));
    }

    @Test
    void test_a_submitted_event_counts_as_pending_until_it_is_settled() {
        final var manager = new BackgroundTaskManager();
        manager.submitBackgroundTask(new EntityEvent(EventType.CREATED, "test", "test", new DbEntry()));

        assertEquals(1, manager.pending(), "an event is pending from the moment it is submitted");
        assertFalse(manager.drain(0L), "a queue holding a submitted event is not idle");
        assertEquals(0, manager.pending(), "stopping discards exactly the events it dropped from the count");
    }

    @Test
    void test_a_task_refused_while_draining_is_not_counted() {
        final var manager = new BackgroundTaskManager();
        assertTrue(manager.drain(0L));

        manager.submitBackgroundTask(new EntityEvent(EventType.CREATED, "test", "test", new DbEntry()));

        assertEquals(0, manager.pending());
    }

    @Test
    void aFollowOnEventIsQueuedWhileDraining() {
        final var manager = new BackgroundTaskManager();
        assertTrue(manager.drain(0L));

        manager.submitFollowOn(new EntityEvent(EventType.CREATED, "test", "test", new DbEntry()));

        assertEquals(1, manager.pending());
    }

    @Test
    void testStopBackgroundWorkersDrainsQueueAndAllowsRestart() throws Exception {
        var manager = new BackgroundTaskManager();
        manager.submitBackgroundTask(new EntityEvent(EventType.CREATED, "test", "test", new DbEntry()));

        final var oldPool = TestUtils.getPrivateField(manager, "pool", java.util.concurrent.ExecutorService.class);

        manager.stopBackgroundWorkers();

        final var type = new ReflectionUtils.TypeToken<LinkedBlockingQueue<Event>>() {
        };
        LinkedBlockingQueue<Event> queue = TestUtils.getPrivateField(manager, "queue", type);
        assertTrue(queue.isEmpty(), "pending events should be dropped on stop");
        assertTrue(oldPool.isShutdown(), "the previous pool should be shut down");

        final var newPool = TestUtils.getPrivateField(manager, "pool", java.util.concurrent.ExecutorService.class);
        assertTrue(newPool != oldPool && !newPool.isShutdown(), "a fresh, usable pool should replace the old one");
    }

    private static EntityEvent anyEvent() {
        return new EntityEvent(EventType.CREATED, "test", "test", new DbEntry());
    }

    @Test
    void awaitCapacityTimesOutAtTheBound() throws Exception {
        final var configuration = Configuration.getInstance();
        TestUtils.setPrivateField(configuration, "backgroundQueueMaxEvents", 2);
        try {
            final var manager = new BackgroundTaskManager();
            assertTrue(manager.awaitCapacity(0L));
            manager.submitBackgroundTask(anyEvent());
            assertTrue(manager.awaitCapacity(0L), "one event below the bound is admitted at once");
            manager.submitBackgroundTask(anyEvent());

            final var started = System.nanoTime();
            assertFalse(manager.awaitCapacity(150L));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 100L, "it waits before refusing");
            assertFalse(manager.awaitCapacity(0L), "a zero wait refuses at once");

            manager.stopBackgroundWorkers();
            assertTrue(manager.awaitCapacity(0L));
        } finally {
            TestUtils.setPrivateField(configuration, "backgroundQueueMaxEvents",
                    ConfigKey.BACKGROUND_QUEUE_MAX_EVENTS.defaultValue());
        }
    }

    @Test
    void awaitCapacityReturnsOnceRoomFreesUp() throws Exception {
        final var configuration = Configuration.getInstance();
        TestUtils.setPrivateField(configuration, "backgroundQueueMaxEvents", 1);
        try {
            final var manager = new BackgroundTaskManager();
            manager.submitBackgroundTask(anyEvent());
            final var admitted = new AtomicBoolean();
            final var waiter = new Thread(() -> {
                try {
                    admitted.set(manager.awaitCapacity(10_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            waiter.start();

            manager.stopBackgroundWorkers();
            waiter.join(10_000L);

            assertFalse(waiter.isAlive());
            assertTrue(admitted.get(), "a waiting write is served as soon as the queue has room");
        } finally {
            TestUtils.setPrivateField(configuration, "backgroundQueueMaxEvents",
                    ConfigKey.BACKGROUND_QUEUE_MAX_EVENTS.defaultValue());
        }
    }
}
