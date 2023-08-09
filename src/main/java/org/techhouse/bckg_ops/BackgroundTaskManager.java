package org.techhouse.bckg_ops;

import org.techhouse.bckg_ops.events.Event;
import org.techhouse.config.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class BackgroundTaskManager {
    private static final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(Configuration.getInstance().getBackgroundProcessingThreads());
    public void submitBackgroundTask(Event op) {
        queue.add(op);
    }

    public void startBackgroundWorkers() {
        final var threadCount = Configuration.getInstance().getBackgroundProcessingThreads();
        for (int i = 0; i<threadCount; i++) {
            final var thread = new BackgroundProcessorThread(queue);
            pool.execute(thread);
        }
        System.out.println("Started listening for background tasks");
    }
}
