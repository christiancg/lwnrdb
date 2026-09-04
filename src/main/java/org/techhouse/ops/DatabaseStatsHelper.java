package org.techhouse.ops;

import java.lang.management.ManagementFactory;
import java.util.Set;
import org.techhouse.bckg_ops.ScheduleExecutor;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheableResource;
import org.techhouse.cluster.ScriptPlacement;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.GetDatabaseStatsResponse;
import org.techhouse.ops.resp.OperationResponse;

public final class DatabaseStatsHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final ScheduleExecutor scheduleExecutor = IocContainer.get(ScheduleExecutor.class);
    private static final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);
    private static final ScriptLoad scriptLoad = IocContainer.get(ScriptLoad.class);
    private static final ScriptRunRegistry scriptRunRegistry = IocContainer.get(ScriptRunRegistry.class);
    private static final ScriptAdmission scriptAdmission = IocContainer.get(ScriptAdmission.class);
    private static final ScriptPlacement scriptPlacement = IocContainer.get(ScriptPlacement.class);

    private DatabaseStatsHelper() {
    }

    public static OperationResponse processGetDatabaseStats() {
        try {
            final var stats = new JsonObject();
            stats.add("memory", buildMemoryStats());
            stats.add("inDoubtTransactions", buildInDoubtTransactions());
            stats.add("triggers", buildTriggerStats());
            stats.add("schedules", buildScheduleStats());
            stats.add("scripts", buildScriptStats());

            final var dbNames = cache.getUserDatabaseNames();
            final var dbArray = new JsonArray();
            final var totals = new Totals();
            for (var dbName : dbNames) {
                dbArray.add(buildDatabaseStats(dbName, totals));
            }
            stats.add("totals", buildTotals(totals, dbNames.size()));
            stats.add("databases", dbArray);

            return new GetDatabaseStatsResponse("Ok", stats);
        } catch (Exception e) {
            return new OperationResponse(OperationType.GET_DATABASE_STATS, ErrorCode.ERROR_GATHERING_STATS);
        }
    }

    // In-doubt distributed transactions still holding this node's write locks (a prepared 2PC participant
    // whose coordinator has not yet delivered a decision), so an operator can spot them and, if needed,
    // force a resolution with RESOLVE_TRANSACTION.
    // A trigger runs asynchronously with no client waiting on it, so these counters are the operator's
    // only window into whether they are running, failing or being dropped under load.
    private static JsonObject buildTriggerStats() {
        final var triggers = new JsonObject();
        triggers.addProperty("enabled", Configuration.getInstance().isTriggersEnabled());
        triggers.addProperty("fired", triggerExecutor.getFired());
        triggers.addProperty("failed", triggerExecutor.getFailed());
        triggers.addProperty("dropped", triggerExecutor.getDropped());
        triggers.addProperty("queued", (long) triggerExecutor.getQueued());
        triggers.addProperty("runLogEnabled", Configuration.getInstance().isTriggerRunLogEnabled());
        // Runs recorded but not yet applied. A number that stays above zero while nothing is queued means
        // runs are stranded - their node never came back, or their collection was dropped - and they will be
        // garbage-collected after triggerRunRetentionMs rather than ever running.
        triggers.addProperty("pendingRuns", (long) pendingRunCount());
        return triggers;
    }

    // Like a trigger, a scheduled run has no client waiting on it, so these counters are the operator's only
    // window into whether jobs are firing, failing, being skipped or being dropped under load.
    private static JsonObject buildScheduleStats() {
        final var schedules = new JsonObject();
        schedules.addProperty("enabled", Configuration.getInstance().isSchedulesEnabled());
        schedules.addProperty("registered", (long) scheduleRegistry.size());
        schedules.addProperty("fired", scheduleExecutor.getFired());
        schedules.addProperty("failed", scheduleExecutor.getFailed());
        schedules.addProperty("skipped", scheduleExecutor.getSkipped());
        schedules.addProperty("dropped", scheduleExecutor.getDropped());
        schedules.addProperty("queued", (long) scheduleExecutor.getQueued());
        return schedules;
    }

    // Where scripts are running: this node's live count plus how often placement forwarded a run elsewhere
    // and how often that forward failed and the run stayed here.
    private static JsonObject buildScriptStats() {
        final var scripts = new JsonObject();
        scripts.addProperty("routingEnabled", Configuration.getInstance().isScriptRoutingEnabled());
        scripts.addProperty("running", (long) scriptLoad.current());
        scripts.addProperty("capacity", (long) scriptAdmission.capacity());
        scripts.addProperty("available", (long) scriptAdmission.available());
        scripts.addProperty("rejected", scriptAdmission.getRejected());
        scripts.addProperty("waited", scriptAdmission.getWaited());
        scripts.addProperty("forwarded", scriptPlacement.getForwarded());
        scripts.addProperty("forwardFallbacks", scriptPlacement.getForwardFallbacks());
        scripts.addProperty("cancelled", scriptRunRegistry.getCancelled());
        return scripts;
    }

    private static int pendingRunCount() {
        try {
            return TriggerRunLog.pending().size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static JsonObject buildInDoubtTransactions() {
        final var inDoubt = new JsonObject();
        final var ids = new JsonArray();
        for (final var dtxId : Tx2pcLog.preparedDtxIds()) {
            ids.add(new org.techhouse.ejson.elements.JsonString(dtxId));
        }
        inDoubt.addProperty("count", (long) ids.size());
        inDoubt.add("ids", ids);
        return inDoubt;
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
        memory.add("adminMetadataCache", buildAdminMetadataCacheStats(config));
        return memory;
    }

    private static JsonObject buildAdminMetadataCacheStats(Configuration config) {
        final var stats = cache.metadataCacheStats();
        final var json = new JsonObject();
        json.addProperty("procedureBytes", stats.procedureBytes());
        json.addProperty("procedureEntries", (long) stats.procedureEntries());
        json.addProperty("triggerBytes", stats.triggerBytes());
        json.addProperty("triggerEntries", (long) stats.triggerEntries());
        json.addProperty("schemaBytes", stats.schemaBytes());
        json.addProperty("schemaEntries", (long) stats.schemaEntries());
        json.addProperty("scheduleBytes", stats.scheduleBytes());
        json.addProperty("scheduleEntries", (long) stats.scheduleEntries());
        json.addProperty("missEntries", (long) stats.missEntries());
        json.addProperty("procedureCacheMaxBytes", config.getProcedureCacheMaxBytes());
        json.addProperty("schemaCacheMaxBytes", config.getSchemaCacheMaxBytes());
        json.addProperty("triggerCacheMaxEntries", (long) config.getTriggerCacheMaxEntries());
        json.addProperty("scheduleCacheMaxBytes", config.getScheduleCacheMaxBytes());
        json.addProperty("metadataMissCacheMaxEntries", (long) config.getMetadataMissCacheMaxEntries());
        return json;
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
