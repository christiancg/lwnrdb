package org.techhouse.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.analyze.AnalyzeContext;
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
    private static final IndexKind[] HASH_INDEX_KINDS = {IndexKind.OBJECT, IndexKind.ARRAY};

    private IndexEntryReader() {
    }

    // Entries are read under the field's index read lock and returned as deep copies, so the background
    // index writer can never mutate a set a consumer is still iterating.
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
        final var pendingIds = PendingWriteReconciler.pendingIds(dbName, collName);
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

    private static void recordAnalyzeIndexUse(String dbName, String collName, String fieldName) {
        final var analyzeContext = AnalyzeContext.current();
        if (analyzeContext != null) {
            analyzeContext.addIndexUsed(fieldName);
            analyzeContext.addLock(AnalyzeContext.fieldLockId(dbName, collName, fieldName));
        }
    }

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

    // Pending ids are dropped from every entry and re-added under the document's CURRENT value; a
    // non-scalar pending value returns false so the caller falls back to a full scan.
    private static boolean reconcilePending(List<FieldIndexEntry<?>> combined, String dbName, String collName,
            String fieldName, Set<String> pendingIds) throws IOException {
        for (var entry : combined) {
            entry.getIds().removeAll(pendingIds);
        }
        final var byValue = new HashMap<JsonBaseElement, FieldIndexEntry<?>>();
        for (var entry : combined) {
            byValue.put(IndexValueCodec.indexValueToElement(entry.getValue()), entry);
        }
        for (var dbEntry : PendingWriteReconciler.pendingDocuments(dbName, collName, pendingIds)) {
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

    public static Set<String> getMatchingIdsForJoin(String dbName, String collName, String fieldName,
            Set<JsonBaseElement> localValues) throws IOException {
        if (cache.hasNoIndex(dbName, collName, fieldName)) {
            return null;
        }
        recordAnalyzeIndexUse(dbName, collName, fieldName);
        final var matchingIds = new HashSet<String>();
        for (var localValue : localValues) {
            if (localValue.isJsonNull()) {
                continue;
            }
            final var lookupValue = IndexValueCodec.elementToLookupValue(localValue);
            if (lookupValue == null) {
                continue;
            }
            final var operator = new FieldOperator(FieldOperatorType.EQUALS, fieldName, localValue);
            final var ids = cache.getIdsFromIndex(dbName, collName, fieldName, operator, lookupValue);
            if (ids != null) {
                matchingIds.addAll(ids);
            }
        }
        final var pendingIds = PendingWriteReconciler.pendingIds(dbName, collName);
        if (pendingIds.isEmpty()) {
            return matchingIds;
        }
        return PendingWriteReconciler.correctIds(matchingIds, dbName, collName, pendingIds, fieldName, (data,
                field) -> JsonUtils.hasInPath(data, field) && localValues.contains(JsonUtils.getFromPath(data, field)));
    }
}
