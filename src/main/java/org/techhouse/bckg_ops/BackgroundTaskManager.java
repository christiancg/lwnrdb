package org.techhouse.bckg_ops;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.config.Configuration;
import org.techhouse.log.Logger;

public class BackgroundTaskManager {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 3L;
    private final Logger logger = Logger.logFor(BackgroundTaskManager.class);
    private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final IdleSignal idleSignal = new IdleSignal();
    private volatile boolean draining;
    private ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public void submitBackgroundTask(Event op) {
        if (draining) {
            logger.warning("Rejecting a background task during shutdown: " + op);
            return;
        }
        queue.add(op);
    }

    public void startBackgroundWorkers() {
        draining = false;
        final var threadCount = Configuration.getInstance().getBackgroundProcessingThreads();
        for (int i = 0; i < threadCount; i++) {
            final var thread = new BackgroundProcessorThread(queue, inFlight, idleSignal);
            pool.execute(thread);
        }
        logger.info("Started listening for background tasks");
    }

    /**
     * Lets the workers finish what is queued before stopping, up to {@code timeoutMillis}. Called on the
     * shutdown path: an index event dropped here is a field index left stale with no pending-write overlay to
     * compensate after the restart, because that overlay lives only in memory.
     *
     * @return true when the queue drained fully within the budget
     */
    public boolean drain(long timeoutMillis) {
        draining = true;
        try {
            if (idleSignal.awaitIdle(this::isIdle, timeoutMillis)) {
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
        return queue.isEmpty() && inFlight.get() == 0;
    }

    public int pending() {
        return queue.size() + inFlight.get();
    }

    /**
     * Interrupts the running workers, drops any pending events and replaces the pool so the manager can be
     * started again. The workers block on {@code queue.take()}; {@code shutdownNow} interrupts that wait,
     * which exits {@link BackgroundProcessorThread#run()}.
     */
    public void stopBackgroundWorkers() {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning("Background workers did not terminate within the timeout; abandoning them");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        queue.clear();
        pool = Executors.newVirtualThreadPerTaskExecutor();
        logger.info("Stopped listening for background tasks");
    }
}
