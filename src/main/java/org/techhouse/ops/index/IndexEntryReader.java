package org.techhouse.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonPrimitive;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.JsonUtils;

public final class IndexEntryReader {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final IndexKind[] HASH_INDEX_KINDS = {IndexKind.OBJECT, IndexKind.ARRAY};

    private IndexEntryReader() {
    }

    // Null means the field is not indexed, or its entries could not be loaded: the caller falls back
    // to a scan. Entries are read under the field's index read lock and returned as deep copies, so
    // the background index writer can never mutate a set a consumer is still iterating.
    public static List<FieldIndexEntry<?>> getIndexEntriesForField(String dbName, String collName, String fieldName)
            throws IOException {
        if (cache.hasNoIndex(dbName, collName, fieldName)) {
            return null;
        }
        final List<FieldIndexEntry<?>> combined = new ArrayList<>();
        final Set<String> hashIds = new HashSet<>();
        try {
            rl.lockIndexRead(dbName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            addEntriesOfType(combined, dbName, collName, fieldName, Number.class);
            addEntriesOfType(combined, dbName, collName, fieldName, Boolean.class);
            addEntriesOfType(combined, dbName, collName, fieldName, String.class);
            for (var customType : CustomTypeFactory.getCustomTypes().values()) {
                addEntriesOfType(combined, dbName, collName, fieldName, customType);
            }
            for (var kind : HASH_INDEX_KINDS) {
                final var hashEntries = cache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, kind);
                if (hashEntries != null) {
                    for (var entry : hashEntries) {
                        hashIds.addAll(entry.getIds());
                    }
                }
            }
        } finally {
            rl.releaseIndexRead(dbName, collName, fieldName);
        }
        if (!hashIds.isEmpty()) {
            addHashIndexEntries(combined, dbName, collName, fieldName, hashIds);
        }
        // Snapshot pending ids AFTER the index read so any write that committed before the read is
        // either already indexed (index accurate) or still pending (covered here).
        final var pendingIds = pendingIndexWrites.idsFor(dbName, collName);
        if (!pendingIds.isEmpty() && !reconcilePending(combined, dbName, collName, fieldName, pendingIds)) {
            return null;
        }
        if (combined.isEmpty()) {
            return null;
        }
        if (!Globals.ADMIN_DB_NAME.equals(dbName)) {
            cache.recordFieldIndexAccess(dbName, collName, fieldName);
        }
        recordAnalyzeIndexUse(dbName, collName, fieldName);
        return combined;
    }

    // Records that this field index was used (and its read lock taken) for analyze mode. A no-op
    // when analyze is off (no active context).
    private static void recordAnalyzeIndexUse(String dbName, String collName, String fieldName) {
        final var analyzeContext = AnalyzeContext.current();
        if (analyzeContext != null) {
            analyzeContext.addIndexUsed(fieldName);
            analyzeContext.addLock(AnalyzeContext.fieldLockId(dbName, collName, fieldName));
        }
    }

    // Fetches the actual documents for all object/array-valued ids collected from the hash indexes,
    // groups them by their real field value, and appends one FieldIndexEntry<JsonBaseElement> per
    // distinct value to combined. Called after the index read lock is released, following the same
    // lock-free getEntriesByIds pattern used by reconcilePending.
    private static void addHashIndexEntries(List<FieldIndexEntry<?>> combined, String dbName, String collName,
            String fieldName, Set<String> hashIds) throws IOException {
        final var docs = cache.getEntriesByIds(dbName, collName, hashIds);
        final var byValue = new HashMap<JsonBaseElement, FieldIndexEntry<JsonBaseElement>>();
        for (var doc : docs) {
            final var data = doc.getData();
            if (!JsonUtils.hasInPath(data, fieldName)) {
                continue;
            }
            final var value = JsonUtils.getFromPath(data, fieldName);
            if (!value.isJsonObject() && !value.isJsonArray()) {
                continue;
            }
            byValue.computeIfAbsent(value, v -> {
                final var entry = new FieldIndexEntry<>(dbName, collName, v, new HashSet<>());
                combined.add(entry);
                return entry;
            }).getIds().add(doc.get_id());
        }
    }

