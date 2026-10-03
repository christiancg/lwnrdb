package org.techhouse.cluster;

import java.util.concurrent.atomic.AtomicLong;

public final class HybridClock {
    private static final int LOGICAL_BITS = 16;
    private static final long LOGICAL_MASK = (1L << LOGICAL_BITS) - 1;
    private static final long MAX_PHYSICAL = (1L << (Long.SIZE - 1 - LOGICAL_BITS)) - 1;

    private final AtomicLong last = new AtomicLong(0);

    public static long pack(long physicalMillis, long logical) {
        final var physical = Math.clamp(physicalMillis, 0L, MAX_PHYSICAL);
        return (physical << LOGICAL_BITS) | (logical & LOGICAL_MASK);
    }

    public static long physicalOf(long version) {
        return version >>> LOGICAL_BITS;
    }

    public static long logicalOf(long version) {
        return version & LOGICAL_MASK;
    }

    public long next() {
        final var now = pack(System.currentTimeMillis(), 0);
        return last.updateAndGet(previous -> Math.max(now, previous + 1));
    }

    public void observe(long version) {
        last.updateAndGet(previous -> Math.max(previous, version));
    }

    public void seed(long version) {
        observe(version);
    }

    public long current() {
        return last.get();
    }
}
