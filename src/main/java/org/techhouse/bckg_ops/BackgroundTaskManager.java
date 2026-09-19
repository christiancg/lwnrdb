package org.techhouse.bckg_ops;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.config.Configuration;
import org.techhouse.log.Logger;

public class BackgroundTaskManager {
    private final Logger logger = Logger.logFor(BackgroundTaskManager.class);
    private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final IdleSignal idleSignal = new IdleSignal();
    private final AtomicInteger parked = new AtomicInteger();
    private final AtomicInteger workerCount = new AtomicInteger();
    private volatile boolean draining;
    private volatile ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public void submitBackgroundTask(Event op) {
        if (draining) {
            logger.warning("Rejecting a background task during shutdown: " + op);
            return;
        }
        queue.add(op);
    }

    public synchronized void startBackgroundWorkers() {
        draining = false;
        final var threadCount = Configuration.getInstance().getBackgroundProcessingThreads();
        workerCount.set(threadCount);
        parked.set(0);
        for (int i = 0; i < threadCount; i++) {
            final var thread = new BackgroundProcessorThread(queue, inFlight, parked, workerCount, idleSignal);
            pool.execute(thread);
        }
        logger.info("Started listening for background tasks");
    }

    public boolean drain(long timeoutMillis) {
        draining = true;
        try {
            if (idleSignal.awaitIdle(this::isIdle, workerCount.get() > 0 ? timeoutMillis : 0L)) {
                stopBackgroundWorkers();
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        final var remaining = queue.size() + inFlight.get();
        logger.warning("Background queue did not drain within " + timeoutMillis + "ms; " + remaining
                + " event(s) abandoned. Their field indexes may be stale - run REINDEX on the affected"
                + " collections.");
        stopBackgroundWorkers();
        return false;
    }

    private boolean isIdle() {
        return queue.isEmpty() && inFlight.get() == 0 && parked.get() >= workerCount.get();
    }

    public int pending() {
        return queue.size() + inFlight.get();
    }

    public synchronized void stopBackgroundWorkers() {
        workerCount.set(0);
        pool = RestartablePool.shutdownAndReplace(pool, logger, "Background");
        queue.clear();
        logger.info("Stopped listening for background tasks");
    }
}
