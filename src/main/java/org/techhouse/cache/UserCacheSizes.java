package org.techhouse.cache;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;

final class UserCacheSizes {
    private final Map<String, AtomicLong> collectionBytes = new ConcurrentHashMap<>();
    private final Map<String, Long> pkBytesPerEntry = new ConcurrentHashMap<>();
    private final Map<String, Long> fieldIndexBytesPerEntry = new ConcurrentHashMap<>();

    void trackPut(String collId, DbEntry previous, DbEntry added) {
        final var delta = CacheSizeEstimator.estimateCollectionEntry(added)
                - (previous == null ? 0L : CacheSizeEstimator.estimateCollectionEntry(previous));
        collectionBytes.computeIfAbsent(collId, _ -> new AtomicLong()).addAndGet(delta);
    }

    void trackRemoved(String collId, DbEntry removed) {
        if (removed != null) {
            collectionBytes.computeIfAbsent(collId, _ -> new AtomicLong())
                    .addAndGet(-CacheSizeEstimator.estimateCollectionEntry(removed));
        }
    }

    long trackedBytes(String collId, Map<String, DbEntry> coll) {
        final var tracked = collectionBytes.get(collId);
        if (tracked != null) {
            return tracked.get();
        }
        if (coll == null) {
            return 0L;
        }
        final var measured = CacheSizeEstimator.estimateCollectionSize(coll);
        final var raced = collectionBytes.putIfAbsent(collId, new AtomicLong(measured));
        return raced != null ? raced.get() : measured;
    }

    void resetCollectionBytes(String collId, long bytes) {
        collectionBytes.put(collId, new AtomicLong(bytes));
    }

    void forgetCollectionDocuments(String collId) {
        collectionBytes.remove(collId);
    }

    void recordPkBytesPerEntry(String collId, long bytesPerEntry) {
        pkBytesPerEntry.put(collId, bytesPerEntry);
    }

    long pkIndexBytes(String collId, int entryCount) {
        final var recorded = pkBytesPerEntry.get(collId);
        return entryCount * (recorded != null ? recorded : CacheSizeEstimator.pkIndexBytesPerEntry(List.of()));
    }

    void forgetPkIndex(String collId) {
        pkBytesPerEntry.remove(collId);
    }

    long measureFieldIndex(String collId, String indexKey, List<FieldIndexEntry<?>> entries) {
        final var measured = CacheSizeEstimator.estimateFieldIndexSize(entries);
        rememberFieldIndex(collId, indexKey, measured, entries.size());
        return measured;
    }

    void rememberFieldIndex(String collId, String indexKey, long bytes, int entryCount) {
        if (entryCount > 0) {
            fieldIndexBytesPerEntry.put(fieldIndexKey(collId, indexKey), bytes / entryCount);
        }
    }

    void adjustFieldIndex(String collId, String indexKey, int previousCount, int entryCount, long deltaBytes) {
        final var total = rememberedFieldIndexBytes(collId, indexKey, previousCount) + deltaBytes;
        rememberFieldIndex(collId, indexKey, Math.max(0L, total), entryCount);
    }

    long rememberedFieldIndexBytes(String collId, String indexKey, int entryCount) {
        final var remembered = fieldIndexBytesPerEntry.get(fieldIndexKey(collId, indexKey));
        return remembered != null
                ? entryCount * remembered
                : CacheSizeEstimator.estimateFieldIndexSizeByCount(entryCount);
    }

    void forgetFieldIndex(String collId, String indexKey) {
        fieldIndexBytesPerEntry.remove(fieldIndexKey(collId, indexKey));
    }

    void forgetFieldIndexAllTypes(String collId, String fieldName) {
        final var fieldKey = fieldIndexKey(collId, fieldName);
        fieldIndexBytesPerEntry.keySet()
                .removeIf(key -> key.equals(fieldKey) || key.startsWith(fieldKey + Globals.COLL_IDENTIFIER_SEPARATOR));
    }

    void forgetCollection(String collId) {
        collectionBytes.remove(collId);
        pkBytesPerEntry.remove(collId);
        forgetByPrefix(collId + Globals.COLL_IDENTIFIER_SEPARATOR);
    }

    void forgetByPrefix(String prefix) {
        collectionBytes.keySet().removeIf(key -> key.startsWith(prefix));
        pkBytesPerEntry.keySet().removeIf(key -> key.startsWith(prefix));
        fieldIndexBytesPerEntry.keySet().removeIf(key -> key.startsWith(prefix));
    }

    long rememberedFootprint(Map<String, List<PkIndexEntry>> pkIndexes, Map<String, Map<String, DbEntry>> collections,
            Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexes) {
        var total = 0L;
        for (final var pk : pkIndexes.entrySet()) {
            total += pkIndexBytes(pk.getKey(), pk.getValue().size());
        }
        for (final var collection : collections.entrySet()) {
            total += trackedBytes(collection.getKey(), collection.getValue());
        }
        for (final var collection : fieldIndexes.entrySet()) {
            for (final var index : collection.getValue().entrySet()) {
                total += rememberedFieldIndexBytes(collection.getKey(), index.getKey(), index.getValue().size());
            }
        }
        return total;
    }

    private static String fieldIndexKey(String collId, String indexKey) {
        return collId + Globals.COLL_IDENTIFIER_SEPARATOR + indexKey;
    }
}
