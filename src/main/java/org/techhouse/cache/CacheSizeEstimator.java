package org.techhouse.cache;

import java.util.List;
import java.util.Map;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;

// Sizes the memory-managed user cache from its own entry counts rather than measured JVM heap, which
// is what MemoryManagement enforces maxMemory against.
final class CacheSizeEstimator {
    private static final long ESTIMATED_PK_ENTRY_BYTES = 96L;
    private static final long ESTIMATED_FIELD_ENTRY_OVERHEAD_BYTES = 64L;

    private CacheSizeEstimator() {
    }

    static long estimatePkIndexSize(List<PkIndexEntry> entries) {
        if (entries == null)
            return 0L;
        return (long) entries.size() * ESTIMATED_PK_ENTRY_BYTES;
    }

    static long estimateCollectionSize(Map<String, DbEntry> entries) {
        if (entries == null)
            return 0L;
        long total = 0L;
        for (var e : entries.values()) {
            total += e.byteSize();
        }
        return total;
    }

    static long estimateFieldIndexSize(List<FieldIndexEntry<?>> entries) {
        if (entries == null)
            return 0L;
        long total = 0L;
        for (var e : entries) {
            final var value = e.getValue();
            final var valueLen = value == null ? 0 : value.toString().length() * 2L;
            final var ids = e.getIds();
            final var idsLen = ids == null ? 0L : ids.size() * ESTIMATED_FIELD_ENTRY_OVERHEAD_BYTES;
            total += valueLen + idsLen + ESTIMATED_FIELD_ENTRY_OVERHEAD_BYTES;
        }
        return total;
    }
}