    // Adds deep copies (entry + id set) of a type's cached entries to combined, so the returned list
    // is fully detached from the cache.
    private static void addEntriesOfType(List<FieldIndexEntry<?>> combined, String dbName, String collName,
            String fieldName, Class<?> type) throws IOException {
        final var entries = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, type);
        if (entries != null) {
            for (var entry : entries) {
                combined.add(new FieldIndexEntry<>(entry.getDatabaseName(), entry.getCollectionName(), entry.getValue(),
                        new HashSet<>(entry.getIds())));
            }
        }
    }

    // Reconciles documents committed but not yet indexed into the loaded index entries: their index
    // membership is untrustworthy, so every pending id is dropped from all entries and then re-added
    // to the entry matching its CURRENT scalar/custom value (creating the entry when the value is
    // new). Emptied entries are dropped so DISTINCT does not surface phantom values. Returns false
    // when a pending document's value is present but not scalar/custom (object/array), signaling the
    // caller to fall back to a full scan, which sees every committed document. The mutated entries are
    // the deep copies built above, so the cache is never touched.
    private static boolean reconcilePending(List<FieldIndexEntry<?>> combined, String dbName, String collName,
            String fieldName, Set<String> pendingIds) throws IOException {
        for (var entry : combined) {
            entry.getIds().removeAll(pendingIds);
        }
        final var byValue = new HashMap<JsonBaseElement, FieldIndexEntry<?>>();
        for (var entry : combined) {
            byValue.put(IndexValueCodec.indexValueToElement(entry.getValue()), entry);
        }
        for (var dbEntry : cache.getEntriesByIds(dbName, collName, pendingIds)) {
            final var data = dbEntry.getData();
            if (!JsonUtils.hasInPath(data, fieldName)) {
                continue;
            }
            final var element = JsonUtils.getFromPath(data, fieldName);
            if (!element.isJsonPrimitive() && !element.isJsonNull()) {
                return false;
            }
            if (element.isJsonNull()) {
                final var nullEntry = byValue.get(JsonNull.INSTANCE);
                if (nullEntry != null) {
                    nullEntry.getIds().add(dbEntry.get_id());
                } else {
                    final var newEntry = new FieldIndexEntry<>(dbName, collName, JsonNull.INSTANCE,
                            new HashSet<>(Set.of(dbEntry.get_id())));
                    combined.add(newEntry);
                    byValue.put(JsonNull.INSTANCE, newEntry);
                }
                continue;
            }
            final var existing = byValue.get(element);
            if (existing != null) {
                existing.getIds().add(dbEntry.get_id());
            } else {
                final var newEntry = scalarEntryFor(dbName, collName, element.asJsonPrimitive(), dbEntry.get_id());
                combined.add(newEntry);
                byValue.put(element, newEntry);
            }
        }
        combined.removeIf(entry -> entry.getIds().isEmpty());
        return true;
    }

    // Builds a fresh scalar/custom FieldIndexEntry for a single pending document, mirroring the value
    // conversion used when an index is first created.
    private static FieldIndexEntry<?> scalarEntryFor(String dbName, String collName, JsonPrimitive<?> primitive,
            String id) {
        final var ids = new HashSet<>(Set.of(id));
        return switch (primitive) {
            case JsonNumber jsonNumber -> new FieldIndexEntry<>(dbName, collName, jsonNumber.getValue(), ids);
            case JsonBoolean jsonBoolean -> new FieldIndexEntry<>(dbName, collName, jsonBoolean.getValue(), ids);
            case JsonString jsonString -> jsonString.isJsonCustom()
                    ? new FieldIndexEntry<>(dbName, collName, CustomTypeFactory.getCustomTypeInstance(jsonString), ids)
                    : new FieldIndexEntry<>(dbName, collName, jsonString.getValue(), ids);
            default -> throw new IllegalStateException("Unexpected value: " + primitive);
        };
    }

    // Targeted index lookup for a JOIN: returns the set of ids in the remote collection whose
    // remoteField equals any value in localValues, using one binary-search-backed getIdsFromIndex
    // call per distinct local value instead of deep-copying every index entry. Returns null when
    // the field has no index so the caller can fall back to the full-scan path. Pending writes are
    // reconciled by re-testing each pending document against the local value set.
    public static Set<String> getMatchingIdsForJoin(String dbName, String collName, String fieldName,
            Set<JsonBaseElement> localValues) throws IOException {
        if (cache.hasNoIndex(dbName, collName, fieldName)) {
            return null;
        }
        recordAnalyzeIndexUse(dbName, collName, fieldName);
        final var matchingIds = new HashSet<String>();
        for (var localValue : localValues) {
            if (localValue.isJsonNull()) {
                continue; // null-keyed joins are not index-backed
            }
            final var lookupValue = IndexValueCodec.elementToLookupValue(localValue);
            if (lookupValue == null) {
                continue; // object/array-valued local key - skip index lookup
            }
            final var operator = new FieldOperator(FieldOperatorType.EQUALS, fieldName, localValue);
            final var ids = cache.getIdsFromIndex(dbName, collName, fieldName, operator, lookupValue);
            if (ids != null) {
                matchingIds.addAll(ids);
            }
        }
        final var pendingIds = pendingIndexWrites.idsFor(dbName, collName);
        for (var pendingId : pendingIds) {
            for (var doc : cache.getEntriesByIds(dbName, collName, Set.of(pendingId))) {
                if (JsonUtils.hasInPath(doc.getData(), fieldName)) {
                    final var val = JsonUtils.getFromPath(doc.getData(), fieldName);
                    if (localValues.contains(val)) {
                        matchingIds.add(pendingId);
                    }
                }
            }
        }
        return matchingIds;
    }
}
