package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class BackgroundTaskManagerTest {

    /**
     * BackgroundTaskManager class is responsible for managing and processing the background tasks.
     * submitBackgroundTask() method is used to submit a task in the form of Event to the task manager.
     */

    @Test
    void testSubmitBackgroundTask() throws NoSuchFieldException, IllegalAccessException {
        // setup
        var manager = new BackgroundTaskManager();
        var event = new EntityEvent(EventType.CREATED, "test", "test", new DbEntry());

        // when
        manager.submitBackgroundTask(event);

        final var type = new ReflectionUtils.TypeToken<LinkedBlockingQueue<Event>>() {
        };
        LinkedBlockingQueue<Event> queue = TestUtils.getPrivateField(manager, "queue", type);

        // then
        assertTrue(queue.contains(event), "Queue should contain the event after submission");
    }

    /**
     * Tests that the expected number of BackgroundProcessorThread are started when starting background workers.
     * The number of BackgroundProcessorThreads to be started is retrieved from the Configuration instance.
     * The test verifies that the execute() method of the pool is called the correct number of times.
     */
    @Test
    void testStartBackgroundWorkers() throws NoSuchFieldException, IllegalAccessException {
        var manager = new BackgroundTaskManager();
        var pool = mock(ExecutorService.class);

        Field poolField = BackgroundTaskManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        poolField.set(manager, pool);

        final var configInstance = Configuration.getInstance();
        final var configField = Configuration.class.getDeclaredField("backgroundProcessingThreads");
        configField.setAccessible(true);
        configField.set(configInstance, 3);

        manager.startBackgroundWorkers();

        verify(pool, times(3)).execute(any(Runnable.class));
    }

    /**
     * Test the startBackgroundWorkers method when the number of background processing threads is zero.
     * The mock Configuration object should return 0 for getBackgroundProcessingThreads.
     * It should verify that the execute method of the pool is never called.
     */
    @Test
    void testStartBackgroundWorkersWhenZeroThreads() throws NoSuchFieldException, IllegalAccessException {
        var manager = new BackgroundTaskManager();
        var pool = mock(ExecutorService.class);

        Field poolField = BackgroundTaskManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        poolField.set(manager, pool);

        var configInstance = Configuration.getInstance();
        var configField = Configuration.class.getDeclaredField("backgroundProcessingThreads");
        configField.setAccessible(true);
        configField.set(configInstance, 0);

        manager.startBackgroundWorkers();

        verify(pool, never()).execute(any(Runnable.class));
    }

    /**
     * stopBackgroundWorkers must shut down the running pool, drop any pending events and leave the manager
     * reusable (a fresh pool so startBackgroundWorkers can run again).
     */
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
}
