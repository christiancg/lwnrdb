package org.techhouse.bckg_ops;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.config.Configuration;
import org.techhouse.log.Logger;

public class BackgroundTaskManager {
    private final Logger logger = Logger.logFor(BackgroundTaskManager.class);
    private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public void submitBackgroundTask(Event op) {
        queue.add(op);
    }

    public void startBackgroundWorkers() {
        final var threadCount = Configuration.getInstance().getBackgroundProcessingThreads();
        for (int i = 0; i < threadCount; i++) {
            final var thread = new BackgroundProcessorThread(queue);
            pool.execute(thread);
        }
        logger.info("Started listening for background tasks");
    }
}
