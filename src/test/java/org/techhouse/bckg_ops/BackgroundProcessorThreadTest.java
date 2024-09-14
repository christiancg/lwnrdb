package org.techhouse.bckg_ops;

import org.techhouse.bckg_ops.events.Event;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;

import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

public class BackgroundProcessorThreadTest {

    /**
     * This class contains tests for the BackgroundProcessorThread class.
     * Specifically, the tests focus on the run method in BackgroundProcessorThread.
     */

    @Test
    public void testRunWhenQueueHasEvent() throws InterruptedException {
        LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();
        Event mockEvent = new Event(EventType.CREATED) {
            @Override
            public EventType getType() {
                return super.getType();
            }
        };
        queue.add(mockEvent);

        Thread thread = new Thread(new BackgroundProcessorThread(queue));
        thread.start();

        Thread.sleep(1000); // waiting for the thread to process the event
        thread.interrupt(); // interrupting the thread to stop it as the run method has an infinite loop
    }

    @Test
    public void testRunWhenQueueIsEmpty() {
        LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();

        Thread thread = new Thread(new BackgroundProcessorThread(queue));
        thread.start();
        assertTrue(thread.isAlive());

        // we just need to make sure the thread didn't crash, as the run method will indefinitely wait for an event
        thread.interrupt(); // this is to stop the thread as the run method has an infinite loop
    }
}