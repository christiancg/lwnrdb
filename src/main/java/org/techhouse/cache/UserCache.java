package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
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

public class UserCache {
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private final UsageTracker usageTracker = IocContainer.get(UsageTracker.class);
    private final Map<String, List<PkIndexEntry>> pkIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DbEntry>> collectionMap = new ConcurrentHashMap<>();
    private final UserCacheSizes sizes = new UserCacheSizes();
    public List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String dbName, String collName) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var primaryKeyIndex = pkIndexMap.get(collectionIdentifier);
        if (primaryKeyIndex == null) {
            final var fromDisk = fs.readWholePkIndexFile(dbName, collName);
            if (!isCachingDisabled(dbName) && rl.holdsCollectionLock(dbName, collName)) {
                admitPkIndex(dbName, collectionIdentifier, fromDisk);
            }
            return fromDisk;
        }
        if (rl.holdsCollectionLock(dbName, collName)) {
            return primaryKeyIndex;
        }
        return snapshotForLockFreeReader(dbName, collName, primaryKeyIndex);
    }

    private void admitPkIndex(String dbName, String collectionIdentifier, List<PkIndexEntry> fromDisk) {
        final var bytesPerEntry = CacheSizeEstimator.pkIndexBytesPerEntry(fromDisk);
        if (shouldCache(dbName, () -> bytesPerEntry * fromDisk.size())) {
            sizes.recordPkBytesPerEntry(collectionIdentifier, bytesPerEntry);
            pkIndexMap.put(collectionIdentifier, fromDisk);
        }
    }

    private List<PkIndexEntry> snapshotForLockFreeReader(String dbName, String collName, List<PkIndexEntry> shared)
            throws IOException {
        var held = false;
        try {
            held = rl.tryLockRead(dbName, collName, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!held) {
            return fs.readWholePkIndexFile(dbName, collName);
        }
        try {
            return new ArrayList<>(shared);
        } finally {
            rl.releaseRead(dbName, collName);
        }
    }

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
        return isReservedDatabase(dbName) || configuration.isCachingDisabled();
    }

    private static boolean isReservedDatabase(String dbName) {
        return Globals.ADMIN_DB_NAME.equals(dbName) || Globals.ADMIN_PAGES_DB_NAME.equals(dbName);
    }

    private boolean shouldCache(String dbName, LongSupplier estimatedBytes) {
        if (isCachingDisabled(dbName)) {
            return false;
        }
        if (configuration.isCacheUnlimited()) {
            return true;
        }
        return footprintBytes() + estimatedBytes.getAsLong() <= configuration.getMaxMemoryBytes();
    }

    private long footprintBytes() {
        var total = 0L;
        for (final var resource : listCacheableResources()) {
            total += resource.estimatedSizeBytes();
        }
        return total;
    }

    // Callers must hold the field's index lock (read for queries, write for the background index
    // writer): the returned entries alias the cached id sets and must be copied before releasing it.
    public <T> List<FieldIndexEntry<T>> getFieldIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, Class<T> indexType) throws IOException {
        return loadIndex(dbName, collName, Cache.getIndexIdentifier(fieldName, indexType),
                () -> fs.readWholeFieldIndexFiles(dbName, collName, fieldName, indexType), true);
    }

    public List<FieldIndexEntry<String>> getHashIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, IndexKind kind) throws IOException {
        return loadIndex(dbName, collName, Cache.getHashIndexIdentifier(fieldName, kind.label()),
                () -> fs.readWholeHashIndexFile(dbName, collName, fieldName, kind), true);
    }

    public <T> List<FieldIndexEntry<T>> getFieldIndexForMaintenance(String dbName, String collName, String fieldName,
            Class<T> indexType) throws IOException {
        return loadIndex(dbName, collName, Cache.getIndexIdentifier(fieldName, indexType),
                () -> fs.readWholeFieldIndexFiles(dbName, collName, fieldName, indexType), false);
    }

    public List<FieldIndexEntry<String>> getHashIndexForMaintenance(String dbName, String collName, String fieldName,
            IndexKind kind) throws IOException {
        return loadIndex(dbName, collName, Cache.getHashIndexIdentifier(fieldName, kind.label()),
                () -> fs.readWholeHashIndexFile(dbName, collName, fieldName, kind), false);
    }

    private <T> List<FieldIndexEntry<T>> loadIndex(String dbName, String collName, String indexIdentifier,
            IndexLoader<T> loader, boolean admit) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var index = fieldIndexMap.get(collectionIdentifier);
        if (index == null || !index.containsKey(indexIdentifier)) {
            final var indexEntries = loader.load();
            if (indexEntries == null) {
                return null;
            }
            if (admit && rl.holdsCollectionLock(dbName, collName) && shouldCache(dbName,
                    () -> CacheSizeEstimator.estimateFieldIndexSize(new ArrayList<>(indexEntries)))) {
                fieldIndexMap.computeIfAbsent(collectionIdentifier, _ -> new ConcurrentHashMap<>()).put(indexIdentifier,
                        new ArrayList<>(indexEntries));
            }
            return indexEntries;
        }
        final var existingIndex = index.get(indexIdentifier);
        if (existingIndex == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        final var typed = (List<FieldIndexEntry<T>>) (List<?>) existingIndex;
        return typed;
    }

    private interface IndexLoader<T> {
        List<FieldIndexEntry<T>> load() throws IOException;
    }

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
            recordFieldIndexAccess(dbName, collName, fieldName);
            return snapshot;
        } finally {
            rl.releaseIndexRead(dbName, collName, fieldName);
        }
    }

    public void recordFieldIndexAccess(String dbName, String collName, String fieldName) {
        usageTracker.recordAccess(AccessKind.FIELD_INDEX, dbName, collName, fieldName);
    }

    public void addEntryToCache(String dbName, String collName, DbEntry entry) {
        if (isReservedDatabase(dbName)) {
            return;
        }
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        final var existing = collectionMap.get(collId);
        // Refreshing a resident document must be unconditional: the admission check governs only new
        // residents, and skipping the refresh would leave a stale copy behind.
        if (existing != null && existing.containsKey(entry.get_id())) {
            sizes.trackPut(collId, existing.put(entry.get_id(), entry), entry);
            return;
        }
        if (!shouldCache(dbName, () -> CacheSizeEstimator.estimateCollectionEntry(entry))) {
            return;
        }
        final var target = collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>());
        sizes.trackPut(collId, target.put(entry.get_id(), entry), entry);
    }

    public void addEntriesToCache(String dbName, String collName, List<DbEntry> entries) {
        if (isReservedDatabase(dbName)) {
            return;
        }
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        final var existing = collectionMap.get(collId);
        final var newEntries = new ArrayList<DbEntry>();
        for (var e : entries) {
            if (existing != null && existing.containsKey(e.get_id())) {
                sizes.trackPut(collId, existing.put(e.get_id(), e), e);
            } else {
                newEntries.add(e);
            }
        }
        if (newEntries.isEmpty()) {
            return;
        }
        if (!shouldCache(dbName, () -> collectionEntriesSize(newEntries))) {
            return;
        }
        final var target = collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>());
        for (var e : newEntries) {
            sizes.trackPut(collId, target.put(e.get_id(), e), e);
        }
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
            final var fetched = entry;
            if (rl.holdsCollectionLock(dbName, collName)
                    && shouldCache(dbName, () -> CacheSizeEstimator.estimateCollectionEntry(fetched))) {
                sizes.trackPut(collectionIdentifier, coll.put(pk, entry), entry);
            }
        }
        return entry;
    }

    public Map<String, DbEntry> getCachedCollection(String dbName, String collName) {
        return collectionMap.get(Cache.getCollectionIdentifier(dbName, collName));
    }

    public Map<String, DbEntry> admitWholeCollection(String dbName, String collName, Map<String, DbEntry> loaded) {
        if (!isCachingDisabled(dbName)) {
            final var asMap = new ConcurrentHashMap<>(loaded);
            final var size = CacheSizeEstimator.estimateCollectionSize(asMap);
            if (shouldCache(dbName, () -> size)) {
                final var collId = Cache.getCollectionIdentifier(dbName, collName);
                collectionMap.put(collId, asMap);
                sizes.resetCollectionBytes(collId, size);
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
        // Detached copies: shiftPkPositionsAfterCompaction mutates position in place and could
        // otherwise move the offset between here and the read.
        final var toRead = new ArrayList<PkIndexEntry>();
        for (var id : missingIds) {
            final var pos = Collections.binarySearch(pkIndex, id);
            if (pos >= 0) {
                toRead.add(pkIndex.get(pos).detachedCopy());
            }
        }
        if (toRead.isEmpty()) {
            return result;
        }
        final var read = fs.getByIndexEntries(toRead);
        result.addAll(read);
        if (!cachingDisabled && rl.holdsCollectionLock(dbName, collName)) {
            if (shouldCache(dbName, () -> collectionEntriesSize(read))) {
                final var coll = collectionMap.computeIfAbsent(collectionIdentifier, _ -> new ConcurrentHashMap<>());
                for (var e : read) {
                    sizes.trackPut(collectionIdentifier, coll.put(e.get_id(), e), e);
                }
            }
        }
        return result;
    }

    private static long collectionEntriesSize(List<DbEntry> entries) {
        var total = 0L;
        for (final var e : entries) {
            total += CacheSizeEstimator.estimateCollectionEntry(e);
        }
        return total;
    }

    public void evictEntry(String dbName, String collName, String pk) {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var coll = collectionMap.get(collectionIdentifier);
        if (coll != null) {
            sizes.trackRemoved(collectionIdentifier, coll.remove(pk));
        }
    }

    public void evictDatabase(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        removeByPrefix(pkIndexMap, prefix);
        removeByPrefix(collectionMap, prefix);
        removeByPrefix(fieldIndexMap, prefix);
        sizes.forgetByPrefix(prefix);
    }

    private static void removeByPrefix(Map<String, ?> map, String prefix) {
        map.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void evictCollection(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        pkIndexMap.remove(collIdentifier);
        collectionMap.remove(collIdentifier);
        fieldIndexMap.remove(collIdentifier);
        sizes.forgetCollection(collIdentifier);
    }

    public void evictCollectionDocuments(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        collectionMap.remove(collIdentifier);
        sizes.forgetCollectionDocuments(collIdentifier);
    }

    public void evictPkIndex(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        pkIndexMap.remove(collIdentifier);
        sizes.forgetPkIndex(collIdentifier);
    }

    public void evictFieldIndex(String dbName, String collName, String indexKey) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var indexes = fieldIndexMap.get(collIdentifier);
        if (indexes != null) {
            indexes.remove(indexKey);
            sizes.forgetFieldIndex(collIdentifier, indexKey);
            if (indexes.isEmpty()) {
                fieldIndexMap.remove(collIdentifier);
            }
        }
    }

    // The background index writer calls this after rewriting a field's .idx files, so the next read
    // reloads from disk instead of answering from the now-stale cached lists.
    public void evictFieldIndexAllTypes(String dbName, String collName, String fieldName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var indexes = fieldIndexMap.get(collIdentifier);
        if (indexes != null) {
            final var prefix = fieldName + Globals.COLL_IDENTIFIER_SEPARATOR;
            indexes.keySet().removeIf(key -> key.equals(fieldName) || key.startsWith(prefix));
            sizes.forgetFieldIndexAllTypes(collIdentifier, fieldName);
            if (indexes.isEmpty()) {
                fieldIndexMap.remove(collIdentifier);
            }
        }
    }

    public List<CacheableResource> listCacheableResources() {
        final var result = new ArrayList<CacheableResource>();
        for (var entry : pkIndexMap.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2)
                continue;
            result.add(new CacheableResource(AccessKind.PK_INDEX, parts[0], parts[1], null,
                    sizes.pkIndexBytes(entry.getKey(), entry.getValue().size())));
        }
        for (var entry : collectionMap.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2)
                continue;
            result.add(new CacheableResource(AccessKind.COLLECTION, parts[0], parts[1], null,
                    sizes.trackedBytes(entry.getKey(), entry.getValue())));
        }
        for (var entry : fieldIndexMap.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2)
                continue;
            for (var inner : entry.getValue().entrySet()) {
                result.add(new CacheableResource(AccessKind.FIELD_INDEX, parts[0], parts[1], inner.getKey(),
                        estimateFieldIndexUnderLock(parts[0], parts[1], inner.getKey(), inner.getValue())));
            }
        }
        return result;
    }

    private long estimateFieldIndexUnderLock(String dbName, String collName, String indexKey,
            List<FieldIndexEntry<?>> entries) {
        final var fieldName = CacheableResource.indexedFieldOf(indexKey);
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        if (!rl.tryLockIndexRead(dbName, collName, fieldName)) {
            return sizes.rememberedFieldIndexBytes(collIdentifier, indexKey, entries.size());
        }
        try {
            return sizes.measureFieldIndex(collIdentifier, indexKey, entries);
        } finally {
            rl.releaseIndexRead(dbName, collName, fieldName);
        }
    }

    public boolean hasLoadedIndex(String dbName, String collName, String fieldName) {
        final var fieldIndexes = fieldIndexMap.get(Cache.getCollectionIdentifier(dbName, collName));
        if (fieldIndexes == null) {
            return false;
        }
        final var prefix = fieldName + Globals.COLL_IDENTIFIER_SEPARATOR;
        return fieldIndexes.keySet().stream().anyMatch(key -> key.equals(fieldName) || key.startsWith(prefix));
    }
}
