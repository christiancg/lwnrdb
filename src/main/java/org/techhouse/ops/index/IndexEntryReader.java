package org.techhouse.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.analyze.ReadObserver;
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
import org.techhouse.utils.CaseFolding;
import org.techhouse.utils.FieldPath;
import org.techhouse.utils.SaltedKey;

public final class IndexEntryReader {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);

    private IndexEntryReader() {
    }

    // Entries are read under the field's index read lock and returned as deep copies, so the background
    // index writer can never mutate a set a consumer is still iterating.
    public static List<FieldIndexEntry<?>> getIndexEntriesForField(String dbName, String collName, String fieldName,
            ReadObserver observer) throws IOException {
        if (cache.hasNoIndex(dbName, collName, fieldName)) {
            return null;
        }
        final List<FieldIndexEntry<?>> combined = new ArrayList<>();
        final Set<String> hashIds = new HashSet<>();
        final var pendingBefore = PendingWriteReconciler.pendingIds(dbName, collName);
        try {
            rl.lockIndexRead(dbName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            for (var type : IndexKind.scalarIndexClasses()) {
                addEntriesOfType(combined, dbName, collName, fieldName, type);
            }
            for (var kind : IndexKind.HASH_KINDS) {
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
            addHashIndexEntries(combined, dbName, collName, fieldName, hashIds, observer);
        }
        final var pendingIds = PendingWriteReconciler.pendingIdsAround(pendingBefore, dbName, collName);
        if (!pendingIds.isEmpty() && !reconcilePending(combined, dbName, collName, fieldName, pendingIds, observer)) {
            return null;
        }
        if (combined.isEmpty() || doesNotCoverEveryDocument(combined, dbName, collName)) {
            return null;
        }
        if (!Globals.ADMIN_DB_NAME.equals(dbName)) {
            cache.recordFieldIndexAccess(dbName, collName, fieldName);
        }
        recordAnalyzeIndexUse(dbName, collName, fieldName, observer);
        return combined;
    }

    public static List<String> sortedIdsForField(String dbName, String collName, String fieldName,
            Comparator<FieldIndexEntry<?>> order, long maxIds, ReadObserver observer) throws IOException {
        if (cache.hasNoIndex(dbName, collName, fieldName)
                || !PendingWriteReconciler.pendingIds(dbName, collName).isEmpty()) {
            return null;
        }
        final List<FieldIndexEntry<?>> entries = new ArrayList<>();
        try {
            rl.lockIndexRead(dbName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        final List<String> ordered;
        try {
            for (var kind : IndexKind.HASH_KINDS) {
                if (cache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, kind) != null) {
                    return null;
                }
            }
            for (var type : IndexKind.scalarIndexClasses()) {
                addCachedEntriesOfType(entries, dbName, collName, fieldName, type);
            }
            if (entries.isEmpty() || doesNotCoverEveryDocument(entries, dbName, collName)) {
                return null;
            }
            entries.sort(order);
            ordered = idsInOrder(entries, order, maxIds);
        } finally {
            rl.releaseIndexRead(dbName, collName, fieldName);
        }
        if (!PendingWriteReconciler.pendingIds(dbName, collName).isEmpty()) {
            return null;
        }
        if (!Globals.ADMIN_DB_NAME.equals(dbName)) {
            cache.recordFieldIndexAccess(dbName, collName, fieldName);
        }
        recordAnalyzeIndexUse(dbName, collName, fieldName, observer);
        return ordered;
    }

    private static boolean doesNotCoverEveryDocument(List<FieldIndexEntry<?>> entries, String dbName, String collName) {
        return coversFewer(idCount(entries), dbName, collName);
    }

    private static boolean coversFewer(long covered, String dbName, String collName) {
        final var documents = cache.pkCountForCoverage(dbName, collName);
        return documents < 0 || covered < documents;
    }

    private static long idCount(List<? extends FieldIndexEntry<?>> entries) {
        var count = 0L;
        for (final var entry : entries) {
            count += entry.getIds().size();
        }
        return count;
    }

    public static List<Object> distinctValuesForField(String dbName, String collName, String fieldName,
            ReadObserver observer) throws IOException {
        if (cache.hasNoIndex(dbName, collName, fieldName)) {
            return null;
        }
        final var pendingBefore = PendingWriteReconciler.pendingIds(dbName, collName);
        if (!pendingBefore.isEmpty()) {
            return valuesOf(getIndexEntriesForField(dbName, collName, fieldName, observer));
        }
        final var values = new ArrayList<>();
        final var hashIds = new HashSet<String>();
        var covered = 0L;
        try {
            rl.lockIndexRead(dbName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            for (var type : IndexKind.scalarIndexClasses()) {
                final var entries = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, type);
                if (entries != null) {
                    covered += idCount(entries);
                    entries.forEach(entry -> values.add(entry.getValue()));
                }
            }
            for (var kind : IndexKind.HASH_KINDS) {
                final var hashEntries = cache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, kind);
                if (hashEntries != null) {
                    hashEntries.forEach(entry -> hashIds.addAll(entry.getIds()));
                }
            }
        } finally {
            rl.releaseIndexRead(dbName, collName, fieldName);
        }
        final var hashed = new ArrayList<FieldIndexEntry<?>>();
        if (!hashIds.isEmpty()) {
            addHashIndexEntries(hashed, dbName, collName, fieldName, hashIds, observer);
        }
        if (!PendingWriteReconciler.pendingIdsAround(pendingBefore, dbName, collName).isEmpty()) {
            return valuesOf(getIndexEntriesForField(dbName, collName, fieldName, observer));
        }
        covered += idCount(hashed);
        hashed.forEach(entry -> values.add(entry.getValue()));
        if (values.isEmpty() || coversFewer(covered, dbName, collName)) {
            return null;
        }
        if (!Globals.ADMIN_DB_NAME.equals(dbName)) {
            cache.recordFieldIndexAccess(dbName, collName, fieldName);
        }
        recordAnalyzeIndexUse(dbName, collName, fieldName, observer);
        return values;
    }

    private static List<Object> valuesOf(List<FieldIndexEntry<?>> entries) {
        return entries == null ? null : entries.stream().<Object>map(FieldIndexEntry::getValue).toList();
    }

    public static List<String> idsInOrder(List<FieldIndexEntry<?>> sortedEntries, Comparator<FieldIndexEntry<?>> order,
            long maxIds) {
        final var ordered = new ArrayList<String>();
        var runStart = 0;
        while (runStart < sortedEntries.size() && ordered.size() < maxIds) {
            var runEnd = runStart + 1;
            while (runEnd < sortedEntries.size()
                    && order.compare(sortedEntries.get(runStart), sortedEntries.get(runEnd)) == 0) {
                runEnd++;
            }
            ordered.addAll(mergedIds(sortedEntries.subList(runStart, runEnd)));
            runStart = runEnd;
        }
        return ordered;
    }

    private static List<String> mergedIds(List<FieldIndexEntry<?>> run) {
        if (run.size() == 1) {
            return sortedIds(run.getFirst());
        }
        final var ids = new ArrayList<String>();
        for (final var entry : run) {
            ids.addAll(entry.getIds());
        }
        Collections.sort(ids);
        return ids;
    }

    public static List<String> sortedIds(FieldIndexEntry<?> entry) {
        final var ids = new ArrayList<>(entry.getIds());
        Collections.sort(ids);
        return ids;
    }

    private static void addCachedEntriesOfType(List<FieldIndexEntry<?>> entries, String dbName, String collName,
            String fieldName, Class<?> type) throws IOException {
        final var cached = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, type);
        if (cached != null) {
            entries.addAll(cached);
        }
    }

    private static void recordAnalyzeIndexUse(String dbName, String collName, String fieldName, ReadObserver observer) {
        observer.indexUsed(fieldName, AnalyzeContext.fieldLockId(dbName, collName, fieldName));
    }

    private static void addHashIndexEntries(List<FieldIndexEntry<?>> combined, String dbName, String collName,
            String fieldName, Set<String> hashIds, ReadObserver observer) throws IOException {
        final var docs = cache.getEntriesByIds(dbName, collName, hashIds);
        observer.scanned(docs.size());
        final var byValue = new HashMap<SaltedKey, FieldIndexEntry<JsonBaseElement>>();
        final var path = FieldPath.of(fieldName);
        for (var doc : docs) {
            final var data = doc.getData();
            final var value = path.resolve(data);
            if (value == null) {
                continue;
            }
            if (!value.isJsonObject() && !value.isJsonArray()) {
                continue;
            }
            byValue.computeIfAbsent(new SaltedKey(value), key -> {
                final var entry = new FieldIndexEntry<>(dbName, collName, key.element(), new HashSet<>());
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
            String fieldName, Set<String> pendingIds, ReadObserver observer) throws IOException {
        for (var entry : combined) {
            entry.getIds().removeAll(pendingIds);
        }
        final var byValue = new HashMap<SaltedKey, FieldIndexEntry<?>>();
        for (var entry : combined) {
            byValue.put(new SaltedKey(IndexValueCodec.indexValueToElement(entry.getValue())), entry);
        }
        final var path = FieldPath.of(fieldName);
        for (var dbEntry : PendingWriteReconciler.pendingDocuments(dbName, collName, pendingIds, observer)) {
            final var data = dbEntry.getData();
            final var element = path.resolve(data);
            if (element == null) {
                continue;
            }
            if (!element.isJsonPrimitive() && !element.isJsonNull()) {
                return false;
            }
            if (element.isJsonNull()) {
                final var nullEntry = byValue.get(new SaltedKey(JsonNull.INSTANCE));
                if (nullEntry != null) {
                    nullEntry.getIds().add(dbEntry.get_id());
                } else {
                    final var newEntry = new FieldIndexEntry<>(dbName, collName, JsonNull.INSTANCE,
                            new HashSet<>(Set.of(dbEntry.get_id())));
                    combined.add(newEntry);
                    byValue.put(new SaltedKey(JsonNull.INSTANCE), newEntry);
                }
                continue;
            }
            final var existing = byValue.get(new SaltedKey(element));
            if (existing != null) {
                existing.getIds().add(dbEntry.get_id());
            } else {
                final var newEntry = scalarEntryFor(dbName, collName, element.asJsonPrimitive(), dbEntry.get_id());
                combined.add(newEntry);
                byValue.put(new SaltedKey(element), newEntry);
            }
        }
        combined.removeIf(entry -> entry.getIds().isEmpty());
        return true;
    }

    private static FieldIndexEntry<?> scalarEntryFor(String dbName, String collName, JsonPrimitive<?> primitive,
            String id) {
        final var ids = new HashSet<>(Set.of(id));
        return switch (primitive) {
            case JsonNumber jsonNumber -> new FieldIndexEntry<>(dbName, collName, jsonNumber.doubleValue() + 0.0, ids);
            case JsonBoolean jsonBoolean -> new FieldIndexEntry<>(dbName, collName, jsonBoolean.getValue(), ids);
            case JsonString jsonString -> jsonString.isJsonCustom()
                    ? new FieldIndexEntry<>(dbName, collName, CustomTypeFactory.getCustomTypeInstance(jsonString), ids)
                    : new FieldIndexEntry<>(dbName, collName, jsonString.getValue(), ids);
            default -> throw new IllegalStateException("Unexpected value: " + primitive);
        };
    }

    public static Set<String> getMatchingIdsForJoin(String dbName, String collName, String fieldName,
            Set<JsonBaseElement> localValues, ReadObserver observer) throws IOException {
        if (cache.hasNoIndex(dbName, collName, fieldName)) {
            return null;
        }
        for (var localValue : localValues) {
            if (IndexValueCodec.elementToLookupValue(localValue) == null) {
                return null;
            }
        }
        recordAnalyzeIndexUse(dbName, collName, fieldName, observer);
        final var pendingBefore = PendingWriteReconciler.pendingIds(dbName, collName);
        final var lookupValues = new ArrayList<>(localValues.size());
        final var foldedStringKeys = new HashSet<String>();
        for (var localValue : localValues) {
            if (localValue instanceof JsonString string && !string.isJsonCustom()
                    && !foldedStringKeys.add(CaseFolding.foldKey(string.getValue()))) {
                continue;
            }
            lookupValues.add(IndexValueCodec.elementToLookupValue(localValue));
        }
        final var matchingIds = cache.getIdsEqualToAny(dbName, collName, fieldName, lookupValues);
        if (matchingIds == null) {
            return null;
        }
        final var pendingIds = PendingWriteReconciler.pendingIdsAround(pendingBefore, dbName, collName);
        if (pendingIds.isEmpty()) {
            return matchingIds;
        }
        final var compiled = FieldPath.of(fieldName);
        return PendingWriteReconciler.correctIds(matchingIds, dbName, collName, pendingIds, fieldName,
                (data, field) -> {
                    final var resolved = compiled.reusedFor(field).resolve(data);
                    return resolved != null && localValues.contains(resolved);
                }, observer);
    }
}
