package org.techhouse.bckg_ops;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.log.Logger;

public class BackgroundTaskManager {
    private final Logger logger = Logger.logFor(BackgroundTaskManager.class);
    private final Configuration configuration = Configuration.getInstance();
    private final BackgroundLanes lanes = new BackgroundLanes(configuration.getBackgroundProcessingThreads());
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
        submitFollowOn(op);
    }

    public void submitFollowOn(Event op) {
        inFlight.incrementAndGet();
        lanes.add(op);
    }

    public boolean submitMaintenance(Event op) {
        if (draining) {
            logger.warning("Rejecting a maintenance task during shutdown: " + op);
            return false;
        }
        lanes.addMaintenance(op);
        return true;
    }

    public synchronized void startBackgroundWorkers() {
        draining = false;
        final var threadCount = configuration.getBackgroundProcessingThreads();
        workerCount.set(threadCount);
        parked.set(0);
        for (int i = 0; i < threadCount; i++) {
            final var thread = new BackgroundProcessorThread(lanes, i, inFlight, parked, workerCount, idleSignal);
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
        final var remaining = inFlight.get();
        logger.warning("Background queue did not drain within " + timeoutMillis + "ms; " + remaining
                + " event(s) abandoned. Their field indexes may be stale - run REINDEX on the affected"
                + " collections.");
        stopBackgroundWorkers();
        return false;
    }

    private boolean isIdle() {
        return lanes.isEmpty() && lanes.maintenanceOpen() == 0 && inFlight.get() == 0
                && parked.get() >= workerCount.get();
    }

    public int pending() {
        return inFlight.get();
    }

    public int gatedPending() {
        return inFlight.get() - lanes.historyQueued();
    }

    public int queuedHistory() {
        return lanes.historyQueued();
    }

    public int maintenancePending() {
        return lanes.maintenanceOpen();
    }

    public boolean awaitCapacity(long waitMs) throws InterruptedException {
        final var maxEvents = configuration.intValue(ConfigKey.BACKGROUND_QUEUE_MAX_EVENTS);
        return gatedPending() < maxEvents || idleSignal.awaitIdle(() -> gatedPending() < maxEvents, waitMs);
    }

    public synchronized void stopBackgroundWorkers() {
        workerCount.set(0);
        pool = RestartablePool.shutdownAndReplace(pool, logger, "Background");
        inFlight.addAndGet(-lanes.discardAll());
        logger.info("Stopped listening for background tasks");
    }
}
