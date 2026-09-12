package org.techhouse.cache;

import java.io.IOException;
import java.util.List;
import org.techhouse.data.DbEntry;
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

    default List<CacheableResource> listCacheableResources() {
        return userCache().listCacheableResources();
    }
}
