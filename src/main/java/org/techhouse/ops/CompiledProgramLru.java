package org.techhouse.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;

final class CompiledProgramLru<V> {
    private static final long ESTIMATED_HEAP_BYTES_PER_SOURCE_CHAR = 25;
    private final Configuration configuration = Configuration.getInstance();
    private final Map<String, Weighted<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock lock = new ReentrantLock();
    private long weightedBytes;

    private record Weighted<V>(V value, long bytes) {
    }

    boolean isDisabled() {
        return configuration.getProcedureCacheSize() <= 0;
    }

    V get(String key, Predicate<V> stillValid) {
        lock.lock();
        try {
            final var cached = entries.get(key);
            return cached != null && stillValid.test(cached.value()) ? cached.value() : null;
        } finally {
            lock.unlock();
        }
    }

    V admit(String key, V compiled, String source, Predicate<V> stillValid) {
        final var bytes = (long) source.length() * ESTIMATED_HEAP_BYTES_PER_SOURCE_CHAR;
        final var maxBytes = configuration.sizeValue(ConfigKey.PROCEDURE_CACHE_MAX_BYTES);
        final var maxEntries = configuration.getProcedureCacheSize();
        lock.lock();
        try {
            final var raced = entries.get(key);
            if (raced != null && stillValid.test(raced.value())) {
                return raced.value();
            }
            if (bytes > maxBytes) {
                return compiled;
            }
            remove(key);
            entries.put(key, new Weighted<>(compiled, bytes));
            weightedBytes += bytes;
            evictDownTo(maxEntries, maxBytes);
            return compiled;
        } finally {
            lock.unlock();
        }
    }

    void removeIf(Predicate<String> keyMatches) {
        lock.lock();
        try {
            final var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                final var entry = iterator.next();
                if (keyMatches.test(entry.getKey())) {
                    weightedBytes -= entry.getValue().bytes();
                    iterator.remove();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    long weightedBytes() {
        lock.lock();
        try {
            return weightedBytes;
        } finally {
            lock.unlock();
        }
    }

    private void remove(String key) {
        final var previous = entries.remove(key);
        if (previous != null) {
            weightedBytes -= previous.bytes();
        }
    }

    private void evictDownTo(int maxEntries, long maxBytes) {
        final var eldest = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || weightedBytes > maxBytes) && eldest.hasNext()) {
            weightedBytes -= eldest.next().getValue().bytes();
            eldest.remove();
        }
    }
}
