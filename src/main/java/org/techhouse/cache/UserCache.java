package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
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
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.FieldOperator;

/**
 * Cache for user document and index entries: the PK index map, field index map
 * and full document map. Unlike {@link AdminCache}, these caches are
 * memory-managed — admission is gated by {@link MemoryManagement} and entries
 * are evicted by the LFU sweep. The {@link Cache} facade coordinates this cache
 * with the admin page metadata for the cross-cutting read/stream methods.
 */
public class UserCache {
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private final UsageTracker usageTracker = IocContainer.get(UsageTracker.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private final Map<String, List<PkIndexEntry>> pkIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DbEntry>> collectionMap = new ConcurrentHashMap<>();
    public List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String dbName, String collName) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        var primaryKeyIndex = pkIndexMap.get(collectionIdentifier);
        if (primaryKeyIndex == null) {
            primaryKeyIndex = fs.readWholePkIndexFile(dbName, collName);
            if (shouldCache(dbName, CacheSizeEstimator.estimatePkIndexSize(primaryKeyIndex))) {
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
        if (configuration.isCachingDisabled()) {
            return false;
        }
        if (configuration.isCacheUnlimited()) {
            return true;
        }
        return footprintBytes() + estimatedBytes <= configuration.getMaxMemoryBytes();
    }

    private long footprintBytes() {
        var total = 0L;
        for (final var resource : listCacheableResources()) {
            total += resource.estimatedSizeBytes();
        }
        return total;
    }

    // Contract: callers must hold the field's index lock (read lock for queries via
    // ResourceLocking#lockIndexRead, write lock for the background index writer). The returned
    // entries alias the cached id sets, so a caller that lets them escape lock scope (e.g. into a
    // lazily-consumed stream) must take its own snapshot of the ids it needs before releasing.
    public <T> List<FieldIndexEntry<T>> getFieldIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, Class<T> indexType) throws IOException {
        return loadIndex(dbName, collName, Cache.getIndexIdentifier(fieldName, indexType),
                () -> fs.readWholeFieldIndexFiles(dbName, collName, fieldName, indexType), indexType::cast);
    }

