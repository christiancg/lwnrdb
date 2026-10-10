package org.techhouse.listen;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class ApplyTracker {
    record ApplySnapshot(Map<String, Long> epochs) {
    }

    private static final class ApplyState {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicLong epoch = new AtomicLong();
    }

    private final Map<String, ApplyState> states = new ConcurrentHashMap<>();

    void begin(Collection<String> keys) {
        for (final var key : keys) {
            final var state = states.computeIfAbsent(key, _ -> new ApplyState());
            state.active.incrementAndGet();
            state.epoch.incrementAndGet();
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
        return new ApplySnapshot(epochs);
    }

    boolean unchangedSince(ApplySnapshot snapshot) {
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

    void clear() {
        states.clear();
    }
}
