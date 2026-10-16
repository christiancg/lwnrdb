package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ex.CollectionBusyException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.FieldIndexBatch;
import org.techhouse.ops.index.IndexEntryReader;
import org.techhouse.ops.index.IndexValueCodec;
import org.techhouse.utils.JsonUtils;

public class IndexHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private static final Configuration configuration = Configuration.getInstance();

    public static List<String> getSortedIdsForField(String dbName, String collName, String fieldName,
            java.util.Comparator<FieldIndexEntry<?>> order, long maxIds, ReadObserver observer) throws IOException {
        return IndexEntryReader.sortedIdsForField(dbName, collName, fieldName, order, maxIds, observer);
    }

    public static List<FieldIndexEntry<?>> getIndexEntriesForField(String dbName, String collName, String fieldName,
            ReadObserver observer) throws IOException {
        return IndexEntryReader.getIndexEntriesForField(dbName, collName, fieldName, observer);
    }

    public static Set<String> getMatchingIdsForJoin(String dbName, String collName, String fieldName,
            Set<JsonBaseElement> localValues, ReadObserver observer) throws IOException {
        return IndexEntryReader.getMatchingIdsForJoin(dbName, collName, fieldName, localValues, observer);
    }

    public static List<String> idsInOrder(List<FieldIndexEntry<?>> sortedEntries,
            java.util.Comparator<FieldIndexEntry<?>> order, long maxIds) {
        return IndexEntryReader.idsInOrder(sortedEntries, order, maxIds);
    }

    public static JsonBaseElement indexValueToElement(Object value) {
        return IndexValueCodec.indexValueToElement(value);
    }

    public static Object elementToLookupValue(JsonBaseElement element) {
        return IndexValueCodec.elementToLookupValue(element);
    }

    public static void createIndex(String dbName, String collName, String fieldName) throws InterruptedException {
        rl.lockIndex(dbName, collName, fieldName);
        try {
            buildIndex(dbName, collName, fieldName);
        } catch (RuntimeException e) {
            fs.dropIndex(dbName, collName, fieldName);
            throw e;
        } finally {
            cache.evictFieldIndexAllTypes(dbName, collName, fieldName);
            rl.releaseIndex(dbName, collName, fieldName);
        }
    }

    private static void buildIndex(String dbName, String collName, String fieldName) {
        fs.dropIndex(dbName, collName, fieldName);
        final var coll = cache.getWholeCollection(dbName, collName);
        final var entriesToBeIndexed = coll.values().stream().map(DbEntry::getData)
                .filter(jsonObject -> JsonUtils.hasInPath(jsonObject, fieldName))
                .collect(Collectors.groupingBy(jsonObject -> JsonUtils.getFromPath(jsonObject, fieldName)));
        final Map<Class<?>, List<FieldIndexEntry<?>>> indexes = entriesToBeIndexed.entrySet().stream()
                .map(jsonElementListEntry -> {
                    final var key = jsonElementListEntry.getKey();
                    final var ids = jsonElementListEntry.getValue().stream()
                            .map(jsonObject -> jsonObject.get(Globals.PK_FIELD).asJsonString().getValue())
                            .collect(Collectors.toSet());
                    if (key.isJsonPrimitive()) {
                        final var primitive = key.asJsonPrimitive();
                        return switch (primitive) {
                            case JsonNumber jsonNumber ->
                                new FieldIndexEntry<>(dbName, collName, jsonNumber.doubleValue() + 0.0, ids);
                            case JsonString jsonString -> {
                                if (jsonString.isJsonCustom()) {
                                    final var custom = CustomTypeFactory.getCustomTypeInstance(jsonString);
                                    yield new FieldIndexEntry<>(dbName, collName, custom, ids);
                                } else {
                                    yield new FieldIndexEntry<>(dbName, collName, jsonString.getValue(), ids);
                                }
                            }
                            case JsonBoolean jsonBoolean ->
                                new FieldIndexEntry<>(dbName, collName, jsonBoolean.getValue(), ids);
                            default -> throw new IllegalStateException("Unexpected value: " + primitive);
                        };
                    } else {
                        return null;
                    }
                }).filter(Objects::nonNull).collect(Collectors.groupingBy(fieldIndexEntry -> {
                    final var clazz = fieldIndexEntry.getValue().getClass();
                    if (Number.class.isAssignableFrom(clazz)) {
                        return Number.class;
                    }
                    return clazz;
                }));
        fs.writeIndexFile(dbName, collName, fieldName, indexes);
        writeHashIndexes(dbName, collName, fieldName, entriesToBeIndexed);
    }

    private static void writeHashIndexes(String dbName, String collName, String fieldName,
            Map<JsonBaseElement, List<JsonObject>> grouped) {
        final var objectIds = new LinkedHashMap<String, Set<String>>();
        final var arrayIds = new LinkedHashMap<String, Set<String>>();
        for (var groupedEntry : grouped.entrySet()) {
            final var key = groupedEntry.getKey();
            if (!key.isJsonObject() && !key.isJsonArray()) {
                continue;
            }
            final var idsByHash = key.isJsonObject() ? objectIds : arrayIds;
            final var ids = idsByHash.computeIfAbsent(JsonUtils.hashElement(key), ignored -> new HashSet<>());
            for (var document : groupedEntry.getValue()) {
                ids.add(document.get(Globals.PK_FIELD).asJsonString().getValue());
            }
        }
        fs.writeHashIndexFile(dbName, collName, fieldName, IndexKind.OBJECT,
                hashEntriesOf(dbName, collName, objectIds));
        fs.writeHashIndexFile(dbName, collName, fieldName, IndexKind.ARRAY, hashEntriesOf(dbName, collName, arrayIds));
    }

    private static List<FieldIndexEntry<String>> hashEntriesOf(String dbName, String collName,
            Map<String, Set<String>> idsByHash) {
        final var entries = new ArrayList<FieldIndexEntry<String>>();
        for (var hashIds : idsByHash.entrySet()) {
            entries.add(new FieldIndexEntry<>(dbName, collName, hashIds.getKey(), hashIds.getValue()));
        }
        return entries;
    }

    public static boolean dropIndex(String dbName, String collName, String fieldName) throws InterruptedException {
        rl.lockIndex(dbName, collName, fieldName);
        try {
            return fs.dropIndex(dbName, collName, fieldName);
        } finally {
            cache.evictFieldIndexAllTypes(dbName, collName, fieldName);
            rl.releaseIndex(dbName, collName, fieldName);
        }
    }

    private static List<String> maintainedIndexes(String dbName, String collName) {
        return cache.getIndexesForCollection(dbName, collName).stream()
                .filter(fieldName -> !fs.indexMarkers().isMarked(dbName, collName, fieldName)).toList();
    }

    private static void lockCollectionForMaintenance(String dbName, String collName, long budget)
            throws InterruptedException {
        if (!rl.tryLockRead(dbName, collName, budget)) {
            throw new CollectionBusyException(Cache.getCollectionIdentifier(dbName, collName), budget);
        }
    }

    public static void bulkUpdateIndexes(String dbName, String collName, List<String> ids)
            throws IOException, InterruptedException {
        bulkUpdateIndexes(dbName, collName, ids, configuration.getTransactionLockTimeoutMs());
    }

    public static void bulkUpdateIndexes(String dbName, String collName, List<String> ids, long lockBudgetMs)
            throws IOException, InterruptedException {
        if (ids.isEmpty() || maintainedIndexes(dbName, collName).isEmpty()) {
            return;
        }
        lockCollectionForMaintenance(dbName, collName, lockBudgetMs);
        try {
            final var existingIndexes = maintainedIndexes(dbName, collName);
            if (existingIndexes.isEmpty()) {
                return;
            }
            final var byId = new HashMap<String, DbEntry>();
            for (var doc : cache.getEntriesByIds(dbName, collName, new HashSet<>(ids))) {
                byId.put(doc.get_id(), doc);
            }
            for (var fieldName : existingIndexes) {
                maintainField(dbName, collName, fieldName, ids, byId);
            }
        } finally {
            rl.releaseRead(dbName, collName);
        }
    }

    public static void updateIndexes(String dbName, String collName, String id)
            throws IOException, InterruptedException {
        bulkUpdateIndexes(dbName, collName, Collections.singletonList(id));
    }

    private static void maintainField(String dbName, String collName, String fieldName, List<String> ids,
            Map<String, DbEntry> byId) throws IOException, InterruptedException {
        rl.lockIndex(dbName, collName, fieldName);
        var flushed = false;
        try {
            final var batch = FieldIndexBatch.open(dbName, collName, fieldName);
            for (var id : ids) {
                final var doc = byId.get(id);
                batch.apply(id, doc == null ? null : JsonUtils.getFromPath(doc.getData(), fieldName));
            }
            batch.flush();
            flushed = true;
        } finally {
            if (!flushed) {
                cache.evictFieldIndexAllTypes(dbName, collName, fieldName);
            }
            rl.releaseIndex(dbName, collName, fieldName);
        }
    }
}
