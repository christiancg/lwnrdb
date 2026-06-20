package org.techhouse.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollectionUsageEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public class MemoryManagement {
    static final long SWEEP_INTERVAL_SECONDS = 5L;
    static final long USAGE_RETENTION_MILLIS = 24L * 60L * 60L * 1000L;
    static final long USAGE_CLEANUP_INTERVAL_SECONDS = 60L * 60L;

    private final Logger logger = Logger.logFor(MemoryManagement.class);
    private final Configuration config = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private final ConcurrentHashMap<String, UsageCounter> counters = new ConcurrentHashMap<>();
    private final AtomicBoolean sweepRunning = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public static String buildKey(AccessKind kind, String dbName, String collName, String indexKey) {
        return kind.name() + Globals.COLL_IDENTIFIER_SEPARATOR + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                + Globals.COLL_IDENTIFIER_SEPARATOR + (indexKey == null ? "" : indexKey);
    }

    public void recordAccess(AccessKind kind, String dbName, String collName, String indexKey) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        if (isCachingDisabled()) {
            return;
        }
        final var key = buildKey(kind, dbName, collName, indexKey);
        final var counter = counters.computeIfAbsent(key,
                _ -> new UsageCounter(kind, dbName, collName, indexKey == null ? "" : indexKey, 0L, 0L));
        counter.increment(System.currentTimeMillis());
    }

    public boolean isCachingDisabled() {
        return config.isCachingDisabled();
    }

    public boolean isCacheUnlimited() {
        return config.isCacheUnlimited();
    }

    public UsageCounter getCounter(AccessKind kind, String dbName, String collName, String indexKey) {
        return counters.get(buildKey(kind, dbName, collName, indexKey));
    }

    public Map<String, UsageCounter> getCountersSnapshot() {
        return Map.copyOf(counters);
    }

    public void clearCounter(AccessKind kind, String dbName, String collName, String indexKey) {
        counters.remove(buildKey(kind, dbName, collName, indexKey));
    }

    public void putCounter(UsageCounter counter) {
        counters.put(buildKey(counter.kind(), counter.dbName(), counter.collName(), counter.indexKey()), counter);
    }

    public long usageRetentionMillis() {
        return USAGE_RETENTION_MILLIS;
    }

    public void loadProfileFromAdmin() {
        if (isCachingDisabled()) {
            return;
        }
        try {
            try (var pagesStream = fs.streamPages(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME)) {
                pagesStream.forEach(map -> {
                    for (var e : map.values()) {
                        try {
                            final var data = e.getData();
                            data.addProperty(Globals.PK_FIELD, e.get_id());
                            final var usage = AdminCollectionUsageEntry.fromJsonObject(data);
                            final var counter = new UsageCounter(usage.getKind(), usage.getDbName(),
                                    usage.getCollName(), usage.getIndexKey(), usage.getAccessCount(),
                                    usage.getLastAccessMillis());
                            counters.put(
                                    buildKey(counter.kind(), counter.dbName(), counter.collName(), counter.indexKey()),
                                    counter);
                        } catch (Exception inner) {
                            logger.warning("Skipping malformed collection_usage entry '" + e.get_id() + "': "
                                    + inner.getMessage());
                        }
                    }
                });
            }
        } catch (Exception e) {
            logger.warning(
                    "Failed to load collection usage profile, continuing with empty counters: " + e.getMessage());
        }
    }

    public void startSweepThread() {
        if (isCachingDisabled()) {
            return;
        }
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            final var t = new Thread(r, "memory-management-sweep");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runEvictionSweepSafely, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::submitCleanupTask, USAGE_CLEANUP_INTERVAL_SECONDS,
                USAGE_CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void stopSweepThread() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void runEvictionSweepSafely() {
        try {
            runEvictionSweep();
        } catch (Exception e) {
            logger.error("Eviction sweep failed", e);
        }
    }

    private void submitCleanupTask() {
        try {
            taskManager.submitBackgroundTask(new UsageProfileCleanupEvent());
        } catch (Exception e) {
            logger.error("Failed to submit usage profile cleanup", e);
        }
    }

    public AdmissionDecision admissionCheck(long estimatedBytes) {
        if (isCachingDisabled()) {
            return AdmissionDecision.REJECT;
        }
        if (config.isCacheUnlimited()) {
            return AdmissionDecision.ADMIT;
        }
        return userCacheBytes() + estimatedBytes > config.getMaxMemoryBytes()
                ? AdmissionDecision.REJECT
                : AdmissionDecision.ADMIT;
    }

    public void runEvictionSweep() {
        if (isCachingDisabled() || config.isCacheUnlimited()) {
            return;
        }
        if (!sweepRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            evictDownTo(config.getMaxMemoryBytes());
        } finally {
            sweepRunning.set(false);
        }
    }

    // Synchronous, between-pages guard for the streaming read path. Reuses the same
    // LFU eviction logic as the scheduled sweep so a long scan can reclaim other
    // cached resources to make room for the next page instead of waiting for the timer.
    public void ensureHeadroomForBytes(long nextPageEstimateBytes) {
        if (isCachingDisabled() || isCacheUnlimited()) {
            return;
        }
        final var maxBytes = config.getMaxMemoryBytes();
        if (userCacheBytes() + nextPageEstimateBytes <= maxBytes) {
            return;
        }
        evictDownTo(Math.max(0L, maxBytes - nextPageEstimateBytes));
    }

    private void evictDownTo(long targetBytes) {
        var resources = userCache.listCacheableResources();
        if (sumBytes(resources) <= targetBytes) {
            return;
        }
        final var ranked = new ArrayList<>(resources);
        ranked.sort(Comparator.comparingInt((CacheableResource r) -> tierOrdinal(r.kind()))
                .thenComparingLong(this::counterAccessCount).thenComparingLong(this::counterLastAccess));
        for (var resource : ranked) {
            if (userCacheBytes() <= targetBytes) {
                break;
            }
            if (!locks.tryLockWrite(resource.dbName(), resource.collName())) {
                continue;
            }
            try {
                switch (resource.kind()) {
                    case PK_INDEX -> userCache.evictPkIndex(resource.dbName(), resource.collName());
                    case FIELD_INDEX ->
                        userCache.evictFieldIndex(resource.dbName(), resource.collName(), resource.indexKey());
                    case COLLECTION -> userCache.evictCollectionDocuments(resource.dbName(), resource.collName());
                    default -> {
                    }
                }
            } finally {
                locks.releaseWrite(resource.dbName(), resource.collName());
            }
        }
    }

    public long userCacheBytes() {
        return sumBytes(userCache.listCacheableResources());
    }

    private long sumBytes(java.util.List<CacheableResource> resources) {
        long total = 0L;
        for (var r : resources) {
            total += r.estimatedSizeBytes();
        }
        return total;
    }

    private int tierOrdinal(AccessKind kind) {
        return switch (kind) {
            case COLLECTION -> 0;
            case FIELD_INDEX -> 1;
            case PK_INDEX -> 2;
        };
    }

    private long counterAccessCount(CacheableResource r) {
        final var counter = counters.get(buildKey(r.kind(), r.dbName(), r.collName(), r.indexKey()));
        return counter == null ? 0L : counter.getAccessCount();
    }

    private long counterLastAccess(CacheableResource r) {
        final var counter = counters.get(buildKey(r.kind(), r.dbName(), r.collName(), r.indexKey()));
        return counter == null ? 0L : counter.getLastAccessMillis();
    }
}
