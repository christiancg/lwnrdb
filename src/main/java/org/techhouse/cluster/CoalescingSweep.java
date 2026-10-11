package org.techhouse.cluster;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.techhouse.log.Logger;

final class CoalescingSweep {
    interface Reconcile {
        void run() throws Exception;
    }

    private final Logger logger;
    private final String label;
    private final Reconcile reconcile;
    private final ExecutorService reconcileExecutor;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private volatile ScheduledExecutorService periodicScheduler;

    CoalescingSweep(Logger logger, String threadName, String label, Reconcile reconcile) {
        this.logger = logger;
        this.label = label;
        this.reconcile = reconcile;
        this.reconcileExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, threadName));
    }

    void startPeriodic(long intervalMs) {
        if (intervalMs <= 0) {
            return;
        }
        periodicScheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, label + "-sweep"));
        periodicScheduler.scheduleAtFixedRate(this::schedule, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    void stopPeriodic() {
        if (periodicScheduler != null) {
            periodicScheduler.shutdownNow();
            periodicScheduler = null;
        }
    }

    void stop(long awaitMillis) {
        stopPeriodic();
        reconcileExecutor.shutdown();
        try {
            if (!reconcileExecutor.awaitTermination(awaitMillis, TimeUnit.MILLISECONDS)) {
                logger.warning(label + " reconciliation did not finish within " + awaitMillis
                        + "ms; interrupting it so it stops mutating admin metadata during the shutdown");
                reconcileExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reconcileExecutor.shutdownNow();
        }
    }

    void schedule() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            reconcileExecutor.submit(() -> {
                scheduled.set(false);
                try {
                    reconcile.run();
                } catch (Exception e) {
                    logger.warning(label + " reconciliation failed: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException rejected) {
            scheduled.set(false);
            logger.info("Skipping " + label + " reconciliation: this node is shutting down");
        }
    }

    private static Thread daemon(Runnable r, String name) {
        final var t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
