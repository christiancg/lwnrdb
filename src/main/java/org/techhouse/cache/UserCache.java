package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.VacuumedPage;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.FieldOperator;

public class UserCache {
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private final UsageTracker usageTracker = IocContainer.get(UsageTracker.class);
    private final Map<String, List<PkIndexEntry>> pkIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexMap = new ConcurrentHashMap<>();
    private final FieldIndexLookups fieldLookups = new FieldIndexLookups();
    private final Map<String, Map<String, DbEntry>> collectionMap = new ConcurrentHashMap<>();
    private final UserCacheSizes sizes = new UserCacheSizes();
    private final WholeLoads wholeLoads = new WholeLoads(() -> fs,
            configuration.intValue(ConfigKey.TRANSIENT_LOAD_PERMITS));
    private final OverBudgetSignal overBudget = new OverBudgetSignal(configuration);

    public List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String dbName, String collName) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var primaryKeyIndex = pkIndexMap.get(collectionIdentifier);
        if (primaryKeyIndex == null) {
            final var locked = rl.holdsCollectionLock(dbName, collName);
            final var fromDisk = wholeLoads.pkIndex(dbName, collName, locked,
                    bytes -> loadsAdmitted(dbName, collName, bytes));
            if (fromDisk != null && !isCachingDisabled(dbName) && locked) {
                return admitPkIndex(dbName, collectionIdentifier, fromDisk);
            }
            return fromDisk;
        }
        if (rl.holdsCollectionLock(dbName, collName)) {
            return primaryKeyIndex;
        }
        return snapshotForLockFreeReader(dbName, collName, primaryKeyIndex);
    }

    private List<PkIndexEntry> admitPkIndex(String dbName, String collectionIdentifier, List<PkIndexEntry> fromDisk) {
        final var admitted = pkIndexMap.get(collectionIdentifier);
        if (admitted != null) {
            return admitted;
        }
        final var bytesPerEntry = CacheSizeEstimator.pkIndexBytesPerEntry(fromDisk);
        if (!shouldCache(dbName, () -> bytesPerEntry * fromDisk.size())) {
            return fromDisk;
        }
        final var cached = PkIndexList.of(fromDisk);
        sizes.recordPkBytesPerEntry(collectionIdentifier, bytesPerEntry);
        final var raced = pkIndexMap.putIfAbsent(collectionIdentifier, cached);
        return raced != null ? raced : cached;
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
            return wholeLoads.transientPkIndex(dbName, collName);
        }
        try {
            return new ArrayList<>(shared);
        } finally {
            rl.releaseRead(dbName, collName);
        }
    }

    public void repositionAfterVacuum(VacuumedPage vacuumed) {
        final var primaryKeyIndex = pkIndexMap
                .get(Cache.getCollectionIdentifier(vacuumed.dbName(), vacuumed.collName()));
        if (primaryKeyIndex != null) {
            vacuumed.reposition(primaryKeyIndex);
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

    private boolean loadsAdmitted(String dbName, String collName, long estimatedBytes) {
        return isReservedDatabase(dbName)
                || rl.holdsCollectionLock(dbName, collName) && shouldCache(dbName, () -> estimatedBytes);
    }

    private long footprintBytes() {
        return sizes.rememberedFootprint(pkIndexMap, collectionMap, fieldIndexMap) + fieldLookups.footprintBytes();
    }

    // Callers must hold the field's index lock (read for queries, write for the background index
    // writer): the returned entries alias the cached id sets and must be copied before releasing it.
    public <T> List<FieldIndexEntry<T>> getFieldIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, Class<T> indexType) throws IOException {
        return loadIndex(dbName, collName, Cache.getIndexIdentifier(fieldName, indexType), () -> wholeLoads
                .fieldIndex(dbName, collName, fieldName, indexType, bytes -> loadsAdmitted(dbName, collName, bytes)));
    }

    public List<FieldIndexEntry<String>> getHashIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, IndexKind kind) throws IOException {
        return loadIndex(dbName, collName, Cache.getHashIndexIdentifier(fieldName, kind.label()), () -> wholeLoads
                .hashIndex(dbName, collName, fieldName, kind, bytes -> loadsAdmitted(dbName, collName, bytes)));
    }

    public List<FieldIndexEntry<?>> residentFieldIndex(String dbName, String collName, String indexIdentifier) {
        final var index = fieldIndexMap.get(Cache.getCollectionIdentifier(dbName, collName));
        return index == null ? null : index.get(indexIdentifier);
    }

    public void admitMaintainedFieldIndex(String dbName, String collName, String indexIdentifier,
            List<FieldIndexEntry<?>> written) {
        if (rl.holdsCollectionLock(dbName, collName) && residentFieldIndex(dbName, collName, indexIdentifier) == null) {
            admitFieldIndex(dbName, collName, indexIdentifier, written);
        }
    }

    public void rememberFieldIndexBatch(String dbName, String collName, String indexIdentifier, int previousCount,
            long deltaBytes, int changedIds) {
        final var resident = residentFieldIndex(dbName, collName, indexIdentifier);
        if (resident != null) {
            sizes.adjustFieldIndex(Cache.getCollectionIdentifier(dbName, collName), indexIdentifier, previousCount,
                    resident.size(), deltaBytes, changedIds);
            signalIfOverBudget();
        }
    }

    public void onOverBudget(Runnable listener) {
        overBudget.listen(listener);
    }

    public void signalIfOverBudget() {
        overBudget.signalIfOver(this::footprintBytes);
    }

    public FieldIndexLookup fieldIndexLookup(String dbName, String collName, String fieldName) {
        return fieldLookups.get(Cache.getCollectionIdentifier(dbName, collName), fieldName);
    }

    public void admitFieldIndexLookup(String dbName, String collName, String fieldName, FieldIndexLookup lookup) {
        if (!rl.holdsCollectionLock(dbName, collName) || isCachingDisabled(dbName)) {
            evictFieldIndexLookup(dbName, collName, fieldName);
            return;
        }
        final var resident = fieldIndexLookup(dbName, collName, fieldName);
        if (resident == lookup
                || shouldCache(dbName, () -> CacheSizeEstimator.estimateFieldIndexLookupSize(lookup.size()))) {
            fieldLookups.put(Cache.getCollectionIdentifier(dbName, collName), fieldName, lookup);
            signalIfOverBudget();
        } else {
            evictFieldIndexLookup(dbName, collName, fieldName);
        }
    }

    public void evictFieldIndexLookup(String dbName, String collName, String fieldName) {
        fieldLookups.evict(Cache.getCollectionIdentifier(dbName, collName), fieldName);
    }

    private <T> List<FieldIndexEntry<T>> loadIndex(String dbName, String collName, String indexIdentifier,
            IndexLoader<T> loader) throws IOException {
        final var existingIndex = residentFieldIndex(dbName, collName, indexIdentifier);
        if (existingIndex != null) {
            @SuppressWarnings("unchecked")
            final var typed = (List<FieldIndexEntry<T>>) (List<?>) existingIndex;
            return typed;
        }
        final var indexEntries = loader.load();
        if (indexEntries != null && rl.holdsCollectionLock(dbName, collName)) {
            admitFieldIndex(dbName, collName, indexIdentifier, new ArrayList<>(indexEntries));
        }
        return indexEntries;
    }

    private void admitFieldIndex(String dbName, String collName, String indexIdentifier,
            List<FieldIndexEntry<?>> copy) {
        if (isCachingDisabled(dbName)) {
            return;
        }
        final var measured = CacheSizeEstimator.estimateFieldIndexSize(copy);
        if (shouldCache(dbName, () -> measured)) {
            final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
            if (fieldIndexMap.computeIfAbsent(collectionIdentifier, _ -> new ConcurrentHashMap<>())
                    .putIfAbsent(indexIdentifier, copy) == null) {
                sizes.rememberMeasuredFieldIndex(collectionIdentifier, indexIdentifier, measured, copy);
            }
        }
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
            recordFieldIndexAccess(dbName, collName, fieldName);
            return result;
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
        if (!shouldCache(dbName, () -> CacheSizeEstimator.estimateCollectionEntries(newEntries))) {
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
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        final var result = new ArrayList<DbEntry>(ids.size());
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
        final var toRead = new ArrayList<PkIndexEntry>();
        for (var id : missingIds) {
            final var pos = PkIndexList.positionOf(pkIndex, id);
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
            if (shouldCache(dbName, () -> CacheSizeEstimator.estimateCollectionEntries(read))) {
                final var coll = collectionMap.computeIfAbsent(collectionIdentifier, _ -> new ConcurrentHashMap<>());
                for (var e : read) {
                    sizes.trackPut(collectionIdentifier, coll.put(e.get_id(), e), e);
                }
            }
        }
        return result;
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
        fieldLookups.evictByPrefix(prefix);
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
        fieldLookups.evictCollection(collIdentifier);
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
        evictFieldIndexLookup(dbName, collName, CacheableResource.indexedFieldOf(indexKey));
        if (Cache.isFieldLookupIdentifier(indexKey)) {
            return;
        }
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

    public void evictFieldIndexAllTypes(String dbName, String collName, String fieldName) {
        evictFieldIndexLookup(dbName, collName, fieldName);
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
        return listCacheableResources(Measure.FULL);
    }

    public List<CacheableResource> listCacheableResources(Measure measure) {
        final var result = new ArrayList<CacheableResource>();
        pkIndexMap.forEach((collId, rows) -> addResource(result, AccessKind.PK_INDEX, collId, null,
                sizes.pkIndexBytes(collId, rows.size())));
        collectionMap.forEach((collId, documents) -> addResource(result, AccessKind.COLLECTION, collId, null,
                sizes.trackedBytes(collId, documents)));
        fieldIndexMap.forEach((collId, indexes) -> indexes.forEach((indexKey, entries) -> addResource(result,
                AccessKind.FIELD_INDEX, collId, indexKey, fieldIndexBytes(measure, collId, indexKey, entries))));
        fieldLookups.addResources(result);
        return result;
    }

    private static void addResource(List<CacheableResource> result, AccessKind kind, String collId, String indexKey,
            long bytes) {
        final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
        if (parts.length == 2) {
            result.add(new CacheableResource(kind, parts[0], parts[1], indexKey, bytes));
        }
    }

    private long fieldIndexBytes(Measure measure, String collId, String indexKey, List<FieldIndexEntry<?>> entries) {
        if (measure == Measure.FULL_FORCED || measure == Measure.FULL && sizes.needsMeasure(collId, indexKey)) {
            final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            final var fieldName = CacheableResource.indexedFieldOf(indexKey);
            if (parts.length == 2 && rl.tryLockIndexRead(parts[0], parts[1], fieldName)) {
                try {
                    return sizes.measureFieldIndex(collId, indexKey, entries);
                } finally {
                    rl.releaseIndexRead(parts[0], parts[1], fieldName);
                }
            }
        }
        return sizes.rememberedFieldIndexBytes(collId, indexKey, entries.size());
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
