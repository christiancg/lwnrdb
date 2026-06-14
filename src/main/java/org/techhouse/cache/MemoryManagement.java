package org.techhouse.cache;

import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollectionUsageEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MemoryManagement {
    private final Logger logger = Logger.logFor(MemoryManagement.class);
    private final Configuration config = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private final ConcurrentHashMap<String, UsageCounter> counters = new ConcurrentHashMap<>();
    private final AtomicBoolean sweepRunning = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public static String buildKey(AccessKind kind, String dbName, String collName, String indexKey) {
        return kind.name() + Globals.COLL_IDENTIFIER_SEPARATOR + dbName +
                Globals.COLL_IDENTIFIER_SEPARATOR + collName +
                Globals.COLL_IDENTIFIER_SEPARATOR + (indexKey == null ? "" : indexKey);
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

    public void loadProfileFromAdmin() {
        if (isCachingDisabled()) {
            return;
        }
        try {
            try (final var pagesStream = fs.streamPages(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME)) {
                pagesStream.forEach(map -> {
                    for (var e : map.values()) {
                        final var data = e.getData();
                        data.addProperty(Globals.PK_FIELD, e.get_id());
                        final var usage = AdminCollectionUsageEntry.fromJsonObject(data);
                        final var counter = new UsageCounter(usage.getKind(), usage.getDbName(), usage.getCollName(),
                                usage.getIndexKey(), usage.getAccessCount(), usage.getLastAccessMillis());
                        counters.put(buildKey(counter.kind(), counter.dbName(), counter.collName(), counter.indexKey()), counter);
                    }
                });
            }
        } catch (IOException e) {
            logger.warning("Failed to load collection usage profile: " + e.getMessage());
        }
    }

    public void startSweepThread() {
        if (isCachingDisabled() || isCacheUnlimited()) {
            return;
        }
        final var interval = config.getMemoryManagementSweepIntervalSeconds();
        final var retentionMillis = config.getUsageProfileRetentionMillis();
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            final var t = new Thread(r, "memory-management-sweep");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runEvictionSweepSafely, interval, interval, TimeUnit.SECONDS);
        final var cleanupIntervalSeconds = Math.max(60L, retentionMillis / 4_000L);
        scheduler.scheduleAtFixedRate(this::submitCleanupTask, cleanupIntervalSeconds, cleanupIntervalSeconds,
                TimeUnit.SECONDS);
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

    public void runEvictionSweep() {
        if (isCachingDisabled() || isCacheUnlimited()) {
            return;
        }
        if (!sweepRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            final var maxBytes = config.getMaxCollectionCacheBytes();
            final var resources = cache.listCacheableResources();
            long currentTotal = 0L;
            for (var resource : resources) {
                currentTotal += resource.estimatedSizeBytes();
            }
            if (currentTotal <= maxBytes) {
                return;
            }
            final var ranked = new ArrayList<>(resources);
            ranked.sort(Comparator
                    .comparingInt((CacheableResource r) -> tierOrdinal(r.kind()))
                    .thenComparingLong(this::counterAccessCount)
                    .thenComparingLong(this::counterLastAccess));
            for (var resource : ranked) {
                if (currentTotal <= maxBytes) {
                    break;
                }
                try {
                    locks.lock(resource.dbName(), resource.collName());
                    switch (resource.kind()) {
                        case PK_INDEX -> cache.evictPkIndex(resource.dbName(), resource.collName());
                        case FIELD_INDEX -> cache.evictFieldIndex(resource.dbName(), resource.collName(),
                                resource.indexKey());
                        case COLLECTION -> cache.evictCollectionDocuments(resource.dbName(), resource.collName());
                    }
                    currentTotal -= resource.estimatedSizeBytes();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    locks.release(resource.dbName(), resource.collName());
                }
            }
        } finally {
            sweepRunning.set(false);
        }
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
