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
    private final MemoryPressureMonitor pressure = IocContainer.get(DefaultMemoryPressureMonitor.class);
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
                        try {
                            final var data = e.getData();
                            data.addProperty(Globals.PK_FIELD, e.get_id());
                            final var usage = AdminCollectionUsageEntry.fromJsonObject(data);
                            final var counter = new UsageCounter(usage.getKind(), usage.getDbName(), usage.getCollName(),
                                    usage.getIndexKey(), usage.getAccessCount(), usage.getLastAccessMillis());
                            counters.put(buildKey(counter.kind(), counter.dbName(), counter.collName(), counter.indexKey()), counter);
                        } catch (Exception inner) {
                            logger.warning("Skipping malformed collection_usage entry '" + e.get_id() + "': " +
                                    inner.getMessage());
                        }
                    }
                });
            }
        } catch (Exception e) {
            logger.warning("Failed to load collection usage profile, continuing with empty counters: " +
                    e.getMessage());
        }
    }

    public void startSweepThread() {
        if (isCachingDisabled()) {
            return;
        }
        final var interval = config.getMemoryManagementSweepIntervalSeconds();
        final var retentionMillis = config.getUsageProfileRetentionMillis();
        final var pollInterval = Math.max(1L, config.getPressurePollIntervalSeconds());
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            final var t = new Thread(r, "memory-management-sweep");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runEvictionSweepSafely, interval, interval, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::pollPressureAndMaybeSweep, pollInterval, pollInterval, TimeUnit.SECONDS);
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

    public void pollPressureAndMaybeSweep() {
        try {
            final var snapshot = safeSnapshot();
            if (isPressureHigh(snapshot)) {
                runEvictionSweep();
            }
        } catch (Exception e) {
            logger.error("Pressure poll failed", e);
        }
    }

    private void submitCleanupTask() {
        try {
            taskManager.submitBackgroundTask(new UsageProfileCleanupEvent());
        } catch (Exception e) {
            logger.error("Failed to submit usage profile cleanup", e);
        }
    }

    public AdmissionDecision admissionCheck() {
        if (isCachingDisabled()) {
            return AdmissionDecision.REJECT;
        }
        final var snapshot = safeSnapshot();
        if (snapshot.osMetricsAvailable() &&
                snapshot.osFreeRatio() < config.getOsFreeCriticalRatio()) {
            return AdmissionDecision.REJECT;
        }
        if (isPressureHigh(snapshot)) {
            return AdmissionDecision.REJECT;
        }
        if (heapBudgetExceeded(snapshot)) {
            return AdmissionDecision.REJECT;
        }
        return AdmissionDecision.ADMIT;
    }

    public void runEvictionSweep() {
        if (isCachingDisabled()) {
            return;
        }
        if (!sweepRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            final var snapshot = safeSnapshot();
            if (!isPressureHigh(snapshot) && !heapBudgetExceeded(snapshot)) {
                return;
            }
            final var overage = heapOverage(snapshot);
            final var resources = cache.listCacheableResources();
            final var ranked = new ArrayList<>(resources);
            ranked.sort(Comparator
                    .comparingInt((CacheableResource r) -> tierOrdinal(r.kind()))
                    .thenComparingLong(this::counterAccessCount)
                    .thenComparingLong(this::counterLastAccess));
            long evicted = 0L;
            for (var resource : ranked) {
                if (shouldStop(evicted, overage)) {
                    break;
                }
                if (!locks.tryLock(resource.dbName(), resource.collName())) {
                    continue;
                }
                try {
                    switch (resource.kind()) {
                        case PK_INDEX -> cache.evictPkIndex(resource.dbName(), resource.collName());
                        case FIELD_INDEX -> cache.evictFieldIndex(resource.dbName(), resource.collName(),
                                resource.indexKey());
                        case COLLECTION -> cache.evictCollectionDocuments(resource.dbName(), resource.collName());
                    }
                    evicted += resource.estimatedSizeBytes();
                } finally {
                    locks.release(resource.dbName(), resource.collName());
                }
            }
        } finally {
            sweepRunning.set(false);
        }
    }

    private boolean heapBudgetExceeded(MemoryPressureMonitor.Snapshot snapshot) {
        if (config.isCacheUnlimited()) {
            return false;
        }
        return snapshot.heapUsedBytes() > config.getMaxCollectionCacheBytes();
    }

    private long heapOverage(MemoryPressureMonitor.Snapshot snapshot) {
        if (config.isCacheUnlimited()) {
            return 0L;
        }
        return Math.max(0L, snapshot.heapUsedBytes() - config.getMaxCollectionCacheBytes());
    }

    private boolean isPressureHigh(MemoryPressureMonitor.Snapshot snapshot) {
        final var heapHigh = snapshot.heapUsedRatio() > config.getHeapHighWatermarkRatio();
        final var osLow = snapshot.osMetricsAvailable() &&
                snapshot.osFreeRatio() < config.getOsFreeLowWatermarkRatio();
        return heapHigh || osLow;
    }

    private boolean shouldStop(long evicted, long heapOverage) {
        final var snapshot = safeSnapshot();
        if (snapshot.heapUsedRatio() > config.getHeapLowWatermarkRatio()) {
            return false;
        }
        if (snapshot.osMetricsAvailable() &&
                snapshot.osFreeRatio() < config.getOsFreeHighWatermarkRatio()) {
            return false;
        }
        return heapOverage <= 0L || evicted >= heapOverage;
    }

    public MemoryPressureMonitor.Snapshot pressureSnapshot() {
        return safeSnapshot();
    }

    private MemoryPressureMonitor.Snapshot safeSnapshot() {
        try {
            return pressure.snapshot();
        } catch (Exception e) {
            logger.warning("Pressure snapshot failed: " + e.getMessage());
            return new MemoryPressureMonitor.Snapshot(0.0, 0L, 1.0, false);
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
