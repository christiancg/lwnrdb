package org.techhouse.bckg_ops;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.config.Configuration;
import org.techhouse.log.Logger;
import org.techhouse.ops.TriggerDispatcher;

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
    private final LongAdder retried = new LongAdder();
    private final LongAdder deadLettered = new LongAdder();
    private final AtomicInteger inFlight = new AtomicInteger();
    // Retries waiting out their backoff. Counted in pending() so a drain does not declare the queue empty
    // while a retry is still due; one still waiting when the process stops is left to startup recovery,
    // which is safe because its durable record is still PENDING.
    private final AtomicInteger scheduled = new AtomicInteger();
    private final IdleSignal idleSignal = new IdleSignal();
    private volatile boolean draining;
    private ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private ScheduledExecutorService retryScheduler;
    // Set by start(); a null dispatcher means nothing consumes the queue yet, so submit() is a no-op that
    // does not silently accumulate events.
    private Consumer<TriggerEvent> dispatcher;

    public TriggerExecutor() {
        this.queue = new LinkedBlockingQueue<>(Math.max(1, Configuration.getInstance().getTriggerQueueSize()));
    }

    public void submit(TriggerEvent event) {
        if (dispatcher == null || draining) {
            return;
        }
        while (!queue.offer(event)) {
            final var evicted = queue.poll();
            if (evicted == null) {
                continue;
            }
            dropped.increment();
            // Overflow is deliberate back-pressure, so a dropped event is terminal: its pending record is
            // consumed too, otherwise a restart would resurrect exactly the work the queue chose to shed.
            TriggerDispatcher.consumeQuietly(evicted.getRunId(), evicted.getTriggerName());
            logger.warning(
                    "Trigger queue full; dropped the oldest queued trigger '" + evicted.getTriggerName() + "' for "
                            + evicted.getDbName() + "|" + evicted.getCollName() + " (event " + evicted.getType() + ")");
        }
    }

    /**
     * Re-queues a failed run once its backoff has elapsed. A delay of zero submits immediately. A retry
     * still waiting when the node stops is not lost: its record stays PENDING, so startup recovery replays
     * it.
     */
    public void submitAfter(TriggerEvent event, long delayMillis) {
        if (dispatcher == null || draining) {
            return;
        }
        retried.increment();
        if (delayMillis <= 0 || retryScheduler == null) {
            submit(event);
            return;
        }
        scheduled.incrementAndGet();
        try {
            retryScheduler.schedule(() -> {
                try {
                    submit(event);
                } finally {
                    scheduled.decrementAndGet();
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            scheduled.decrementAndGet();
            submit(event);
        }
    }

    public void countDeadLetter() {
        deadLettered.increment();
    }

    public void start(Consumer<TriggerEvent> triggerDispatcher) {
        draining = false;
        this.dispatcher = triggerDispatcher;
        retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final var thread = new Thread(runnable, "trigger-retry-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        final var threadCount = Math.max(1, Configuration.getInstance().getTriggerThreads());
        for (int i = 0; i < threadCount; i++) {
            pool.execute(this::runWorker);
        }
        logger.info("Started listening for triggers");
    }

    private void runWorker() {
        while (!Thread.currentThread().isInterrupted()) {
            final TriggerEvent event;
            try {
                event = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            inFlight.incrementAndGet();
            try {
                dispatcher.accept(event);
                fired.increment();
            } catch (Exception e) {
                failed.increment();
                logger.error("Error while dispatching a trigger: ", e);
            } finally {
                inFlight.decrementAndGet();
                idleSignal.signal();
            }
        }
    }

    /**
     * Lets the queued triggers run before stopping, up to {@code timeoutMillis}. A trigger abandoned here is
     * not lost - its pending run record is on disk and replays at the next startup - but finishing now is far
     * better than replaying later, and it is the only way a node being decommissioned runs them at all.
     *
     * @return true when everything queued ran within the budget
     */
    public boolean drain(long timeoutMillis) {
        draining = true;
        try {
            if (idleSignal.awaitIdle(this::isIdle, timeoutMillis)) {
                stop();
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.warning("Trigger queue did not drain within " + timeoutMillis + "ms; " + pending()
                + " trigger run(s) left pending. They replay when this node starts again.");
        stop();
        return false;
    }

    private boolean isIdle() {
        return queue.isEmpty() && inFlight.get() == 0 && scheduled.get() == 0;
    }

    public int pending() {
        return queue.size() + inFlight.get() + scheduled.get();
    }

    public void stop() {
        final var scheduler = retryScheduler;
        retryScheduler = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        scheduled.set(0);
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

    public long getRetried() {
        return retried.sum();
    }

    public long getDeadLettered() {
        return deadLettered.sum();
    }

    public int getQueued() {
        return queue.size();
    }
}
