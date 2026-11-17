package org.techhouse.ops.snapshot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public class SnapshotSweeper {
    private static final long MIN_SCAN_INTERVAL_MS = 1000L;
    private static final int SCANS_PER_TIMEOUT = 4;
    private final SnapshotRegistry registry = IocContainer.get(SnapshotRegistry.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Logger logger = Logger.logFor(SnapshotSweeper.class);
    private final AtomicReference<ScheduledExecutorService> scanner = new AtomicReference<>();

    public void start() {
        final var scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final var thread = new Thread(runnable, "snapshot-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        if (!scanner.compareAndSet(null, scheduler)) {
            scheduler.shutdownNow();
            return;
        }
        final var interval = Math.max(MIN_SCAN_INTERVAL_MS,
                configuration.longValue(ConfigKey.SNAPSHOT_IDLE_TIMEOUT_MS) / SCANS_PER_TIMEOUT);
        scheduler.scheduleWithFixedDelay(this::sweepQuietly, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        final var running = scanner.getAndSet(null);
        if (running != null) {
            running.shutdownNow();
        }
    }

    public boolean isRunning() {
        return scanner.get() != null;
    }

    private void sweepQuietly() {
        try {
            registry.expireIdle(System.nanoTime());
        } catch (RuntimeException e) {
            logger.error("Error while releasing idle snapshots", e);
        }
    }
}
