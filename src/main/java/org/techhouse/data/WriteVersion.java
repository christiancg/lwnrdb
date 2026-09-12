package org.techhouse.data;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Every replicated version must be observed here, or a node that later coordinates writes assigns one below
 * a version it has already seen and last-write-wins resolves backwards.
 */
public final class WriteVersion {
    private static final AtomicLong last = new AtomicLong(0);

    private WriteVersion() {
    }

    public static long next() {
        final var now = System.currentTimeMillis();
        return last.updateAndGet(prev -> Math.max(now, prev + 1));
    }

    public static void observe(long version) {
        last.updateAndGet(prev -> Math.max(prev, version));
    }
}
