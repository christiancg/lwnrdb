package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundProcessorThread;
import org.techhouse.bckg_ops.IdleSignal;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;

public class BackgroundProcessorThreadTest {

    @Test
    public void testRunWhenQueueHasEvent() throws InterruptedException {
        LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();
        Event mockEvent = new Event(EventType.CREATED) {
        };
        queue.add(mockEvent);

        Thread thread = new Thread(new BackgroundProcessorThread(queue, new AtomicInteger(), new IdleSignal()));
        thread.start();

        Thread.sleep(1000);
        thread.interrupt();
    }

    @Test
    public void testRunWhenQueueIsEmpty() {
        LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();

        Thread thread = new Thread(new BackgroundProcessorThread(queue, new AtomicInteger(), new IdleSignal()));
        thread.start();
        assertTrue(thread.isAlive());

        thread.interrupt();
    }
}
