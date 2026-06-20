package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.SearchUtils;

/**
 * Cache for user document and index entries: the PK index map, field index map
 * and full document map. Unlike {@link AdminCache}, these caches are
 * memory-managed — admission is gated by {@link MemoryManagement} and entries
 * are evicted by the LFU sweep. The {@link Cache} facade coordinates this cache
 * with the admin page metadata for the cross-cutting read/stream methods.
 */
public class UserCache {
    private static final long ESTIMATED_PK_ENTRY_BYTES = 96L;
    private static final long ESTIMATED_FIELD_ENTRY_OVERHEAD_BYTES = 64L;
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Map<String, List<PkIndexEntry>> pkIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DbEntry>> collectionMap = new ConcurrentHashMap<>();
    // Lazily initialized because UserCache <-> MemoryManagement is a construction-time cycle:
    // MemoryManagement holds the cache eagerly, so we cannot hold MemoryManagement eagerly here
    // without recursing through the IoC container during static init.
    private MemoryManagement memoryManagement;
    private BackgroundTaskManager taskManager;

    private MemoryManagement memoryManagement() {
        var mm = memoryManagement;
        if (mm == null) {
            mm = IocContainer.get(MemoryManagement.class);
            memoryManagement = mm;
        }
        return mm;
    }

    private BackgroundTaskManager taskManager() {
        var tm = taskManager;
        if (tm == null) {
            tm = IocContainer.get(BackgroundTaskManager.class);
            taskManager = tm;
        }
        return tm;
    }

