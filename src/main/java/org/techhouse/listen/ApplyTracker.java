package org.techhouse.listen;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class ApplyTracker {
    record ApplySnapshot(Map<String, Long> epochs, long generation) {
    }

    private static final class ApplyState {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicLong epoch = new AtomicLong();
    }

    private final Map<String, ApplyState> states = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    void begin(Collection<String> keys) {
        for (final var key : keys) {
            states.compute(key, (_, existing) -> {
                final var state = existing == null ? new ApplyState() : existing;
                state.active.incrementAndGet();
                state.epoch.incrementAndGet();
                return state;
            });
        }
    }

    void end(Collection<String> keys) {
        for (final var key : keys) {
            final var state = states.get(key);
            if (state != null) {
                state.epoch.incrementAndGet();
                state.active.decrementAndGet();
            }
        }
    }

    ApplySnapshot snapshot(Collection<String> keys) {
        final var seenGeneration = generation.get();
        final var epochs = new HashMap<String, Long>();
        for (final var key : keys) {
            final var state = states.get(key);
            if (state == null) {
                epochs.put(key, 0L);
                continue;
            }
            final var epoch = state.epoch.get();
            if (state.active.get() > 0) {
                return null;
            }
            epochs.put(key, epoch);
        }
        return new ApplySnapshot(epochs, seenGeneration);
    }

    boolean unchangedSince(ApplySnapshot snapshot) {
        if (generation.get() != snapshot.generation()) {
            return false;
        }
        for (final var entry : snapshot.epochs().entrySet()) {
            final var state = states.get(entry.getKey());
            final var epoch = state == null ? 0L : state.epoch.get();
            if (epoch != entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    boolean anyActive(Collection<String> keys) {
        for (final var key : keys) {
            final var state = states.get(key);
            if (state != null && state.active.get() > 0) {
                return true;
            }
        }
        return false;
    }

    void forget(String key) {
        if (removeIfIdle(key)) {
            generation.incrementAndGet();
        }
    }

    void forgetPrefix(String prefix) {
        var forgotten = false;
        for (final var key : List.copyOf(states.keySet())) {
            if (key.startsWith(prefix) && removeIfIdle(key)) {
                forgotten = true;
            }
        }
        if (forgotten) {
            generation.incrementAndGet();
        }
    }

    private boolean removeIfIdle(String key) {
        final var removed = new boolean[1];
        states.computeIfPresent(key, (_, state) -> {
            if (state.active.get() > 0) {
                return state;
            }
            removed[0] = true;
            return null;
        });
        return removed[0];
    }

    void clear() {
        states.clear();
        generation.incrementAndGet();
    }
}
