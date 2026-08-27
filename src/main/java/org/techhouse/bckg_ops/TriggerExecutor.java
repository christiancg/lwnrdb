package org.techhouse.bckg_ops;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.config.Configuration;
import org.techhouse.log.Logger;

/**
 * Runs queued triggers on its own workers, deliberately not on {@link BackgroundTaskManager}'s queue: a
 * trigger runs arbitrary user code for up to {@code triggerTimeoutMs}, and sharing the index/admin queue
 * would let one slow trigger stall field-index maintenance for every collection, turning the index layer's
 * documented "eventually consistent" into indefinitely stale.
 *
 * <p>
 * The queue is bounded. On overflow the oldest queued event is dropped: an unbounded queue of retained
 * documents is a heap risk of exactly the kind {@code ConsoleCapture}'s ring buffer already guards against,
 * and dropping beats blocking the write that fired it. Drops are counted so GET_DATABASE_STATS can show them.
 */
public class TriggerExecutor {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 3L;
    private final Logger logger = Logger.logFor(TriggerExecutor.class);
    private final LinkedBlockingQueue<TriggerEvent> queue;
    private final LongAdder fired = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    // Set by start(); a null dispatcher means nothing consumes the queue yet, so submit() is a no-op that
    // does not silently accumulate events.
    private Consumer<TriggerEvent> dispatcher;

    public TriggerExecutor() {
        this.queue = new LinkedBlockingQueue<>(Math.max(1, Configuration.getInstance().getTriggerQueueSize()));
    }

    public void submit(TriggerEvent event) {
        if (dispatcher == null) {
            return;
        }
        while (!queue.offer(event)) {
            final var evicted = queue.poll();
            if (evicted == null) {
                continue;
            }
            dropped.increment();
            logger.warning(
                    "Trigger queue full; dropped the oldest queued trigger '" + evicted.getTriggerName() + "' for "
                            + evicted.getDbName() + "|" + evicted.getCollName() + " (event " + evicted.getType() + ")");
        }
    }

    public void start(Consumer<TriggerEvent> triggerDispatcher) {
        this.dispatcher = triggerDispatcher;
        final var threadCount = Math.max(1, Configuration.getInstance().getTriggerThreads());
        for (int i = 0; i < threadCount; i++) {
            pool.execute(this::runWorker);
        }
        logger.info("Started listening for triggers");
    }

    private void runWorker() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                final var event = queue.take();
                dispatcher.accept(event);
                fired.increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                failed.increment();
                logger.error("Error while dispatching a trigger: ", e);
            }
        }
    }

    public void stop() {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning("Trigger workers did not terminate within the timeout; abandoning them");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        queue.clear();
        pool = Executors.newVirtualThreadPerTaskExecutor();
        dispatcher = null;
        logger.info("Stopped listening for triggers");
    }

    public void countFailure() {
        failed.increment();
    }

    public long getFired() {
        return fired.sum();
    }

    public long getFailed() {
        return failed.sum();
    }

    public long getDropped() {
        return dropped.sum();
    }

    public int getQueued() {
        return queue.size();
    }
}
