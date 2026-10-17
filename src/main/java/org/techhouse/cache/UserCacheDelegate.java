package org.techhouse.cache;

import java.io.IOException;
import java.util.List;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;

interface UserCacheDelegate {
    UserCache userCache();

    default List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String dbName, String collName) throws IOException {
        return userCache().getPkIndexAndLoadIfNecessary(dbName, collName);
    }

    default void recordFieldIndexAccess(String dbName, String collName, String fieldName) {
        userCache().recordFieldIndexAccess(dbName, collName, fieldName);
    }

    default void addEntryToCache(String dbName, String collName, DbEntry entry) {
        userCache().addEntryToCache(dbName, collName, entry);
    }

    default void addEntriesToCache(String dbName, String collName, List<DbEntry> entries) {
        userCache().addEntriesToCache(dbName, collName, entries);
    }

    default DbEntry getById(String dbName, String collName, PkIndexEntry idxEntry) throws Exception {
        return userCache().getById(dbName, collName, idxEntry);
    }

    default void evictEntry(String dbName, String collName, String pk) {
        userCache().evictEntry(dbName, collName, pk);
    }

    default void evictFieldIndexAllTypes(String dbName, String collName, String fieldName) {
        userCache().evictFieldIndexAllTypes(dbName, collName, fieldName);
    }

    default List<FieldIndexEntry<?>> residentFieldIndex(String dbName, String collName, String indexIdentifier) {
        return userCache().residentFieldIndex(dbName, collName, indexIdentifier);
    }

    default void admitMaintainedFieldIndex(String dbName, String collName, String indexIdentifier,
            List<FieldIndexEntry<?>> written) {
        userCache().admitMaintainedFieldIndex(dbName, collName, indexIdentifier, written);
    }

    default void rememberFieldIndexBatch(String dbName, String collName, String indexIdentifier, int previousCount,
            long deltaBytes) {
        userCache().rememberFieldIndexBatch(dbName, collName, indexIdentifier, previousCount, deltaBytes);
    }

    default FieldIndexLookup fieldIndexLookup(String dbName, String collName, String fieldName) {
        return userCache().fieldIndexLookup(dbName, collName, fieldName);
    }

    default void admitFieldIndexLookup(String dbName, String collName, String fieldName, FieldIndexLookup lookup) {
        userCache().admitFieldIndexLookup(dbName, collName, fieldName, lookup);
    }

    default void evictFieldIndexLookup(String dbName, String collName, String fieldName) {
        userCache().evictFieldIndexLookup(dbName, collName, fieldName);
    }

    default void evictFieldIndex(String dbName, String collName, String indexIdentifier) {
        userCache().evictFieldIndex(dbName, collName, indexIdentifier);
    }

    default List<CacheableResource> listCacheableResources() {
        return userCache().listCacheableResources();
    }
}
