package org.techhouse.bckg_ops;

import org.techhouse.bckg_ops.events.Event;
import org.techhouse.log.Logger;

import java.util.concurrent.LinkedBlockingQueue;

public class BackgroundProcessorThread implements Runnable {
    private final Logger logger = Logger.logFor(BackgroundProcessorThread.class);
    private final LinkedBlockingQueue<Event> queue;
    public BackgroundProcessorThread(LinkedBlockingQueue<Event> queue) {
        this.queue = queue;
    }
    @Override
    public void run() {
        while (true) {
            try {
                final var event = queue.take();
                EventProcessorHelper.processEvent(event);
            } catch (Exception e) {
                logger.error("Error while processing background task", e);
            }
        }
    }
}
