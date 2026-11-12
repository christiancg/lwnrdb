package org.techhouse.ops;

import org.techhouse.cache.Cache;
import org.techhouse.ioc.IocContainer;

record StoredTotals(long databases, long collections, long pages, long entries, long sizeBytes) {
    private static final Cache cache = IocContainer.get(Cache.class);
    static final StoredTotals NONE = new StoredTotals(0L, 0L, 0L, 0L, 0L);

    static StoredTotals ofCollection(String dbName, String collName) {
        final var pageEntries = cache.getAdminPageEntries(dbName, collName);
        if (pageEntries == null) {
            return new StoredTotals(0L, 1L, 0L, 0L, 0L);
        }
        long entryCount = 0L;
        long sizeBytes = 0L;
        for (var page : pageEntries) {
            entryCount += page.getEntryCount();
            sizeBytes += page.getPageSize();
        }
        return new StoredTotals(0L, 1L, pageEntries.size(), entryCount, sizeBytes);
    }

    static StoredTotals ofEveryUserDatabase() {
        var totals = NONE;
        for (var dbName : cache.getUserDatabaseNames()) {
            var database = new StoredTotals(1L, 0L, 0L, 0L, 0L);
            for (var collName : cache.getCollectionNamesForDatabase(dbName)) {
                database = database.plus(ofCollection(dbName, collName));
            }
            totals = totals.plus(database);
        }
        return totals;
    }

    StoredTotals plus(StoredTotals other) {
        return new StoredTotals(databases + other.databases, collections + other.collections, pages + other.pages,
                entries + other.entries, sizeBytes + other.sizeBytes);
    }
}
