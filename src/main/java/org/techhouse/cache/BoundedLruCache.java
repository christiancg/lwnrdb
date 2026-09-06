package org.techhouse.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * An LRU cache bounded by both an entry count and an approximate byte weight, used for the admin metadata
 * caches whose contents are derived from disk and can therefore be dropped at any time.
 *
 * <p>
 * Follows {@code ops.CompiledProcedureCache}'s idiom: an access-ordered {@link LinkedHashMap} guarded by a
 * {@link ReentrantLock}. The weight of a value is computed once at insert, because the weighers serialize or
 * measure their value and puts are far rarer than gets.
 */
public class BoundedLruCache<V> {
    private record Weighted<V>(V value, long bytes) {
    }

    private final Map<String, Weighted<V>> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock lock = new ReentrantLock();
    private final ToLongFunction<V> weigher;
    private final int maxEntries;
    private final long maxBytes;
    private long bytes;

    public BoundedLruCache(int maxEntries, long maxBytes, ToLongFunction<V> weigher) {
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.weigher = weigher;
    }

    public V get(String key) {
        if (maxEntries <= 0) {
            return null;
        }
        lock.lock();
        try {
            final var weighted = cache.get(key);
            return weighted == null ? null : weighted.value();
        } finally {
            lock.unlock();
        }
    }

    public void put(String key, V value) {
        if (maxEntries <= 0) {
            return;
        }
        final var weight = weigher.applyAsLong(value);
        lock.lock();
        try {
            final var previous = cache.put(key, new Weighted<>(value, weight));
            if (previous != null) {
                bytes -= previous.bytes();
            }
            bytes += weight;
            evictDownToBounds();
        } finally {
            lock.unlock();
        }
    }

    public void remove(String key) {
        lock.lock();
        try {
            final var removed = cache.remove(key);
            if (removed != null) {
                bytes -= removed.bytes();
            }
        } finally {
            lock.unlock();
        }
    }

    public void removeIf(Predicate<String> keyMatches) {
        lock.lock();
        try {
            final var iterator = cache.entrySet().iterator();
            while (iterator.hasNext()) {
                final var entry = iterator.next();
                if (keyMatches.test(entry.getKey())) {
                    bytes -= entry.getValue().bytes();
                    iterator.remove();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            cache.clear();
            bytes = 0L;
        } finally {
            lock.unlock();
        }
    }

    public long bytes() {
        lock.lock();
        try {
            return bytes;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }

    // The map is in access order, so the iterator's first entry is the least recently used. A single entry
    // heavier than maxBytes is kept rather than evicted on sight, so a cache whose bound is smaller than one
    // value still serves that value instead of thrashing it in and out on every access.
    private void evictDownToBounds() {
        final var iterator = cache.entrySet().iterator();
        while (iterator.hasNext() && (cache.size() > maxEntries || (maxBytes > 0 && bytes > maxBytes))) {
            if (cache.size() == 1) {
                return;
            }
            final var entry = iterator.next();
            bytes -= entry.getValue().bytes();
            iterator.remove();
        }
    }
}
