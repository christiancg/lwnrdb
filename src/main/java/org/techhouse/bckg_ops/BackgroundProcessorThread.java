package org.techhouse.bckg_ops;

import java.util.concurrent.LinkedBlockingQueue;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.log.Logger;

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
                logger.error("Error while processing background task: ", e);
            }
        }
    }
}
