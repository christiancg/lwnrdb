package org.techhouse.cache;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;

final class UserCacheSizes {
    static final long DRIFT_FRACTION = 10L;

    private final Footprint footprint = new Footprint();
    private final Map<String, SizeCell> fieldIndexCells = new ConcurrentHashMap<>();
    private final Map<String, Drift> fieldIndexDrift = new ConcurrentHashMap<>();
    private final Map<String, RefusedPk> refusedPk = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> refusedFieldIndexes = new ConcurrentHashMap<>();

    record RefusedPk(long bytesPerEntry, long rows) {
        long bytes() {
            return bytesPerEntry * rows;
        }
    }

    private record Drift(long measuredIds, long changedIds) {
        boolean isDue() {
            return changedIds >= Math.max(1L, measuredIds / DRIFT_FRACTION);
        }
    }

    Footprint footprint() {
        return footprint;
    }

    long measureFieldIndex(String collId, String indexKey, List<FieldIndexEntry<?>> entries) {
        final var measured = CacheSizeEstimator.estimateFieldIndexSize(entries) - SharedIdLists.sharedBytesOf(entries);
        final var cell = fieldIndexCells.get(fieldIndexKey(collId, indexKey));
        if (cell != null) {
            cell.set(measured);
            rememberDrift(collId, indexKey, entries);
        }
        return measured;
    }

    void rememberMeasuredFieldIndex(String collId, String indexKey, long bytes, List<FieldIndexEntry<?>> entries) {
        cellOf(collId, indexKey).set(bytes);
        rememberDrift(collId, indexKey, entries);
    }

    private void rememberDrift(String collId, String indexKey, List<FieldIndexEntry<?>> entries) {
        var ids = 0L;
        for (final var entry : entries) {
            final var entryIds = entry.getIds();
            ids += entryIds == null ? 0 : entryIds.size();
        }
        fieldIndexDrift.put(fieldIndexKey(collId, indexKey), new Drift(ids, 0L));
    }

    private SizeCell cellOf(String collId, String indexKey) {
        return fieldIndexCells.computeIfAbsent(fieldIndexKey(collId, indexKey),
                _ -> footprint.newCell(AccessKind.FIELD_INDEX));
    }

    boolean needsMeasure(String collId, String indexKey) {
        final var drift = fieldIndexDrift.get(fieldIndexKey(collId, indexKey));
        return drift == null || drift.isDue();
    }

    void adjustFieldIndex(String collId, String indexKey, int previousCount, int entryCount, long deltaBytes,
            int changedIds) {
        final var total = rememberedFieldIndexBytes(collId, indexKey, previousCount) + deltaBytes;
        cellOf(collId, indexKey).set(Math.max(0L, total));
        final var changed = Math.abs((long) entryCount - previousCount) + changedIds;
        fieldIndexDrift.computeIfPresent(fieldIndexKey(collId, indexKey),
                (_, drift) -> new Drift(drift.measuredIds(), drift.changedIds() + changed));
    }

    long rememberedFieldIndexBytes(String collId, String indexKey, int entryCount) {
        final var cell = fieldIndexCells.get(fieldIndexKey(collId, indexKey));
        return cell != null ? cell.bytes() : CacheSizeEstimator.estimateFieldIndexSizeByCount(entryCount);
    }

    void forgetFieldIndex(String collId, String indexKey) {
        final var key = fieldIndexKey(collId, indexKey);
        retire(fieldIndexCells.remove(key));
        fieldIndexDrift.remove(key);
    }

    void forgetFieldIndexAllTypes(String collId, String fieldName) {
        final var fieldKey = fieldIndexKey(collId, fieldName);
        final var typePrefix = fieldKey + Globals.COLL_IDENTIFIER_SEPARATOR;
        forgetWhere(key -> key.equals(fieldKey) || key.startsWith(typePrefix));
    }

    void forgetCollection(String collId) {
        refusedPk.remove(collId);
        refusedFieldIndexes.remove(collId);
        forgetByPrefix(collId + Globals.COLL_IDENTIFIER_SEPARATOR);
    }

    void forgetByPrefix(String prefix) {
        refusedPk.keySet().removeIf(key -> key.startsWith(prefix));
        refusedFieldIndexes.keySet().removeIf(key -> key.startsWith(prefix));
        forgetWhere(key -> key.startsWith(prefix));
    }

    private void forgetWhere(Predicate<String> forgotten) {
        for (final var key : List.copyOf(fieldIndexCells.keySet())) {
            if (forgotten.test(key)) {
                retire(fieldIndexCells.remove(key));
            }
        }
        fieldIndexDrift.keySet().removeIf(forgotten);
    }

    private static void retire(SizeCell cell) {
        if (cell != null) {
            cell.retire();
        }
    }

    void refusePk(String collId, long bytesPerEntry, long rows) {
        refusedPk.put(collId, new RefusedPk(bytesPerEntry, rows));
    }

    RefusedPk refusedPk(String collId) {
        return refusedPk.get(collId);
    }

    void clearRefusedPk(String collId) {
        refusedPk.remove(collId);
    }

    void refuseFieldIndex(String collId, String indexKey, long bytes) {
        refusedFieldIndexes.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>()).put(indexKey, bytes);
    }

    Long refusedFieldIndexBytes(String collId, String indexKey) {
        final var refused = refusedFieldIndexes.get(collId);
        return refused == null ? null : refused.get(indexKey);
    }

    Set<String> fieldsWithRefusedTypes(String collId) {
        final var refused = refusedFieldIndexes.get(collId);
        if (refused == null || refused.isEmpty()) {
            return Set.of();
        }
        final var fields = new HashSet<String>();
        for (final var indexKey : refused.keySet()) {
            fields.add(CacheableResource.indexedFieldOf(indexKey));
        }
        return fields;
    }

    void clearRefusedFieldIndex(String collId, String indexKey) {
        final var refused = refusedFieldIndexes.get(collId);
        if (refused != null) {
            refused.remove(indexKey);
        }
    }

    void clearRefusedField(String collId, String fieldName) {
        final var refused = refusedFieldIndexes.get(collId);
        if (refused != null) {
            refused.keySet().removeIf(indexKey -> CacheableResource.indexedFieldOf(indexKey).equals(fieldName));
        }
    }

    private static String fieldIndexKey(String collId, String indexKey) {
        return collId + Globals.COLL_IDENTIFIER_SEPARATOR + indexKey;
    }
}
