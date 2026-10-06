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

        Thread thread = new Thread(new BackgroundProcessorThread(queue, new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new IdleSignal()));
        thread.start();

        Thread.sleep(1000);
        thread.interrupt();
    }

    @Test
    public void test_a_failed_batch_still_settles_the_count_the_producer_took() throws InterruptedException {
        final var queue = new LinkedBlockingQueue<Event>();
        final var inFlight = new AtomicInteger(1);
        queue.add(new Event(EventType.CREATED) {
        });

        final var idleSignal = new IdleSignal();
        final var thread = new Thread(
                new BackgroundProcessorThread(queue, inFlight, new AtomicInteger(), new AtomicInteger(1), idleSignal));
        thread.start();
        try {
            assertTrue(idleSignal.awaitIdle(() -> inFlight.get() <= 0, 5000L));
            assertFalse(idleSignal.awaitIdle(() -> inFlight.get() != 0, 100L));
            assertEquals(0, inFlight.get(),
                    "an event counted at submit is settled exactly once, even when its batch throws");
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
    }

    @Test
    public void testRunWhenQueueIsEmpty() {
        LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();

        Thread thread = new Thread(new BackgroundProcessorThread(queue, new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new IdleSignal()));
        thread.start();
        assertTrue(thread.isAlive());

        thread.interrupt();
    }
}
