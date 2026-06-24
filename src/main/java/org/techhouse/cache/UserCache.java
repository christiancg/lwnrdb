package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.JsonUtils;
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
    private final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
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

    /**
     * Keeps the cached PK index positions consistent after a single-entry page compaction: every
     * cached entry on {@code page} whose position is greater than {@code removedPosition} shifted
     * toward the start of the file by {@code removedLength}. Mutates the cached entries in place so
     * any in-flight operation holding a reference observes the corrected position. No-op when the
     * collection's PK index is not cached.
     */
    public void shiftPkPositionsAfterCompaction(String dbName, String collName, long page, long removedPosition,
            long removedLength) {
        final var primaryKeyIndex = pkIndexMap.get(Cache.getCollectionIdentifier(dbName, collName));
        if (primaryKeyIndex == null) {
            return;
        }
        for (final var entry : primaryKeyIndex) {
            if (entry.getPage() == page && entry.getPosition() > removedPosition) {
                entry.setPosition(entry.getPosition() - removedLength);
            }
        }
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

    // Contract: callers must hold the field's index lock (read lock for queries via
    // ResourceLocking#lockIndexRead, write lock for the background index writer). The returned
    // entries alias the cached id sets, so a caller that lets them escape lock scope (e.g. into a
    // lazily-consumed stream) must take its own snapshot of the ids it needs before releasing.
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

    // Hash index counterpart of getFieldIndexAndLoadIfNecessary: loads (and caches) the element-match
    // entries (value = hex hash) for the object or array index family. Cached under the
    // field|Object / field|Array identifier, kept apart from the scalar/custom index entries. Same
    // locking contract as getFieldIndexAndLoadIfNecessary: callers must hold the field's index lock.
    public List<FieldIndexEntry<String>> getHashIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, IndexKind kind) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var indexIdentifier = Cache.getIndexIdentifier(fieldName, kind.label());
        var index = fieldIndexMap.get(collectionIdentifier);
        List<FieldIndexEntry<String>> indexEntries = null;
        if (index == null || index.keySet().stream().noneMatch(string -> string.contains(indexIdentifier))) {
            indexEntries = fs.readWholeHashIndexFile(dbName, collName, fieldName, kind);
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
                                fieldIndexEntry.getCollectionName(), (String) fieldIndexEntry.getValue(),
                                fieldIndexEntry.getIds()))
                        .collect(Collectors.toList());
            }
        }
        return indexEntries;
    }

    // Resolves the matching ids for a single field operator under the field's index read lock and
    // returns a detached snapshot. The read lock serializes against the background index writer (the
    // searched id sets are mutated by it), and the copy keeps the result safe once the lock is
    // released and the ids flow into a lazily-consumed stream.
    public <T> Set<String> getIdsFromIndex(String dbName, String collName, String fieldName, FieldOperator operator,
            T value) throws IOException {
        try {
            rl.lockIndexRead(dbName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            final var result = doGetIdsFromIndex(dbName, collName, fieldName, operator, value);
            if (result == null) {
                return null;
            }
            final var snapshot = new HashSet<>(result);
            if (!Globals.ADMIN_DB_NAME.equals(dbName)) {
                recordFieldIndexAccess(dbName, collName, fieldName);
            }
            return snapshot;
        } finally {
            rl.releaseIndexRead(dbName, collName, fieldName);
        }
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
            case JsonObject obj -> {
                final var opType = operator.getFieldOperatorType();
                if (opType == FieldOperatorType.EQUALS || opType == FieldOperatorType.NOT_EQUALS) {
                    final var hashIndex = getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, IndexKind.OBJECT);
                    yield hashIndex != null
                            ? SearchUtils.findingByOperator(hashIndex, opType, JsonUtils.hashElement(obj))
                            : null;
                } else {
                    yield null;
                }
            }
            case JsonArray arr -> {
                final var opType = operator.getFieldOperatorType();
                yield switch (opType) {
                    // EQUALS/NOT_EQUALS against an array operand means element-match on the whole array.
                    case EQUALS, NOT_EQUALS -> {
                        final var hashIndex = getHashIndexAndLoadIfNecessary(dbName, collName, fieldName,
                                IndexKind.ARRAY);
                        yield hashIndex != null
                                ? SearchUtils.findingByOperator(hashIndex, opType, JsonUtils.hashElement(arr))
                                : null;
                    }
                    // IN/NOT_IN against an array operand means membership in the list of candidate values.
                    case IN, NOT_IN -> getIdsFromInList(dbName, collName, fieldName, operator, arr);
                    default -> null;
                };
            }
            default -> throw new IllegalStateException("Unexpected value: " + value);
        };
    }

    // Resolves IN/NOT_IN against the list of candidate values. Primitive elements use their scalar /
    // custom index; object and array elements are hashed and resolved through the element-match hash
    // index of the matching kind. The candidate list is assumed homogeneous (dispatched off its
    // first element), mirroring how the scalar path already worked.
    @SuppressWarnings("unchecked")
    private <T> Set<String> getIdsFromInList(String dbName, String collName, String fieldName, FieldOperator operator,
            JsonArray arr) throws IOException {
        if (arr.isEmpty()) {
            return null;
        }
        final var firstElement = arr.get(0);
        final var listStream = arr.asList().stream();
        final var opType = operator.getFieldOperatorType();
        if (firstElement.isJsonObject()) {
            final var hashIndex = getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, IndexKind.OBJECT);
            return hashIndex != null
                    ? SearchUtils.findingInNotIn(hashIndex, opType, listStream.map(JsonUtils::hashElement).toList())
                    : null;
        } else if (firstElement.isJsonArray()) {
            final var hashIndex = getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, IndexKind.ARRAY);
            return hashIndex != null
                    ? SearchUtils.findingInNotIn(hashIndex, opType, listStream.map(JsonUtils::hashElement).toList())
                    : null;
        } else if (firstElement.isJsonPrimitive()) {
            final var prim = firstElement.asJsonPrimitive();
            return switch (prim) {
                case JsonCustom<?> c -> {
                    final var customClass = CustomTypeFactory.getCustomTypes().get(c.getCustomTypeName());
                    final var customIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                            (Class<T>) customClass);
                    yield customIndex != null
                            ? SearchUtils.findingInNotIn(customIndex, opType,
                                    (List<T>) listStream.map(JsonBaseElement::asJsonCustom).toList())
                            : null;
                }
                case JsonString ignored -> {
                    final var stringIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, String.class);
                    yield stringIndex != null
                            ? SearchUtils.findingInNotIn(stringIndex, opType,
                                    listStream.map(x -> x.asJsonString().getValue()).toList())
                            : null;
                }
                case JsonNumber ignored -> {
                    final var numberIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class);
                    yield numberIndex != null
                            ? SearchUtils.findingInNotIn(numberIndex, opType,
                                    listStream.map(x -> x.asJsonNumber().getValue()).toList())
                            : null;
                }
                case JsonBoolean ignored -> {
                    final var booleanIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                            Boolean.class);
                    yield booleanIndex != null
                            ? SearchUtils.findingInNotIn(booleanIndex, opType,
                                    listStream.map(x -> x.asJsonBoolean().getValue()).toList())
                            : null;
                }
                default -> null;
            };
        }
        return null;
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

    // Evicts every per-type list cached for a field (field|Number, field|String, field|Object, ...).
    // Called by the background index writer after rewriting a field's .idx files so the next read
    // reloads the up-to-date index from disk. Index keys are field|TypeLabel and field names cannot
    // contain the separator, so the prefix match is unambiguous.
    public void evictFieldIndexAllTypes(String dbName, String collName, String fieldName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var indexes = fieldIndexMap.get(collIdentifier);
        if (indexes != null) {
            final var prefix = fieldName + Globals.COLL_IDENTIFIER_SEPARATOR;
            indexes.keySet().removeIf(key -> key.equals(fieldName) || key.startsWith(prefix));
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
