package org.techhouse.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public class MemoryManagement {
    static final long SWEEP_INTERVAL_SECONDS = 5L;
    static final long USAGE_RETENTION_MILLIS = 24L * 60L * 60L * 1000L;
    static final long USAGE_CLEANUP_INTERVAL_SECONDS = 60L * 60L;

    private final Logger logger = Logger.logFor(MemoryManagement.class);
    private final Configuration config = Configuration.getInstance();
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private final UsageTracker usageTracker = IocContainer.get(UsageTracker.class);
    private final AtomicBoolean sweepRunning = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public static String buildKey(AccessKind kind, String dbName, String collName, String indexKey) {
        return UsageTracker.buildKey(kind, dbName, collName, indexKey);
    }

    public void recordAccess(AccessKind kind, String dbName, String collName, String indexKey) {
        usageTracker.recordAccess(kind, dbName, collName, indexKey);
    }

    public UsageCounter getCounter(AccessKind kind, String dbName, String collName, String indexKey) {
        return usageTracker.getCounter(kind, dbName, collName, indexKey);
    }

    public Map<String, UsageCounter> getCountersSnapshot() {
        return usageTracker.getCountersSnapshot();
    }

    public void clearCounter(AccessKind kind, String dbName, String collName, String indexKey) {
        usageTracker.clearCounter(kind, dbName, collName, indexKey);
    }

    public void putCounter(UsageCounter counter) {
        usageTracker.putCounter(counter);
    }

    public void loadProfileFromAdmin() {
        usageTracker.loadProfileFromAdmin();
    }

    public boolean isCachingDisabled() {
        return config.isCachingDisabled();
    }

    public boolean isCacheUnlimited() {
        return config.isCacheUnlimited();
    }

    public long usageRetentionMillis() {
        return USAGE_RETENTION_MILLIS;
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
        scheduler.scheduleAtFixedRate(this::flushUsageProfileSafely, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS,
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

    private void flushUsageProfileSafely() {
        try {
            flushUsageProfile();
        } catch (Exception e) {
            logger.error("Usage profile flush failed", e);
        }
    }

    public void flushUsageProfile() {
        if (isCachingDisabled()) {
            return;
        }
        for (final var counter : usageTracker.drainDirtyCounters()) {
            taskManager.submitBackgroundTask(new CollectionUsageEvent(counter.kind(), counter.dbName(),
                    counter.collName(), counter.indexKey(), counter.getLastAccessMillis()));
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

    private record RankedResource(CacheableResource resource, int tier, long accessCount, long lastAccess) {
    }

    private void evictDownTo(long targetBytes) {
        var resources = userCache.listCacheableResources();
        var remaining = sumBytes(resources);
        if (remaining <= targetBytes) {
            return;
        }
        final var ranked = new ArrayList<RankedResource>(resources.size());
        for (var resource : resources) {
            ranked.add(new RankedResource(resource, tierOrdinal(resource.kind()), usageTracker.accessCountFor(resource),
                    usageTracker.lastAccessFor(resource)));
        }
        ranked.sort(Comparator.comparingInt(RankedResource::tier).thenComparingLong(RankedResource::accessCount)
                .thenComparingLong(RankedResource::lastAccess));
        for (var candidate : ranked) {
            if (remaining <= targetBytes) {
                break;
            }
            final var resource = candidate.resource();
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
                remaining -= resource.estimatedSizeBytes();
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

}