    public List<FieldIndexEntry<String>> getHashIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, IndexKind kind) throws IOException {
        return loadIndex(dbName, collName, Cache.getIndexIdentifier(fieldName, kind.label()),
                () -> fs.readWholeHashIndexFile(dbName, collName, fieldName, kind), value -> (String) value);
    }

    // The caller must hold the field's index read lock: the cached id sets are mutated by the background
    // index writer, and the entries returned here alias them until the caller copies them.
    private <T> List<FieldIndexEntry<T>> loadIndex(String dbName, String collName, String indexIdentifier,
            IndexLoader<T> loader, Function<Object, T> cast) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        var index = fieldIndexMap.get(collectionIdentifier);
        if (index == null || !index.containsKey(indexIdentifier)) {
            final var indexEntries = loader.load();
            if (indexEntries == null) {
                return null;
            }
            if (index == null) {
                index = new ConcurrentHashMap<>();
            }
            if (shouldCache(dbName, CacheSizeEstimator.estimateFieldIndexSize(new ArrayList<>(indexEntries)))) {
                index.put(indexIdentifier, new ArrayList<>(indexEntries));
                fieldIndexMap.put(collectionIdentifier, index);
            }
            return indexEntries;
        }
        final var existingIndex = index.get(indexIdentifier);
        if (existingIndex == null) {
            return null;
        }
        return existingIndex.stream().map(entry -> new FieldIndexEntry<>(entry.getDatabaseName(),
                entry.getCollectionName(), cast.apply(entry.getValue()), entry.getIds())).collect(Collectors.toList());
    }

    private interface IndexLoader<T> {
        List<FieldIndexEntry<T>> load() throws IOException;
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
            final var result = IndexLookupResolver.doGetIdsFromIndex(this, dbName, collName, fieldName, operator,
                    value);
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
        usageTracker.recordAccess(AccessKind.FIELD_INDEX, dbName, collName, fieldName);
        taskManager.submitBackgroundTask(new CollectionUsageEvent(AccessKind.FIELD_INDEX, dbName, collName, fieldName,
                System.currentTimeMillis()));
    }

    public void addEntryToCache(String dbName, String collName, DbEntry entry) {
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        final var existing = collectionMap.get(collId);
        // Refreshing an already-resident document must be unconditional: it replaces a document already
        // counted against the budget, so the admission check (which only governs admitting NEW residents)
        // must not drop it and leave a stale copy behind.
        if (existing != null && existing.containsKey(entry.get_id())) {
            existing.put(entry.get_id(), entry);
            return;
        }
        if (!shouldCache(dbName, entry.byteSize())) {
            return;
        }
        collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>()).put(entry.get_id(), entry);
    }

    public void addEntriesToCache(String dbName, String collName, List<DbEntry> entries) {
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        final var existing = collectionMap.get(collId);
        // Already-resident documents are refreshed unconditionally (see addEntryToCache); only genuinely
        // new documents are subject to the admission check.
        final var newEntries = new ArrayList<DbEntry>();
        for (var e : entries) {
            if (existing != null && existing.containsKey(e.get_id())) {
                existing.put(e.get_id(), e);
            } else {
                newEntries.add(e);
            }
        }
        if (newEntries.isEmpty()) {
            return;
        }
        long total = 0L;
        for (var e : newEntries) {
            total += e.byteSize();
        }
        if (!shouldCache(dbName, total)) {
            return;
        }
        collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>())
                .putAll(newEntries.stream().collect(Collectors.toMap(DbEntry::get_id, o -> o, (_, b) -> b)));
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
            if (shouldCache(dbName, CacheSizeEstimator.estimateCollectionSize(asMap))) {
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
        // toRead holds detached copies so a concurrent shiftPkPositionsAfterCompaction
        // (which mutates position in place) cannot move the offset between here and the read.
        final var toRead = new ArrayList<PkIndexEntry>();
        for (var id : missingIds) {
            final var pos = Collections.binarySearch(pkIndex, id);
            if (pos >= 0) {
                final var e = pkIndex.get(pos);
                toRead.add(new PkIndexEntry(e.getDatabaseName(), e.getCollectionName(), e.getValue(), e.getPosition(),
                        e.getLength(), e.getPage(), e.getVersion()));
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
        // Keys are "db|coll"; append the separator so "foo" does not match "foobar|...".
        final var toRemove = collectionMap.keySet().stream()
                .filter(s -> s.startsWith(dbName + Globals.COLL_IDENTIFIER_SEPARATOR)).toList();
        for (var entryKeyToRemove : toRemove) {
            pkIndexMap.remove(entryKeyToRemove);
            collectionMap.remove(entryKeyToRemove);
            fieldIndexMap.remove(entryKeyToRemove);
        }
    }

    public void evictCollection(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        pkIndexMap.remove(collIdentifier);
        collectionMap.remove(collIdentifier);
        fieldIndexMap.remove(collIdentifier);
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
                    CacheSizeEstimator.estimatePkIndexSize(entry.getValue())));
        }
        for (var entry : collectionMap.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2 || Globals.ADMIN_DB_NAME.equals(parts[0]))
                continue;
            result.add(new CacheableResource(AccessKind.COLLECTION, parts[0], parts[1], null,
                    CacheSizeEstimator.estimateCollectionSize(entry.getValue())));
        }
        for (var entry : fieldIndexMap.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2 || Globals.ADMIN_DB_NAME.equals(parts[0]))
                continue;
            for (var inner : entry.getValue().entrySet()) {
                result.add(new CacheableResource(AccessKind.FIELD_INDEX, parts[0], parts[1], inner.getKey(),
                        CacheSizeEstimator.estimateFieldIndexSize(inner.getValue())));
            }
        }
        return result;
    }

    public boolean hasLoadedIndex(String dbName, String collName, String fieldName) {
        final var fieldIndexes = fieldIndexMap.get(Cache.getCollectionIdentifier(dbName, collName));
        if (fieldIndexes != null) {
            return fieldIndexes.containsKey(fieldName);
        }
        return false;
    }
}
