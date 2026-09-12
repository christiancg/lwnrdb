package org.techhouse.bckg_ops;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ScheduleOperationHelper;

public class ScheduleExecutor {
    private final Logger logger = Logger.logFor(ScheduleExecutor.class);
    private final Configuration configuration = Configuration.getInstance();
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final LinkedBlockingQueue<ScheduleRegistry.Entry> queue;
    private final LongAdder fired = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final IdleSignal idleSignal = new IdleSignal();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private volatile boolean draining;
    private ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private ScheduledExecutorService scheduler;
    private Consumer<ScheduleRegistry.Entry> dispatcher;

    public ScheduleExecutor() {
        this.queue = new LinkedBlockingQueue<>(Math.max(1, Configuration.getInstance().getScheduleQueueSize()));
    }

    public void start(Consumer<ScheduleRegistry.Entry> scheduleDispatcher) {
        draining = false;
        this.dispatcher = scheduleDispatcher;
        final var threadCount = Math.max(1, configuration.getScheduleThreads());
        for (var i = 0; i < threadCount; i++) {
            pool.execute(this::runWorker);
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final var thread = new Thread(r, "schedule-ticker");
            thread.setDaemon(true);
            return thread;
        });
        final var tick = Math.max(1L, configuration.getScheduleTickMs());
        final var refresh = Math.max(1L, configuration.getScheduleRefreshMs());
        scheduler.scheduleAtFixedRate(this::tickOnce, tick, tick, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::refreshRegistry, refresh, refresh, TimeUnit.MILLISECONDS);
        logger.info("Started the scheduler");
    }

    private void tickOnce() {
        if (draining) {
            return;
        }
        try {
            tick(System.currentTimeMillis());
        } catch (Exception e) {
            logger.error("Error while looking for due schedules: ", e);
        }
    }

    private void refreshRegistry() {
        try {
            registry.loadAll();
        } catch (Exception e) {
            logger.warning("Failed to refresh the schedule registry: " + e.getMessage());
        }
    }

    public void tick(long now) {
        for (final var entry : registry.entries()) {
            final var definition = entry.getDefinition();
            if (!isOwner(entry)) {
                continue;
            }
            if (!definition.isEnabled()) {
                continue;
            }
            final var nextRunAt = entry.getNextRunAt();
            if (nextRunAt <= 0 || now < nextRunAt) {
                continue;
            }
            entry.setNextRunAt(registry.nextRunAfter(entry, now));
            if (!running.add(entry.key())) {
                skipped.increment();
                registry.warnOnce(entry.key(), "the previous run is still executing; this occurrence is skipped");
                continue;
            }
            submit(entry);
        }
    }

    private boolean isOwner(ScheduleRegistry.Entry entry) {
        return !clusterConfig.isEnabled()
                || ownershipManager.isOwner(entry.getDbName(), ScheduleOperationHelper.ringKey(entry.getName()));
    }

    public void submit(ScheduleRegistry.Entry entry) {
        if (dispatcher == null || draining) {
            running.remove(entry.key());
            return;
        }
        while (!queue.offer(entry)) {
            final var evicted = queue.poll();
            if (evicted == null) {
                continue;
            }
            dropped.increment();
            running.remove(evicted.key());
            logger.warning("Schedule queue full; dropped the oldest queued run of '" + evicted.key() + "'");
        }
    }

    private void runWorker() {
        while (!Thread.currentThread().isInterrupted()) {
            final ScheduleRegistry.Entry entry;
            try {
                entry = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            inFlight.incrementAndGet();
            try {
                dispatcher.accept(entry);
                fired.increment();
            } catch (Exception e) {
                failed.increment();
                logger.error("Error while dispatching a scheduled run: ", e);
            } finally {
                running.remove(entry.key());
                inFlight.decrementAndGet();
                idleSignal.signal();
            }
        }
    }

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
        logger.warning("Schedule queue did not drain within " + timeoutMillis + "ms; " + pending()
                + " scheduled run(s) abandoned. They are not made up; the next occurrence fires normally.");
        stop();
        return false;
    }

    private boolean isIdle() {
        return queue.isEmpty() && inFlight.get() == 0;
    }

    public int pending() {
        return queue.size() + inFlight.get();
    }

    public void stop() {
        draining = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        pool = RestartablePool.shutdownAndReplace(pool, logger, "Schedule");
        queue.clear();
        running.clear();
        dispatcher = null;
        logger.info("Stopped the scheduler");
    }

    public void countFailure() {
        failed.increment();
    }

    public void countSkip() {
        skipped.increment();
    }

    public long getFired() {
        return fired.sum();
    }

    public long getFailed() {
        return failed.sum();
    }

    public long getSkipped() {
        return skipped.sum();
    }

    public long getDropped() {
        return dropped.sum();
    }

    public int getQueued() {
        return queue.size();
    }
}
