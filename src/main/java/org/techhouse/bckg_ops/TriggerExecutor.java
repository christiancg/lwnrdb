package org.techhouse.bckg_ops;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.config.ConfigKey;
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
    private volatile ScheduledExecutorService sweepScheduler;
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong scheduledBytes = new AtomicLong();
    private volatile Consumer<TriggerEvent> dispatcher;
    private final Configuration configuration = Configuration.getInstance();

    public TriggerExecutor() {
        this.queue = new LinkedBlockingQueue<>(Math.max(1, configuration.getTriggerQueueSize()));
    }

    public void submit(TriggerEvent event) {
        if (dispatcher == null || draining) {
            return;
        }
        final var queued = event.withIdsOnly();
        final var bytes = queued.queuedBytes();
        while (cannotEnqueue(queued, bytes)) {
            final var evicted = oldestQueuedFor(queued.getDbName());
            if (evicted == null) {
                if (cannotEnqueue(queued, bytes)) {
                    drop(queued, "the newest");
                }
                return;
            }
            if (dequeue(evicted)) {
                drop(evicted, "the oldest queued");
            }
        }
    }

    private boolean cannotEnqueue(TriggerEvent event, long bytes) {
        final var queuedTotal = queuedBytes.addAndGet(bytes);
        final var total = queuedTotal + scheduledBytes.get();
        final var overBudget = total > configuration.sizeValue(ConfigKey.TRIGGER_QUEUE_MAX_BYTES)
                && queuedTotal != bytes;
        if (overBudget || !queue.offer(event)) {
            queuedBytes.addAndGet(-bytes);
            return true;
        }
        return false;
    }

    private boolean dequeue(TriggerEvent event) {
        if (!queue.remove(event)) {
            return false;
        }
        queuedBytes.addAndGet(-event.queuedBytes());
        return true;
    }

    public int discardQueuedFor(String dbName, String collName) {
        var discarded = 0;
        for (final var queued : List.copyOf(queue)) {
            final var matches = dbName.equals(queued.getDbName())
                    && (collName == null || collName.equals(queued.getCollName()));
            if (matches && dequeue(queued)) {
                discarded++;
            }
        }
        return discarded;
    }

    public long getQueuedBytes() {
        return queuedBytes.get() + scheduledBytes.get();
    }

    private TriggerEvent oldestQueuedFor(String dbName) {
        for (final var queued : queue) {
            if (dbName.equals(queued.getDbName())) {
                return queued;
            }
        }
        return null;
    }

    private void drop(TriggerEvent event, String which) {
        dropped.increment();
        TriggerRunLog.markAttempt(event.getRunId(), TriggerRunStatus.DEAD, event.getAttempt(),
                "dropped: trigger queue full", 0L);
        logger.warning("Trigger queue full; dropped " + which + " trigger '" + event.getTriggerName() + "' for "
                + event.getDbName() + "|" + event.getCollName() + " (event " + event.getType() + ")");
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
        final var waiting = event.withIdsOnly();
        final var bytes = waiting.queuedBytes();
        scheduled.incrementAndGet();
        scheduledBytes.addAndGet(bytes);
        try {
            retryScheduler.schedule(() -> {
                try {
                    decrementScheduled(bytes);
                } finally {
                    submit(waiting);
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            decrementScheduled(bytes);
            submit(waiting);
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
        workers.start(pool, Math.max(1, configuration.getTriggerThreads()), queue, this::dispatch);
        logger.info("Started listening for triggers");
    }

    private void dispatch(TriggerEvent event) {
        queuedBytes.addAndGet(-event.queuedBytes());
        try {
            dispatcher.accept(event);
            fired.increment();
        } catch (Exception | Error e) {
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

    private void decrementScheduled(long bytes) {
        scheduled.updateAndGet(current -> current > 0 ? current - 1 : 0);
        scheduledBytes.updateAndGet(current -> Math.max(0L, current - bytes));
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
        scheduledBytes.set(0L);
        stopSweep();
    }

    public synchronized void stopSweep() {
        final var sweep = sweepScheduler;
        sweepScheduler = null;
        if (sweep != null) {
            sweep.shutdownNow();
        }
    }

    public synchronized void sweepPeriodically(Runnable sweep, long periodMillis) {
        if (dispatcher == null || sweepScheduler != null) {
            return;
        }
        sweepScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final var thread = new Thread(runnable, "trigger-run-sweep");
            thread.setDaemon(true);
            return thread;
        });
        sweepScheduler.scheduleWithFixedDelay(() -> runSweep(sweep), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    private void runSweep(Runnable sweep) {
        try {
            sweep.run();
        } catch (Exception | Error e) {
            logger.error("Trigger run sweep failed: ", e);
        }
    }

    public synchronized void stop() {
        workers.stopped();
        cancelPendingRetries();
        pool = RestartablePool.shutdownAndReplace(pool, logger, "Trigger");
        queue.clear();
        queuedBytes.set(0L);
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
