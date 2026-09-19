package org.techhouse.ops;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.techhouse.log.Logger;

final class ShutdownRollbackWaits {
    private static final long WAIT_MS = 2000L;
    private static final Logger logger = Logger.logFor(ShutdownRollbackWaits.class);
    private static final Map<UUID, CountDownLatch> pending = new ConcurrentHashMap<>();

    private ShutdownRollbackWaits() {
    }

    static CountDownLatch register(UUID clientId) {
        final var wait = new CountDownLatch(1);
        pending.put(clientId, wait);
        return wait;
    }

    static void cancel(UUID clientId) {
        pending.remove(clientId);
    }

    static void complete(UUID clientId) {
        final var wait = pending.remove(clientId);
        if (wait != null) {
            wait.countDown();
        }
    }

    static int await(Iterable<CountDownLatch> waits) {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        var cleared = 0;
        for (final var wait : waits) {
            try {
                final var remaining = deadline - System.nanoTime();
                if (remaining > 0 && wait.await(remaining, TimeUnit.NANOSECONDS)) {
                    cleared++;
                } else {
                    logger.warning("A connection did not release its transaction before shutdown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return cleared;
            }
        }
        return cleared;
    }
}