    public List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String dbName, String collName) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        var primaryKeyIndex = pkIndexMap.get(collectionIdentifier);
        if (primaryKeyIndex == null) {
            primaryKeyIndex = fs.readWholePkIndexFile(dbName, collName);
            if (shouldCache(dbName, estimatePkIndexSize(primaryKeyIndex))) {
                pkIndexMap.put(collectionIdentifier, primaryKeyIndex);
            }
        }
        return primaryKeyIndex;
    }

    public boolean isCachingDisabled(String dbName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return false;
        }
        return configuration.isCachingDisabled();
    }

    private boolean shouldCache(String dbName, long estimatedBytes) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return true;
        }
        return memoryManagement().admissionCheck(estimatedBytes) == AdmissionDecision.ADMIT;
    }

    public <T> List<FieldIndexEntry<T>> getFieldIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, Class<T> indexType) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var indexIdentifier = Cache.getIndexIdentifier(fieldName, indexType);
        var index = fieldIndexMap.get(collectionIdentifier);
        List<FieldIndexEntry<T>> indexEntries = null;
        if (index == null || index.keySet().stream().noneMatch(string -> string.contains(indexIdentifier))) {
            indexEntries = fs.readWholeFieldIndexFiles(dbName, collName, fieldName, indexType);
            if (indexEntries == null) {
                return null;
            } else if (index == null) {
                index = new ConcurrentHashMap<>();
            }
            final var asWildcard = new ArrayList<FieldIndexEntry<?>>(indexEntries);
            if (shouldCache(dbName, estimateFieldIndexSize(asWildcard))) {
                index.put(indexIdentifier, new ArrayList<>(indexEntries));
                fieldIndexMap.put(collectionIdentifier, index);
            }
        } else {
            final var existingIndex = index.get(indexIdentifier);
            if (existingIndex != null) {
                indexEntries = existingIndex.stream()
                        .map(fieldIndexEntry -> new FieldIndexEntry<>(fieldIndexEntry.getDatabaseName(),
                                fieldIndexEntry.getCollectionName(), indexType.cast(fieldIndexEntry.getValue()),
                                fieldIndexEntry.getIds()))
                        .collect(Collectors.toList());
            }
        }
        return indexEntries;
    }

    public <T> Set<String> getIdsFromIndex(String dbName, String collName, String fieldName, FieldOperator operator,
            T value) throws IOException {
        final var result = doGetIdsFromIndex(dbName, collName, fieldName, operator, value);
        if (result != null && !Globals.ADMIN_DB_NAME.equals(dbName)) {
            recordFieldIndexAccess(dbName, collName, fieldName);
        }
        return result;
    }

    public void recordFieldIndexAccess(String dbName, String collName, String fieldName) {
        memoryManagement().recordAccess(AccessKind.FIELD_INDEX, dbName, collName, fieldName);
        taskManager().submitBackgroundTask(new CollectionUsageEvent(AccessKind.FIELD_INDEX, dbName, collName, fieldName,
                System.currentTimeMillis()));
    }

    @SuppressWarnings("unchecked")
    private <T> Set<String> doGetIdsFromIndex(String dbName, String collName, String fieldName, FieldOperator operator,
            T value) throws IOException {
        return switch (value) {
            case Number n -> {
                final var numberIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class);
                if (numberIndex != null) {
                    yield SearchUtils.findingByOperator(numberIndex, operator.getFieldOperatorType(), n);
                } else {
                    yield null;
                }
            }
            case Boolean b -> {
                final var booleanIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Boolean.class);
                if (booleanIndex != null) {
                    yield SearchUtils.findingByOperator(booleanIndex, operator.getFieldOperatorType(), b);
                } else {
                    yield null;
                }
            }
            case String s -> {
                final var stringIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, String.class);
                if (stringIndex != null) {
                    yield SearchUtils.findingByOperator(stringIndex, operator.getFieldOperatorType(), s);
                } else {
                    yield null;
                }
            }
            case JsonCustom<?> c -> {
                final var customTypes = CustomTypeFactory.getCustomTypes();
                final var customClass = customTypes.get(c.getCustomTypeName());
                final var customIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                        (Class<T>) customClass);
                if (customIndex != null) {
                    yield SearchUtils.findingByOperator(customIndex, operator.getFieldOperatorType(), (T) c);
                } else {
                    yield null;
                }
            }
            case JsonArray arr -> {
                final var firstElement = arr.get(0);
                if (firstElement.isJsonPrimitive()) {
                    final var prim = firstElement.asJsonPrimitive();
                    final var listStream = arr.asList().stream();
                    switch (prim) {
                        case JsonCustom<?> c -> {
                            final var customTypes = CustomTypeFactory.getCustomTypes();
                            final var customClass = customTypes.get(c.getCustomTypeName());
                            final var customIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                                    (Class<T>) customClass);
                            if (customIndex != null) {
                                final var custList = listStream.map(JsonBaseElement::asJsonCustom).toList();
                                yield SearchUtils.findingInNotIn(customIndex, operator.getFieldOperatorType(),
                                        (List<T>) custList);
                            } else {
                                yield null;
                            }
                        }
                        case JsonString ignored -> {
                            final var stringIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                                    String.class);
                            if (stringIndex != null) {
                                final var strList = listStream.map(x -> x.asJsonString().getValue()).toList();
                                yield SearchUtils.findingInNotIn(stringIndex, operator.getFieldOperatorType(), strList);
                            } else {
                                yield null;
                            }
                        }
                        case JsonNumber ignored -> {
                            final var numberIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                                    Number.class);
                            if (numberIndex != null) {
                                final var numberList = listStream.map(x -> x.asJsonNumber().getValue()).toList();
                                yield SearchUtils.findingInNotIn(numberIndex, operator.getFieldOperatorType(),
                                        numberList);
                            } else {
                                yield null;
                            }
                        }
                        case JsonBoolean ignored -> {
                            final var booleanIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                                    Boolean.class);
                            if (booleanIndex != null) {
                                final var booleanList = listStream.map(x -> x.asJsonBoolean().getValue()).toList();
                                yield SearchUtils.findingInNotIn(booleanIndex, operator.getFieldOperatorType(),
                                        booleanList);
                            } else {
                                yield null;
                            }
                        }
                        default -> {
                            yield null;
                        }
                    }
                } else {
                    yield null;
                }
            }
            default -> throw new IllegalStateException("Unexpected value: " + value);
        };
    }

    public void addEntryToCache(String dbName, String collName, DbEntry entry) {
        if (!shouldCache(dbName, entry.byteSize())) {
            return;
        }
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        var coll = collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>());
        coll.put(entry.get_id(), entry);
    }

    public void addEntriesToCache(String dbName, String collName, List<DbEntry> entries) {
        long total = 0L;
        for (var e : entries) {
            total += e.byteSize();
        }
        if (!shouldCache(dbName, total)) {
            return;
        }
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        var coll = collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>());
        coll.putAll(entries.stream().collect(Collectors.toMap(DbEntry::get_id, o -> o, (_, b) -> b)));
    }

    public DbEntry getById(String dbName, String collName, PkIndexEntry idxEntry) throws Exception {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        if (isCachingDisabled(dbName)) {
            return fs.getById(idxEntry);
        }
        final var coll = collectionMap.computeIfAbsent(collectionIdentifier, _ -> new ConcurrentHashMap<>());
        final var pk = idxEntry.getValue();
        var entry = coll.get(pk);
        if (entry == null) {
            entry = fs.getById(idxEntry);
            if (shouldCache(dbName, entry.byteSize())) {
                coll.put(pk, entry);
            }
        }
        return entry;
    }

    /**
     * Returns the cached document map for the collection, or {@code null} if none is cached. Used by the
     * {@link Cache} facade, which combines it with the admin page metadata to decide completeness.
     */
    public Map<String, DbEntry> getCachedCollection(String dbName, String collName) {
        return collectionMap.get(Cache.getCollectionIdentifier(dbName, collName));
    }

    /**
     * Admits a freshly loaded whole-collection map into the cache when caching is enabled and the
     * admission check passes, returning the resident map; otherwise returns the loaded map unchanged.
     */
    public Map<String, DbEntry> admitWholeCollection(String dbName, String collName, Map<String, DbEntry> loaded) {
        if (!isCachingDisabled(dbName)) {
            final var asMap = new ConcurrentHashMap<>(loaded);
            if (shouldCache(dbName, estimateCollectionSize(asMap))) {
                collectionMap.put(Cache.getCollectionIdentifier(dbName, collName), asMap);
                return asMap;
            }
        }
        return loaded;
    }

    public List<DbEntry> getEntriesByIds(String dbName, String collName, Set<String> ids) throws IOException {
        final var result = new ArrayList<DbEntry>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var cachingDisabled = isCachingDisabled(dbName);
        final var cached = cachingDisabled ? null : collectionMap.get(collectionIdentifier);
        // Serve cache hits directly by id; only ids that are not cached need to be
        // resolved through the PK index and targeted-read from disk.
        final var missingIds = new ArrayList<String>();
        for (var id : ids) {
            final var hit = cached != null ? cached.get(id) : null;
            if (hit != null) {
                result.add(hit);
            } else {
                missingIds.add(id);
            }
        }
        if (missingIds.isEmpty()) {
            return result;
        }
        final var pkIndex = getPkIndexAndLoadIfNecessary(dbName, collName);
        // Snapshot the resolved PkIndexEntry references. They are immutable value carriers,
        // so reading against this snapshot is unaffected by a concurrent write that shifts
        // offsets mid-read; read/write isolation for aggregates is a separate, pre-existing
        // concern (the aggregate path already reads lock-free).
        final var toRead = new ArrayList<PkIndexEntry>();
        for (var id : missingIds) {
            final var pos = Collections.binarySearch(pkIndex, id);
            if (pos >= 0) {
                toRead.add(pkIndex.get(pos));
            }
        }
        if (toRead.isEmpty()) {
            return result;
        }
        final var read = fs.getByIndexEntries(toRead);
        result.addAll(read);
        if (!cachingDisabled) {
            long bytes = 0L;
            for (var e : read) {
                bytes += e.byteSize();
            }
            if (shouldCache(dbName, bytes)) {
                final var coll = collectionMap.computeIfAbsent(collectionIdentifier, _ -> new ConcurrentHashMap<>());
                for (var e : read) {
                    coll.put(e.get_id(), e);
                }
            }
        }
        return result;
    }

    public void evictEntry(String dbName, String collName, String pk) {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var coll = collectionMap.get(collectionIdentifier);
        if (coll != null) {
            coll.remove(pk);
        }
    }

    public void evictDatabase(String dbName) {
        final var toRemove = collectionMap.keySet().stream().filter(s -> s.startsWith(dbName)).toList();
        for (var entryKeyToRemove : toRemove) {
            pkIndexMap.remove(entryKeyToRemove);
            collectionMap.remove(entryKeyToRemove);
        }
    }

    public void evictCollection(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        pkIndexMap.remove(collIdentifier);
        collectionMap.remove(collIdentifier);
    }

    public void evictCollectionDocuments(String dbName, String collName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        collectionMap.remove(collIdentifier);
    }

    public void evictPkIndex(String dbName, String collName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        pkIndexMap.remove(collIdentifier);
    }

    public void evictFieldIndex(String dbName, String collName, String indexKey) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var indexes = fieldIndexMap.get(collIdentifier);
        if (indexes != null) {
            indexes.remove(indexKey);
            if (indexes.isEmpty()) {
                fieldIndexMap.remove(collIdentifier);
            }
        }
    }

    public List<CacheableResource> listCacheableResources() {
        final var result = new ArrayList<CacheableResource>();
        for (var entry : pkIndexMap.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2 || Globals.ADMIN_DB_NAME.equals(parts[0]))
                continue;
            result.add(new CacheableResource(AccessKind.PK_INDEX, parts[0], parts[1], null,
                    estimatePkIndexSize(entry.getValue())));
        }
        for (var entry : collectionMap.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2 || Globals.ADMIN_DB_NAME.equals(parts[0]))
                continue;
            result.add(new CacheableResource(AccessKind.COLLECTION, parts[0], parts[1], null,
                    estimateCollectionSize(entry.getValue())));
        }
        for (var entry : fieldIndexMap.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2 || Globals.ADMIN_DB_NAME.equals(parts[0]))
                continue;
            for (var inner : entry.getValue().entrySet()) {
                result.add(new CacheableResource(AccessKind.FIELD_INDEX, parts[0], parts[1], inner.getKey(),
                        estimateFieldIndexSize(inner.getValue())));
            }
        }
        return result;
    }

    private long estimatePkIndexSize(List<PkIndexEntry> entries) {
        if (entries == null)
            return 0L;
        return (long) entries.size() * ESTIMATED_PK_ENTRY_BYTES;
    }

    private long estimateCollectionSize(Map<String, DbEntry> entries) {
        if (entries == null)
            return 0L;
        long total = 0L;
        for (var e : entries.values()) {
            total += e.byteSize();
        }
        return total;
    }

    private long estimateFieldIndexSize(List<FieldIndexEntry<?>> entries) {
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

    public boolean hasLoadedIndex(String dbName, String collName, String fieldName) {
        final var fieldIndexes = fieldIndexMap.get(Cache.getCollectionIdentifier(dbName, collName));
        if (fieldIndexes != null) {
            return fieldIndexes.containsKey(fieldName);
        }
        return false;
    }
}
