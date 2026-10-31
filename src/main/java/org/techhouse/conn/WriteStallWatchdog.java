package org.techhouse.conn;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public class WriteStallWatchdog {
    private static final long MIN_SCAN_INTERVAL_MS = 1000L;
    private static final int SCANS_PER_TIMEOUT = 4;
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Logger logger = Logger.logFor(WriteStallWatchdog.class);
    private final AtomicReference<ScheduledExecutorService> scanner = new AtomicReference<>();

    public void start() {
        final var scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final var thread = new Thread(runnable, "write-stall-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        if (!scanner.compareAndSet(null, scheduler)) {
            scheduler.shutdownNow();
            return;
        }
        final var interval = Math.max(MIN_SCAN_INTERVAL_MS, timeoutMillis() / SCANS_PER_TIMEOUT);
        scheduler.scheduleWithFixedDelay(this::scanQuietly, interval, interval, TimeUnit.MILLISECONDS);
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

    public int scan(long nowNanos) {
        final var timeout = timeoutMillis();
        final var aborted = new int[1];
        clientTracker.forEachOutbound((clientId, outbound) -> {
            final var stalled = outbound.stalledForMillis(nowNanos);
            if (stalled > timeout && outbound.abortStalled()) {
                logger.warning("Closing the connection from " + clientTracker.addressOf(clientId)
                        + ": writing its response made no progress for " + stalled + " ms ("
                        + ConfigKey.CLIENT_WRITE_STALL_TIMEOUT_MS.key() + "=" + timeout + ")");
                aborted[0]++;
            }
        });
        return aborted[0];
    }

    private void scanQuietly() {
        try {
            scan(System.nanoTime());
        } catch (RuntimeException e) {
            logger.error("The write stall watchdog failed a scan", e);
        }
    }

    private long timeoutMillis() {
        return configuration.longValue(ConfigKey.CLIENT_WRITE_STALL_TIMEOUT_MS);
    }
}
