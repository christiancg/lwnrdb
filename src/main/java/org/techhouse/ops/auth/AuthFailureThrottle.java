package org.techhouse.ops.auth;

import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.techhouse.config.Configuration;

public final class AuthFailureThrottle {
    public static final int MAX_KEYS = 10_000;
    public static final int MAX_USERNAME_CHARS = 128;
    public static final long IDLE_EXPIRY_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_SHIFT = 30;
    private static final char KEY_SEPARATOR = '|';

    private final LongSupplier clock;
    private final long baseMillis;
    private final long capMillis;
    private final Map<String, State> states;

    public AuthFailureThrottle() {
        this(System::currentTimeMillis, Configuration.getInstance().getAuthFailureDelayBaseMs(),
                Configuration.getInstance().getAuthFailureDelayMaxMs(), MAX_KEYS);
    }

    public AuthFailureThrottle(LongSupplier clock, long baseMillis, long capMillis, int maxKeys) {
        this.clock = clock;
        this.baseMillis = baseMillis;
        this.capMillis = capMillis;
        this.states = new BoundedStates(maxKeys);
    }

    public static String keyOf(String address, String username) {
        final var name = username == null ? "" : username;
        final var truncated = name.length() > MAX_USERNAME_CHARS ? name.substring(0, MAX_USERNAME_CHARS) : name;
        return address + KEY_SEPARATOR + truncated;
    }

    public synchronized long remainingDelayMillis(String key) {
        final var state = live(key);
        return state == null ? 0 : Math.max(0, state.notBefore - clock.getAsLong());
    }

    public synchronized void recordFailure(String key) {
        final var now = clock.getAsLong();
        var state = live(key);
        if (state == null) {
            state = new State();
            states.put(key, state);
        }
        final var shift = Math.min(state.failures, MAX_SHIFT);
        state.failures++;
        state.lastFailure = now;
        state.notBefore = now + delayFor(shift);
    }

    public synchronized void recordSuccess(String key) {
        states.remove(key);
    }

    public synchronized int size() {
        return states.size();
    }

    private long delayFor(int shift) {
        return baseMillis > capMillis >> shift ? capMillis : Math.min(capMillis, baseMillis << shift);
    }

    private State live(String key) {
        final var state = states.get(key);
        if (state != null && clock.getAsLong() - state.lastFailure > IDLE_EXPIRY_MILLIS) {
            states.remove(key);
            return null;
        }
        return state;
    }

    private static final class State {
        private int failures;
        private long notBefore;
        private long lastFailure;
    }

    private static final class BoundedStates extends LinkedHashMap<String, State> {
        @Serial
        private static final long serialVersionUID = 1L;
        private final int maxKeys;

        private BoundedStates(int maxKeys) {
            super(16, 0.75f, true);
            this.maxKeys = maxKeys;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, State> eldest) {
            return size() > maxKeys;
        }
    }
}
