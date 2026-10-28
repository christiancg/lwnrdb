package org.techhouse.cache;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.techhouse.config.Configuration;

final class OverBudgetSignal {
    static final long CHECK_INTERVAL_NANOS = 100_000_000L;

    private final Configuration configuration;
    private final LongSupplier clock;
    private final AtomicLong nextCheckNanos;
    private volatile Runnable listener;

    OverBudgetSignal(Configuration configuration) {
        this(configuration, System::nanoTime);
    }

    OverBudgetSignal(Configuration configuration, LongSupplier clock) {
        this.configuration = configuration;
        this.clock = clock;
        this.nextCheckNanos = new AtomicLong(clock.getAsLong());
    }

    void listen(Runnable listener) {
        this.listener = listener;
        nextCheckNanos.set(clock.getAsLong());
    }

    void signalIfOver(LongSupplier footprintBytes) {
        final var current = listener;
        if (current == null || configuration.isCachingDisabled() || configuration.isCacheUnlimited()
                || !isDueForCheck()) {
            return;
        }
        if (footprintBytes.getAsLong() > configuration.getMaxMemoryBytes()) {
            current.run();
        }
    }

    private boolean isDueForCheck() {
        final var now = clock.getAsLong();
        final var due = nextCheckNanos.get();
        return now - due >= 0 && nextCheckNanos.compareAndSet(due, now + CHECK_INTERVAL_NANOS);
    }
}
