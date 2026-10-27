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
    public static final int UNKNOWN_COUNT = -1;

    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private final UsageTracker usageTracker = IocContainer.get(UsageTracker.class);
    private final Map<String, List<PkIndexEntry>> pkIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexMap = new ConcurrentHashMap<>();
    private final FieldIndexLookups fieldLookups = new FieldIndexLookups();
    private final Map<String, ResidentDocuments> collectionMap = new ConcurrentHashMap<>();
    private final UserCacheSizes sizes = new UserCacheSizes();
    private final WholeLoads wholeLoads = new WholeLoads(() -> fs,
            configuration.intValue(ConfigKey.TRANSIENT_LOAD_PERMITS));
    private final OverBudgetSignal overBudget = new OverBudgetSignal(configuration);
    private final CacheFitWarnings fitWarnings = new CacheFitWarnings();
    private final PkIndexTier pkTier = new PkIndexTier(this);
    private final DocumentTier documentTier = new DocumentTier(this);
    private final AdmissionEviction admission = new AdmissionEviction(this);

    Map<String, List<PkIndexEntry>> pkIndexes() {
        return pkIndexMap;
    }

    Map<String, ResidentDocuments> residentDocuments() {
        return collectionMap;
    }

    Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexes() {
        return fieldIndexMap;
    }

    UserCacheSizes sizes() {
        return sizes;
    }

    FileSystem fs() {
        return fs;
    }

    ResourceLocking locks() {
        return rl;
    }

    UsageTracker usageTracker() {
        return usageTracker;
    }

    WholeLoads wholeLoads() {
        return wholeLoads;
    }

    CacheFitWarnings fitWarnings() {
        return fitWarnings;
    }

    PkIndexTier pkTier() {
        return pkTier;
    }

    long maxMemoryBytes() {
        return configuration.getMaxMemoryBytes();
    }

    public List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String dbName, String collName) throws IOException {
        return pkTier.loadIfNecessary(dbName, collName);
    }

    public PkIndexEntry pkRowFor(String dbName, String collName, String id) throws IOException {
        return pkTier.rowFor(dbName, collName, id);
    }

    public int pkCountForCoverage(String dbName, String collName) throws IOException {
        return pkTier.countForCoverage(dbName, collName);
    }

    public int pkIndexSize(String dbName, String collName) throws IOException {
        return pkTier.size(dbName, collName);
    }

    public boolean isResidentPkIndex(String dbName, String collName, List<PkIndexEntry> rows) {
        return rows != null && pkIndexMap.get(Cache.getCollectionIdentifier(dbName, collName)) == rows;
    }

    public boolean tryPinTransientPkIndex() {
        return wholeLoads.tryPin();
    }

    public void unpinTransientPkIndex() {
        wholeLoads.unpin();
    }

    public void repositionAfterVacuum(VacuumedPage vacuumed) {
        pkTier.repositionAfterVacuum(vacuumed);
    }

    public boolean isCachingDisabled(String dbName) {
        return isReservedDatabase(dbName) || configuration.isCachingDisabled();
    }

    static boolean isReservedDatabase(String dbName) {
        return Globals.ADMIN_DB_NAME.equals(dbName) || Globals.ADMIN_PAGES_DB_NAME.equals(dbName);
    }

    public boolean shouldCache(String dbName, AdmissionRequest request) {
        if (isCachingDisabled(dbName)) {
            return false;
        }
        if (configuration.isCacheUnlimited()) {
            return true;
        }
        final var maxBytes = configuration.getMaxMemoryBytes();
        return footprintBytes() + request.bytes() <= maxBytes || admission.makeRoom(request, maxBytes);
    }

    public boolean wouldAdmit(AdmissionRequest request) {
        if (isCachingDisabled(request.dbName())) {
            return false;
        }
        if (configuration.isCacheUnlimited()) {
            return true;
        }
        final var maxBytes = configuration.getMaxMemoryBytes();
        return footprintBytes() + request.bytes() <= maxBytes || admission.mayMakeRoom(request, maxBytes);
    }

    boolean admits(String dbName, AccessKind tier, String collName, String fieldName, LongSupplier bytes) {
        return !isCachingDisabled(dbName)
                && shouldCache(dbName, new AdmissionRequest(tier, dbName, collName, fieldName, bytes.getAsLong()));
    }

    boolean admissionFeasible(AccessKind tier, long bytes) {
        return configuration.isCacheUnlimited() || admission.feasible(tier, bytes, configuration.getMaxMemoryBytes());
    }

    boolean loadsAdmitted(String dbName, String collName, AccessKind tier, long estimatedBytes) {
        return isReservedDatabase(dbName) || rl.holdsCollectionLock(dbName, collName) && !isCachingDisabled(dbName)
                && admissionFeasible(tier, estimatedBytes);
    }

    public long footprintBytes() {
        return sizes.footprint().bytes();
    }

    public <T> List<FieldIndexEntry<T>> getFieldIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, Class<T> indexType) throws IOException {
        return loadIndex(dbName, collName, Cache.getIndexIdentifier(fieldName, indexType),
                () -> wholeLoads.fieldIndex(dbName, collName, fieldName, indexType,
                        bytes -> loadsAdmitted(dbName, collName, AccessKind.FIELD_INDEX, bytes)));
    }

    public List<FieldIndexEntry<String>> getHashIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, IndexKind kind) throws IOException {
        return loadIndex(dbName, collName, Cache.getHashIndexIdentifier(fieldName, kind.label()),
                () -> wholeLoads.hashIndex(dbName, collName, fieldName, kind,
                        bytes -> loadsAdmitted(dbName, collName, AccessKind.FIELD_INDEX, bytes)));
    }

    public List<FieldIndexEntry<?>> residentFieldIndex(String dbName, String collName, String indexIdentifier) {
        final var index = fieldIndexMap.get(Cache.getCollectionIdentifier(dbName, collName));
        return index == null ? null : index.get(indexIdentifier);
    }

    public void admitMaintainedFieldIndex(String dbName, String collName, String indexIdentifier,
            List<FieldIndexEntry<?>> written) {
        if (rl.holdsCollectionLock(dbName, collName) && residentFieldIndex(dbName, collName, indexIdentifier) == null
                && fieldIndexMayFit(dbName, collName, indexIdentifier, written.size())) {
            admitFieldIndex(dbName, collName, indexIdentifier, FieldIndexList.of(written));
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
        if (resident == lookup || admits(dbName, AccessKind.FIELD_INDEX, collName, fieldName,
                () -> CacheSizeEstimator.estimateFieldIndexLookupSize(lookup.size()))) {
            fieldLookups.put(Cache.getCollectionIdentifier(dbName, collName), fieldName, lookup, sizes.footprint());
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
        if (indexEntries != null && rl.holdsCollectionLock(dbName, collName)
                && fieldIndexMayFit(dbName, collName, indexIdentifier, indexEntries.size())) {
            admitFieldIndex(dbName, collName, indexIdentifier, FieldIndexList.of(indexEntries));
        }
        return indexEntries;
    }

    private boolean fieldIndexMayFit(String dbName, String collName, String indexIdentifier, int entries) {
        final var lowerBound = CacheSizeEstimator.minimumFieldIndexBytes(entries);
        if (admissionFeasible(AccessKind.FIELD_INDEX, lowerBound)) {
            return true;
        }
        if (!isCachingDisabled(dbName)) {
            fitWarnings.fieldIndexOutgrows(dbName, collName, indexIdentifier, lowerBound, maxMemoryBytes());
        }
        return false;
    }

    private void admitFieldIndex(String dbName, String collName, String indexIdentifier,
            List<FieldIndexEntry<?>> copy) {
        if (isCachingDisabled(dbName)) {
            return;
        }
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        SharedIdLists.share(pkIndexMap.get(collectionIdentifier), copy);
        final var measured = CacheSizeEstimator.estimateFieldIndexSize(copy);
        if (!shouldCache(dbName, new AdmissionRequest(AccessKind.FIELD_INDEX, dbName, collName,
                CacheableResource.indexedFieldOf(indexIdentifier), measured))) {
            fitWarnings.fieldIndexOutgrows(dbName, collName, indexIdentifier, measured, maxMemoryBytes());
            return;
        }
        fieldIndexMap.compute(collectionIdentifier, (_, existing) -> {
            final var indexes = existing != null ? existing : new ConcurrentHashMap<String, List<FieldIndexEntry<?>>>();
            if (indexes.putIfAbsent(indexIdentifier, copy) == null) {
                sizes.rememberMeasuredFieldIndex(collectionIdentifier, indexIdentifier, measured, copy);
            }
            return indexes;
        });
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
        documentTier.addEntryToCache(dbName, collName, entry);
    }

    public void addEntriesToCache(String dbName, String collName, List<DbEntry> entries) {
        documentTier.addEntriesToCache(dbName, collName, entries);
    }

    public DbEntry getById(String dbName, String collName, PkIndexEntry idxEntry) throws Exception {
        return documentTier.getById(dbName, collName, idxEntry);
    }

    public Map<String, DbEntry> getCachedCollection(String dbName, String collName) {
        return documentTier.getCachedCollection(dbName, collName);
    }

    public Map<String, DbEntry> admitWholeCollection(String dbName, String collName, Map<String, DbEntry> loaded) {
        return documentTier.admitWholeCollection(dbName, collName, loaded);
    }

    void admitScannedCollection(String dbName, String collName, ConcurrentHashMap<String, DbEntry> collected,
            long measuredBytes) {
        documentTier.admitCollected(dbName, collName, collected, measuredBytes, collected);
    }

    public List<DbEntry> getEntriesByIds(String dbName, String collName, Set<String> ids) throws IOException {
        return documentTier.getEntriesByIds(dbName, collName, ids, null);
    }

    public List<DbEntry> getEntriesByIds(String dbName, String collName, Set<String> ids, PkRows pinned)
            throws IOException {
        return documentTier.getEntriesByIds(dbName, collName, ids, pinned);
    }

    public void evictEntry(String dbName, String collName, String pk) {
        documentTier.evictEntry(dbName, collName, pk);
    }

    public void evictDatabase(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        for (final var collectionIdentifier : List.copyOf(pkIndexMap.keySet())) {
            if (collectionIdentifier.startsWith(prefix)) {
                pkTier.evict(collectionIdentifier);
            }
        }
        for (final var collectionIdentifier : List.copyOf(collectionMap.keySet())) {
            if (collectionIdentifier.startsWith(prefix)) {
                documentTier.evictCollectionDocuments(collectionIdentifier);
            }
        }
        fieldIndexMap.keySet().removeIf(key -> key.startsWith(prefix));
        fieldLookups.evictByPrefix(prefix);
        sizes.forgetByPrefix(prefix);
        fitWarnings.forgetByPrefix(prefix);
    }

    public void evictCollection(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        pkTier.evict(collIdentifier);
        documentTier.evictCollectionDocuments(collIdentifier);
        fieldIndexMap.remove(collIdentifier);
        fieldLookups.evictCollection(collIdentifier);
        sizes.forgetCollection(collIdentifier);
        fitWarnings.forgetCollection(collIdentifier);
    }

    public void evictCollectionDocuments(String dbName, String collName) {
        documentTier.evictCollectionDocuments(Cache.getCollectionIdentifier(dbName, collName));
    }

    public void evictPkIndex(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        pkTier.evict(collIdentifier);
        SharedIdLists.sharersOf(fieldIndexMap.get(collIdentifier))
                .forEach(key -> evictFieldIndex(dbName, collName, key));
    }

    public String canonicalPkId(String dbName, String collName, String id) {
        return SharedIdLists.canonicalId(pkIndexMap.get(Cache.getCollectionIdentifier(dbName, collName)), id);
    }

    public void evict(CacheableResource resource) {
        switch (resource.kind()) {
            case PK_INDEX -> evictPkIndex(resource.dbName(), resource.collName());
            case FIELD_INDEX -> evictFieldIndex(resource.dbName(), resource.collName(), resource.indexKey());
            default -> evictCollectionDocuments(resource.dbName(), resource.collName());
        }
    }

    public void evictFieldIndex(String dbName, String collName, String indexKey) {
        evictFieldIndexLookup(dbName, collName, CacheableResource.indexedFieldOf(indexKey));
        if (Cache.isFieldLookupIdentifier(indexKey)) {
            return;
        }
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        fieldIndexMap.computeIfPresent(collIdentifier, (_, indexes) -> {
            indexes.remove(indexKey);
            sizes.forgetFieldIndex(collIdentifier, indexKey);
            return indexes.isEmpty() ? null : indexes;
        });
    }

    public void evictFieldIndexAllTypes(String dbName, String collName, String fieldName) {
        evictFieldIndexLookup(dbName, collName, fieldName);
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var prefix = fieldName + Globals.COLL_IDENTIFIER_SEPARATOR;
        fieldIndexMap.computeIfPresent(collIdentifier, (_, indexes) -> {
            indexes.keySet().removeIf(key -> key.equals(fieldName) || key.startsWith(prefix));
            sizes.forgetFieldIndexAllTypes(collIdentifier, fieldName);
            return indexes.isEmpty() ? null : indexes;
        });
    }

    public List<CacheableResource> listCacheableResources() {
        return listCacheableResources(Measure.FULL);
    }

    public List<CacheableResource> listCacheableResources(Measure measure) {
        final var result = new ArrayList<CacheableResource>();
        pkIndexMap.forEach((collId, rows) -> addResource(result, AccessKind.PK_INDEX, collId, null, pkBytes(rows)));
        collectionMap.forEach(
                (collId, documents) -> addResource(result, AccessKind.COLLECTION, collId, null, documents.bytes()));
        fieldIndexMap.forEach((collId, indexes) -> indexes.forEach((indexKey, entries) -> addResource(result,
                AccessKind.FIELD_INDEX, collId, indexKey, fieldIndexBytes(measure, collId, indexKey, entries))));
        fieldLookups.addResources(result);
        return result;
    }

    private static long pkBytes(List<PkIndexEntry> rows) {
        final var charged = rows instanceof PkIndexList list ? list.chargedBytes() : -1L;
        return charged >= 0 ? charged : rows.size() * CacheSizeEstimator.pkIndexBytesPerEntry(List.of());
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
