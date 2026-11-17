package org.techhouse.config;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;

final class ResolvedValues {
    private final Map<ConfigKey, String> raw;
    private final Object[] slots = new Object[ConfigKey.values().length];

    ResolvedValues(Map<ConfigKey, String> raw) {
        this.raw = raw;
    }

    Object resolve(ConfigKey key, Function<String, Object> parser) {
        final var cached = slots[key.ordinal()];
        if (cached != null) {
            return cached;
        }
        final var value = parser.apply(raw.get(key).trim());
        slots[key.ordinal()] = value;
        return value;
    }

    long resolveSize(ConfigKey key) {
        final var cached = slots[key.ordinal()];
        if (cached != null) {
            return (Long) cached;
        }
        final long value = HeapShares.sizeOf(key, raw.get(key));
        slots[key.ordinal()] = value;
        return value;
    }

    void forget(ConfigKey key) {
        slots[key.ordinal()] = null;
    }

    void forgetAll() {
        Arrays.fill(slots, null);
    }
}
