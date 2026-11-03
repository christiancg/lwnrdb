package org.techhouse.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.techhouse.config.Globals;

final class RefusalHistory {
    static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    static final int REQUIRED_REFUSALS = 2;
    static final int PRUNE_ABOVE = 1024;
    static final long MIN_SPACING_NANOS = TimeUnit.MILLISECONDS.toNanos(Globals.REFUSAL_MIN_SPACING_MS);

    private final LongSupplier clock;
    private final Map<String, Recent> refusals = new ConcurrentHashMap<>();

    private record Recent(long latest, long previous, boolean twice) {
        int countSince(long since) {
            final var latestCounts = latest - since >= 0 ? 1 : 0;
            return latestCounts + (twice && previous - since >= 0 ? 1 : 0);
        }
    }

    RefusalHistory(LongSupplier clock) {
        this.clock = clock;
    }

    void record(String identifier) {
        final var now = clock.getAsLong();
        refusals.merge(identifier, new Recent(now, now, false),
                (known, _) -> now - known.latest() < MIN_SPACING_NANOS ? known : new Recent(now, known.latest(), true));
        if (refusals.size() > PRUNE_ABOVE) {
            final var since = now - WINDOW_NANOS;
            refusals.values().removeIf(recent -> recent.countSince(since) == 0);
        }
    }

    boolean refusedRepeatedly(String identifier) {
        final var recent = refusals.get(identifier);
        return recent != null && recent.countSince(clock.getAsLong() - WINDOW_NANOS) >= REQUIRED_REFUSALS;
    }
}
