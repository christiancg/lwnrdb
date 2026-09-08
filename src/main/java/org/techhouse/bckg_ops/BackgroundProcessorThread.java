package org.techhouse.bckg_ops;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.log.Logger;

public class BackgroundProcessorThread implements Runnable {
    private final Logger logger = Logger.logFor(BackgroundProcessorThread.class);
    private final LinkedBlockingQueue<Event> queue;
    // Counts events taken but not yet finished, so a shutdown drain can tell an empty queue apart from an
    // empty queue with work still in flight.
    private final AtomicInteger inFlight;
    private final IdleSignal idleSignal;

    public BackgroundProcessorThread(LinkedBlockingQueue<Event> queue, AtomicInteger inFlight, IdleSignal idleSignal) {
        this.queue = queue;
        this.inFlight = inFlight;
        this.idleSignal = idleSignal;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            final Event event;
            try {
                event = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            inFlight.incrementAndGet();
            try {
                EventProcessorHelper.processEvent(event);
            } catch (Exception e) {
                logger.error("Error while processing background task: ", e);
            } finally {
                inFlight.decrementAndGet();
                idleSignal.signal();
            }
        }
    }
}
