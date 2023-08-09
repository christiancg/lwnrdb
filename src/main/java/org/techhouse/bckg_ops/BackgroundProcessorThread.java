package org.techhouse.bckg_ops;

import org.techhouse.bckg_ops.events.Event;

import java.util.concurrent.LinkedBlockingQueue;

public class BackgroundProcessorThread implements Runnable {
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
                System.out.println("Error while processing background task: " + e.getMessage());
            }
        }
    }
}
