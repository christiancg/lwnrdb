package org.techhouse.bckg_ops;

import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.CollectionEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Configuration;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class BackgroundTaskManagerTest {

    /**
     * BackgroundTaskManager class is responsible for managing and processing the background tasks.
     * submitBackgroundTask() method is used to submit a task in the form of Event to the task manager.
     */

    @Test
    void testSubmitBackgroundTask() throws NoSuchFieldException, IllegalAccessException {
        // setup
        var manager = new BackgroundTaskManager();
        var event = new CollectionEvent(EventType.CREATED, "test", "test");

        // when
        manager.submitBackgroundTask(event);

        Field field = BackgroundTaskManager.class.getDeclaredField("queue");
        field.setAccessible(true);

        // then
        LinkedBlockingQueue<Event> queue = (LinkedBlockingQueue<Event>) field.get(manager);
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
}
