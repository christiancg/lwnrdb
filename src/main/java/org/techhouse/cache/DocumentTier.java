package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;

final class DocumentTier {
    private final UserCache cache;

    DocumentTier(UserCache cache) {
        this.cache = cache;
    }

    private Map<String, ResidentDocuments> residents() {
        return cache.residentDocuments();
    }

    private ResidentDocuments residentFor(String collectionIdentifier) {
        return residents().computeIfAbsent(collectionIdentifier,
                _ -> new ResidentDocuments(cache.sizes().footprint().newCell(AccessKind.COLLECTION)));
    }

    void addEntryToCache(String dbName, String collName, DbEntry entry) {
        if (UserCache.isReservedDatabase(dbName)) {
            return;
        }
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        final var existing = residents().get(collId);
        if (existing != null && existing.containsKey(entry.get_id())) {
            existing.put(entry);
            return;
        }
        if (!cache.admits(dbName, AccessKind.COLLECTION, collName, null,
                () -> CacheSizeEstimator.estimateCollectionEntry(entry))) {
            return;
        }
        residentFor(collId).put(entry);
    }

    void addEntriesToCache(String dbName, String collName, List<DbEntry> entries) {
        if (UserCache.isReservedDatabase(dbName)) {
            return;
        }
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        final var existing = residents().get(collId);
        final var newEntries = new ArrayList<DbEntry>();
        for (var e : entries) {
            if (existing != null && existing.containsKey(e.get_id())) {
                existing.put(e);
            } else {
                newEntries.add(e);
            }
        }
        if (newEntries.isEmpty() || !cache.admits(dbName, AccessKind.COLLECTION, collName, null,
                () -> CacheSizeEstimator.estimateCollectionEntries(newEntries))) {
            return;
        }
        final var target = residentFor(collId);
        for (var e : newEntries) {
            target.put(e);
        }
    }

    DbEntry getById(String dbName, String collName, PkIndexEntry idxEntry) throws Exception {
        if (cache.isCachingDisabled(dbName)) {
            return cache.fs().getById(idxEntry);
        }
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var resident = residents().get(collectionIdentifier);
        final var hit = resident == null ? null : resident.get(idxEntry.getValue());
        if (hit != null) {
            return hit;
        }
        final var entry = cache.fs().getById(idxEntry);
        if (cache.locks().holdsCollectionLock(dbName, collName) && cache.admits(dbName, AccessKind.COLLECTION, collName,
                null, () -> CacheSizeEstimator.estimateCollectionEntry(entry))) {
            residentFor(collectionIdentifier).put(entry);
        }
        return entry;
    }

    Map<String, DbEntry> getCachedCollection(String dbName, String collName) {
        final var resident = residents().get(Cache.getCollectionIdentifier(dbName, collName));
        return resident == null ? null : resident.documents();
    }

    Map<String, DbEntry> admitWholeCollection(String dbName, String collName, Map<String, DbEntry> loaded) {
        if (cache.isCachingDisabled(dbName)) {
            return loaded;
        }
        final var asMap = loaded instanceof ConcurrentHashMap<String, DbEntry> concurrent
                ? concurrent
                : new ConcurrentHashMap<>(loaded);
        return admitCollected(dbName, collName, asMap, CacheSizeEstimator.estimateCollectionSize(asMap), loaded);
    }

    Map<String, DbEntry> admitCollected(String dbName, String collName, ConcurrentHashMap<String, DbEntry> collected,
            long measuredBytes, Map<String, DbEntry> otherwise) {
        if (cache.isCachingDisabled(dbName)
                || !cache.admits(dbName, AccessKind.COLLECTION, collName, null, () -> measuredBytes)) {
            return otherwise;
        }
        final var attached = ResidentDocuments.attach(collected,
                cache.sizes().footprint().newCell(AccessKind.COLLECTION), measuredBytes);
        final var replaced = residents().put(Cache.getCollectionIdentifier(dbName, collName), attached);
        if (replaced != null) {
            replaced.retire();
        }
        return collected;
    }

    List<DbEntry> getEntriesByIds(String dbName, String collName, Set<String> ids, PkRows pinned) throws IOException {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        final var result = new ArrayList<DbEntry>(ids.size());
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var cachingDisabled = cache.isCachingDisabled(dbName);
        final var resident = cachingDisabled ? null : residents().get(collectionIdentifier);
        final var missingIds = new ArrayList<String>();
        for (var id : ids) {
            final var hit = resident != null ? resident.get(id) : null;
            if (hit != null) {
                result.add(hit);
            } else {
                missingIds.add(id);
            }
        }
        if (missingIds.isEmpty()) {
            return result;
        }
        final var toRead = cache.pkTier().rowsFor(dbName, collName, missingIds, pinned);
        if (toRead.isEmpty()) {
            return result;
        }
        final var read = cache.fs().getByIndexEntries(toRead);
        result.addAll(read);
        if (!cachingDisabled && cache.locks().holdsCollectionLock(dbName, collName) && cache.admits(dbName,
                AccessKind.COLLECTION, collName, null, () -> CacheSizeEstimator.estimateCollectionEntries(read))) {
            final var target = residentFor(collectionIdentifier);
            for (var e : read) {
                target.put(e);
            }
        }
        return result;
    }

    void evictEntry(String dbName, String collName, String pk) {
        final var resident = residents().get(Cache.getCollectionIdentifier(dbName, collName));
        if (resident != null) {
            resident.remove(pk);
        }
    }

    void evictCollectionDocuments(String collectionIdentifier) {
        final var removed = residents().remove(collectionIdentifier);
        if (removed != null) {
            removed.retire();
        }
    }
}
