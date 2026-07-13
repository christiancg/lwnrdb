package org.techhouse.data;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Node-global last-write-wins version source (epoch millis, forced strictly monotonic). {@link #next()}
 * assigns the version for a locally-coordinated write; {@link #observe(long)} advances the clock past any
 * version received via replication, so a node that later coordinates writes never assigns a version below
 * one it has already seen (keeping versions monotonic across ownership handoff).
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
