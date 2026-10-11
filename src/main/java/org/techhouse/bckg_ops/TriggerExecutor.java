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
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.log.Logger;
import org.techhouse.ops.TriggerRunLog;

public class TriggerExecutor {
    private final Logger logger = Logger.logFor(TriggerExecutor.class);
    private final LinkedBlockingQueue<TriggerEvent> queue;
    private final LongAdder fired = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder retried = new LongAdder();
    private final LongAdder deadLettered = new LongAdder();
    private final AtomicInteger scheduled = new AtomicInteger();
    private final IdleSignal.Workers<TriggerEvent> workers = new IdleSignal.Workers<>();
    private volatile boolean draining;
    private volatile ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private volatile ScheduledExecutorService retryScheduler;
    private volatile Consumer<TriggerEvent> dispatcher;

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
            TriggerRunLog.markAttempt(evicted.getRunId(), TriggerRunStatus.DEAD, evicted.getAttempt(),
                    "dropped: trigger queue full", 0L);
            logger.warning(
                    "Trigger queue full; dropped the oldest queued trigger '" + evicted.getTriggerName() + "' for "
                            + evicted.getDbName() + "|" + evicted.getCollName() + " (event " + evicted.getType() + ")");
        }
    }

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
                    decrementScheduled();
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            decrementScheduled();
            submit(event);
        }
    }

    public void countDeadLetter() {
        deadLettered.increment();
    }

    public synchronized void start(Consumer<TriggerEvent> triggerDispatcher) {
        draining = false;
        this.dispatcher = triggerDispatcher;
        retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final var thread = new Thread(runnable, "trigger-retry-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        workers.start(pool, Math.max(1, Configuration.getInstance().getTriggerThreads()), queue, this::dispatch);
        logger.info("Started listening for triggers");
    }

    private void dispatch(TriggerEvent event) {
        try {
            dispatcher.accept(event);
            fired.increment();
        } catch (Exception e) {
            failed.increment();
            logger.error("Error while dispatching a trigger: ", e);
        }
    }

    public boolean drain(long timeoutMillis) {
        draining = true;
        cancelPendingRetries();
        try {
            if (workers.awaitIdle(() -> queue.isEmpty() && scheduled.get() <= 0, timeoutMillis)) {
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

    private void decrementScheduled() {
        scheduled.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    public int pending() {
        return queue.size() + workers.inFlight() + scheduled.get();
    }

    private void cancelPendingRetries() {
        final var scheduler = retryScheduler;
        retryScheduler = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        scheduled.set(0);
    }

    public synchronized void stop() {
        workers.stopped();
        cancelPendingRetries();
        pool = RestartablePool.shutdownAndReplace(pool, logger, "Trigger");
        queue.clear();
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
