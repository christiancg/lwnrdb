package org.techhouse.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

public class PartitionedMissCache {
    public static final int MAX_ENTRIES_PER_DATABASE = 256;

    private final Map<String, BoundedLruCache<Boolean>> partitions = new LinkedHashMap<>(16, 0.75f, true);
    private final int maxEntries;
    private final int maxEntriesPerDatabase;

    public PartitionedMissCache(int maxEntries) {
        this.maxEntries = maxEntries;
        this.maxEntriesPerDatabase = Math.min(MAX_ENTRIES_PER_DATABASE, maxEntries);
    }

    public synchronized boolean contains(String dbName, String key) {
        final var partition = partitions.get(dbName);
        return partition != null && partition.get(key) != null;
    }

    public synchronized void put(String dbName, String key) {
        if (maxEntries <= 0) {
            return;
        }
        partitions.computeIfAbsent(dbName, _ -> new BoundedLruCache<>(maxEntriesPerDatabase, 0L, _ -> 1L)).put(key,
                Boolean.TRUE);
        evictPartitionsDownToBound();
    }

    public synchronized void remove(String dbName, String key) {
        final var partition = partitions.get(dbName);
        if (partition == null) {
            return;
        }
        partition.remove(key);
        dropIfEmpty(dbName, partition);
    }

    public synchronized void removeIf(String dbName, Predicate<String> keyMatches) {
        final var partition = partitions.get(dbName);
        if (partition == null) {
            return;
        }
        partition.removeIf(keyMatches);
        dropIfEmpty(dbName, partition);
    }

    public synchronized void clear() {
        partitions.clear();
    }

    public synchronized int size() {
        var total = 0;
        for (var partition : partitions.values()) {
            total += partition.size();
        }
        return total;
    }

    private void dropIfEmpty(String dbName, BoundedLruCache<Boolean> partition) {
        if (partition.size() == 0) {
            partitions.remove(dbName);
        }
    }

    private void evictPartitionsDownToBound() {
        final var iterator = partitions.values().iterator();
        while (partitions.size() > 1 && size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
