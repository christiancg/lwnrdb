package org.techhouse.ops;

import java.lang.management.ManagementFactory;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheableResource;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.GetDatabaseStatsResponse;

public final class DatabaseStatsHelper {
    private static final Cache cache = IocContainer.get(Cache.class);

    private DatabaseStatsHelper() {
    }

    public static GetDatabaseStatsResponse processGetDatabaseStats() {
        try {
            final var stats = new JsonObject();
            stats.add("memory", buildMemoryStats());

            final var dbNames = cache.getUserDatabaseNames();
            final var dbArray = new JsonArray();
            final var totals = new Totals();
            for (var dbName : dbNames) {
                dbArray.add(buildDatabaseStats(dbName, totals));
            }
            stats.add("totals", buildTotals(totals, dbNames.size()));
            stats.add("databases", dbArray);

            return new GetDatabaseStatsResponse(OperationStatus.OK, "Ok", stats);
        } catch (Exception e) {
            return new GetDatabaseStatsResponse(OperationStatus.ERROR,
                    "Error while gathering database stats: " + e.getMessage(), null);
        }
    }

    private static JsonObject buildMemoryStats() {
        final var config = Configuration.getInstance();
        final var memory = new JsonObject();
        final var heapUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        memory.addProperty("heapUsedBytes", heapUsage.getUsed());
        memory.addProperty("heapMaxBytes", heapUsage.getMax());
        memory.addProperty("heapCommittedBytes", heapUsage.getCommitted());
        long userCacheBytes = 0L;
        for (CacheableResource r : cache.listCacheableResources()) {
            userCacheBytes += r.estimatedSizeBytes();
        }
        memory.addProperty("userCacheBytes", userCacheBytes);
        memory.addProperty("maxMemoryBytes", config.getMaxMemoryBytes());
        memory.addProperty("cachingDisabled", config.isCachingDisabled());
        memory.addProperty("cacheUnlimited", config.isCacheUnlimited());
        return memory;
    }

    private static JsonObject buildDatabaseStats(String dbName, Totals totals) {
        final var collNames = cache.getCollectionNamesForDatabase(dbName);
        final var collArray = new JsonArray();
        long dbIndexes = 0L;
        long dbPages = 0L;
        long dbEntries = 0L;
        long dbSizeBytes = 0L;
        for (var collName : collNames) {
            final var coll = buildCollectionStats(dbName, collName);
            collArray.add(coll.json());
            dbIndexes += coll.indexCount();
            dbPages += coll.pageCount();
            dbEntries += coll.entryCount();
            dbSizeBytes += coll.sizeBytes();
        }
        final var dbObj = new JsonObject();
        dbObj.addProperty("name", dbName);
        dbObj.addProperty("collectionCount", (long) collNames.size());
        dbObj.addProperty("indexCount", dbIndexes);
        dbObj.addProperty("pageCount", dbPages);
        dbObj.addProperty("entryCount", dbEntries);
        dbObj.addProperty("sizeBytes", dbSizeBytes);
        dbObj.add("collections", collArray);

        totals.collections += collNames.size();
        totals.indexes += dbIndexes;
        totals.pages += dbPages;
        totals.entries += dbEntries;
        totals.sizeBytes += dbSizeBytes;
        return dbObj;
    }

    private static CollectionStats buildCollectionStats(String dbName, String collName) {
        final var collEntry = cache.getAdminCollectionEntry(dbName, collName);
        final var indexes = collEntry == null ? Set.<String>of() : collEntry.getIndexes();
        final var pageEntries = cache.getAdminPageEntries(dbName, collName);
        long entryCount = 0L;
        long sizeBytes = 0L;
        long pageCount = 0L;
        if (pageEntries != null) {
            pageCount = pageEntries.size();
            for (var p : pageEntries) {
                entryCount += p.getEntryCount();
                sizeBytes += p.getPageSize();
            }
        }
        final var collObj = new JsonObject();
        collObj.addProperty("name", collName);
        collObj.addProperty("indexCount", (long) indexes.size());
        final var idxArr = new JsonArray();
        for (var idx : indexes) {
            idxArr.add(idx);
        }
        collObj.add("indexes", idxArr);
        collObj.addProperty("pageCount", pageCount);
        collObj.addProperty("entryCount", entryCount);
        collObj.addProperty("sizeBytes", sizeBytes);
        return new CollectionStats(collObj, indexes.size(), pageCount, entryCount, sizeBytes);
    }

    private static JsonObject buildTotals(Totals totals, int databaseCount) {
        final var json = new JsonObject();
        json.addProperty("userCount", (long) cache.getAllAdminUserEntries().size());
        json.addProperty("databaseCount", (long) databaseCount);
        json.addProperty("collectionCount", totals.collections);
        json.addProperty("indexCount", totals.indexes);
        json.addProperty("pageCount", totals.pages);
        json.addProperty("entryCount", totals.entries);
        json.addProperty("sizeBytes", totals.sizeBytes);
        return json;
    }

    private static final class Totals {
        long collections;
        long indexes;
        long pages;
        long entries;
        long sizeBytes;
    }

    private record CollectionStats(JsonObject json, long indexCount, long pageCount, long entryCount, long sizeBytes) {
    }
}
