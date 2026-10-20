package org.techhouse.cache;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.techhouse.cache.heap.DocumentHeapSizer;
import org.techhouse.cache.heap.HeapLayout;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;

public final class CacheSizeEstimator {
    public static final int PAGE_SAMPLE_SIZE = 32;
    private static final int TYPICAL_ID_LENGTH = 36;
    private static final int COLLECTION_TABLE_SLOTS_PER_ENTRY = 2;
    private static final String TYPICAL_ID = "x".repeat(TYPICAL_ID_LENGTH);
    private static final String SHORTEST_ID = "x";
    private static final int SHORTEST_LIVE_PK_ROW_BYTES = 11;
    private static final int TYPICAL_INDEXED_ID_BYTES = TYPICAL_ID_LENGTH + 1;
    private static final Class<?> HASH_MAP_NODE = HeapLayout.jdkClass("java.util.HashMap$Node");
    private static final Class<?> CONCURRENT_HASH_MAP_NODE = HeapLayout
            .jdkClass("java.util.concurrent.ConcurrentHashMap$Node");

    private CacheSizeEstimator() {
    }

    public static long estimateEntry(DbEntry entry) {
        final var memoized = entry.getCachedHeapBytes();
        if (memoized >= 0) {
            return memoized;
        }
        return entry.memoizeHeapBytes(estimateEntry(entry, HeapLayout.current()));
    }

    public static long estimateEntry(DbEntry entry, HeapLayout layout) {
        return layout.shallowSize(entry.getClass()) + DocumentHeapSizer.sizeOf(entry.getData(), layout);
    }

    public static long estimateCollectionEntry(DbEntry entry) {
        return estimateEntry(entry) + collectionSlotSize(HeapLayout.current());
    }

    private static long collectionSlotSize(HeapLayout layout) {
        return layout.shallowSize(CONCURRENT_HASH_MAP_NODE)
                + (long) COLLECTION_TABLE_SLOTS_PER_ENTRY * layout.referenceBytes();
    }

    public static long estimatePageHeap(Collection<DbEntry> pageDocuments) {
        if (pageDocuments == null || pageDocuments.isEmpty()) {
            return 0L;
        }
        var sampled = 0;
        var sampledBytes = 0L;
        for (final var entry : pageDocuments) {
            if (sampled == PAGE_SAMPLE_SIZE) {
                break;
            }
            sampledBytes += estimateEntry(entry);
            sampled++;
        }
        return sampledBytes * pageDocuments.size() / sampled;
    }

    public static long pkIndexBytesPerEntry(List<PkIndexEntry> entries) {
        return pkIndexBytesPerEntry(entries, HeapLayout.current());
    }

    public static long pkIndexBytesPerEntry(List<PkIndexEntry> entries, HeapLayout layout) {
        if (entries == null || entries.isEmpty()) {
            return pkEntrySize(TYPICAL_ID, layout);
        }
        var total = 0L;
        for (final var entry : entries) {
            total += pkEntrySize(entry.getValue(), layout);
        }
        return total / entries.size();
    }

    private static long pkEntrySize(String id, HeapLayout layout) {
        return layout.referenceBytes() + layout.shallowSize(PkIndexEntry.class) + layout.stringSize(id);
    }

    public static long estimatePkIndexSize(List<PkIndexEntry> entries) {
        return estimatePkIndexSize(entries, HeapLayout.current());
    }

    public static long estimatePkIndexSize(List<PkIndexEntry> entries, HeapLayout layout) {
        if (entries == null)
            return 0L;
        return entries.size() * pkIndexBytesPerEntry(entries, layout);
    }

    public static long estimatePkIndexSizeFromFileBytes(long fileBytes) {
        final var mostLiveRows = Math.ceilDiv(fileBytes, SHORTEST_LIVE_PK_ROW_BYTES);
        return mostLiveRows * pkEntrySize(SHORTEST_ID, HeapLayout.current()) + fileBytes;
    }

    public static long estimateFieldIndexSizeFromFileBytes(long fileBytes) {
        final var ids = Math.ceilDiv(fileBytes, TYPICAL_INDEXED_ID_BYTES);
        return estimateFieldIndexSizeByCount((int) Math.min(Integer.MAX_VALUE, ids));
    }

    public static long estimateCollectionSize(Map<String, DbEntry> entries) {
        if (entries == null)
            return 0L;
        long total = 0L;
        for (var e : entries.values()) {
            total += estimateCollectionEntry(e);
        }
        return total;
    }

    public static long estimateFieldIndexSizeByCount(int entryCount) {
        return estimateFieldIndexSizeByCount(entryCount, HeapLayout.current());
    }

    public static long estimateFieldIndexSizeByCount(int entryCount, HeapLayout layout) {
        final var singleIdEntry = fieldEntryOverhead(layout) + layout.presizedHashTableSize(1)
                + idSize(TYPICAL_ID, layout) + layout.shallowSize(Double.class);
        return entryCount * singleIdEntry;
    }

    public static long estimateFieldIndexSize(List<FieldIndexEntry<?>> entries) {
        return estimateFieldIndexSize(entries, HeapLayout.current());
    }

    public static long estimateFieldIndexSize(List<FieldIndexEntry<?>> entries, HeapLayout layout) {
        if (entries == null)
            return 0L;
        long total = 0L;
        for (var e : entries) {
            final var ids = e.getIds();
            if (ids == null) {
                total += fieldEntryOverhead(layout) + DocumentHeapSizer.indexedValueSize(e.getValue(), layout);
                continue;
            }
            total += fieldEntryShellSize(e.getValue(), ids.size(), layout);
            for (final var id : ids) {
                total += idSize(id, layout);
            }
        }
        return total;
    }

    public static long fieldEntryShellSize(Object value, int idCount) {
        return fieldEntryShellSize(value, idCount, HeapLayout.current());
    }

    private static long fieldEntryShellSize(Object value, int idCount, HeapLayout layout) {
        return fieldEntryOverhead(layout) + DocumentHeapSizer.indexedValueSize(value, layout)
                + layout.presizedHashTableSize(idCount);
    }

    public static long fieldIndexIdSize(String id) {
        return idSize(id, HeapLayout.current());
    }

    public static long estimateFieldIndexLookupSize(int ids) {
        return estimateFieldIndexLookupSize(ids, HeapLayout.current());
    }

    public static long estimateFieldIndexLookupSize(int ids, HeapLayout layout) {
        return layout.shallowSize(FieldIndexLookup.class) + layout.shallowSize(HashMap.class)
                + layout.hashTableSize(ids)
                + (long) ids * (layout.shallowSize(HASH_MAP_NODE) + layout.shallowSize(FieldIndexLookup.Located.class));
    }

    private static long fieldEntryOverhead(HeapLayout layout) {
        return layout.referenceBytes() + layout.shallowSize(FieldIndexEntry.class) + layout.shallowSize(HashSet.class)
                + layout.shallowSize(HashMap.class);
    }

    private static long idSize(String id, HeapLayout layout) {
        return layout.shallowSize(HASH_MAP_NODE) + layout.stringSize(id);
    }
}
