package org.techhouse.bckg_ops;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.log.Logger;

public class BackgroundProcessorThread implements Runnable {
    private static final int MAX_BATCH = 256;

    private final Logger logger = Logger.logFor(BackgroundProcessorThread.class);
    private final LinkedBlockingQueue<Event> queue;
    private final AtomicInteger inFlight;
    private final IdleSignal idleSignal;

    public BackgroundProcessorThread(LinkedBlockingQueue<Event> queue, AtomicInteger inFlight, IdleSignal idleSignal) {
        this.queue = queue;
        this.inFlight = inFlight;
        this.idleSignal = idleSignal;
    }

    @Override
    public void run() {
        final var batch = new ArrayList<Event>(MAX_BATCH);
        while (!Thread.currentThread().isInterrupted()) {
            final Event first;
            try {
                first = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            inFlight.incrementAndGet();
            batch.clear();
            batch.add(first);
            while (batch.size() < MAX_BATCH) {
                final var next = queue.poll();
                if (next == null) {
                    break;
                }
                inFlight.incrementAndGet();
                batch.add(next);
            }
            try {
                EventProcessorHelper.processBatch(batch);
            } catch (Exception e) {
                logger.error("Error while processing background task: ", e);
            } finally {
                inFlight.addAndGet(-batch.size());
                idleSignal.signal();
            }
        }
    }
}
