package org.techhouse.cache;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.config.Globals;

public class IndexIdCounts {
    public static final long UNKNOWN = -1L;
    private static final char KEY_SEPARATOR = Globals.COLL_IDENTIFIER_SEPARATOR;

    private final Map<String, Long> counts = new ConcurrentHashMap<>();
    private final AtomicLong forgets = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();

    @FunctionalInterface
    public interface Counter {
        long count() throws IOException;
    }

    private static String keyOf(String dbName, String collName, String indexIdentifier) {
        return Cache.getCollectionIdentifier(dbName, collName) + KEY_SEPARATOR + indexIdentifier;
    }

    public long count(String dbName, String collName, String indexIdentifier, Counter counter) throws IOException {
        final var key = keyOf(dbName, collName, indexIdentifier);
        final var known = counts.get(key);
        if (known != null) {
            return known;
        }
        final var epoch = forgets.get();
        final var counted = counter.count();
        if (counted >= 0) {
            lock.lock();
            try {
                if (forgets.get() == epoch) {
                    counts.putIfAbsent(key, counted);
                }
            } finally {
                lock.unlock();
            }
        }
        return counted;
    }

    public long known(String dbName, String collName, String indexIdentifier) {
        final var known = counts.get(keyOf(dbName, collName, indexIdentifier));
        return known == null ? UNKNOWN : known;
    }

    public void adjust(String dbName, String collName, String indexIdentifier, long added, long removed) {
        if (added < 0 || removed < 0) {
            forget(dbName, collName, indexIdentifier);
            return;
        }
        if (added != removed) {
            counts.computeIfPresent(keyOf(dbName, collName, indexIdentifier),
                    (_, count) -> count + added - removed < 0 ? null : count + added - removed);
        }
    }

    public void forget(String dbName, String collName, String indexIdentifier) {
        lock.lock();
        try {
            forgets.incrementAndGet();
            counts.remove(keyOf(dbName, collName, indexIdentifier));
        } finally {
            lock.unlock();
        }
    }

    public void forgetField(String dbName, String collName, String fieldName) {
        forgetByPrefix(keyOf(dbName, collName, fieldName + Globals.COLL_IDENTIFIER_SEPARATOR));
    }

    public void forgetCollection(String dbName, String collName) {
        forgetByPrefix(Cache.getCollectionIdentifier(dbName, collName) + KEY_SEPARATOR);
    }

    public void forgetDatabase(String dbName) {
        forgetByPrefix(dbName + Globals.COLL_IDENTIFIER_SEPARATOR);
    }

    private void forgetByPrefix(String prefix) {
        lock.lock();
        try {
            forgets.incrementAndGet();
            counts.keySet().removeIf(key -> key.startsWith(prefix));
        } finally {
            lock.unlock();
        }
    }
}
