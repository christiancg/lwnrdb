package org.techhouse.cache;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;
import org.techhouse.bckg_ops.events.UsageProfileFlushEvent;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public class MemoryManagement {
    static final long SWEEP_INTERVAL_SECONDS = 5L;
    static final long FULL_MEASURE_FROM_PERCENT = 50L;
    static final long USAGE_RETENTION_MILLIS = 24L * 60L * 60L * 1000L;
    static final long USAGE_CLEANUP_INTERVAL_SECONDS = 60L * 60L;
    static final long OVER_CAP_LOCK_WAIT_MILLIS = 100L;
    static final long NO_LOCK_WAIT = 0L;

    private final Logger logger = Logger.logFor(MemoryManagement.class);
    private final Configuration config = Configuration.getInstance();
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private final UsageTracker usageTracker = IocContainer.get(UsageTracker.class);
    private final AtomicBoolean sweepRunning = new AtomicBoolean(false);
    private final AtomicBoolean sweepQueued = new AtomicBoolean(false);
    private final AtomicBoolean sweepOutOfMemory = new AtomicBoolean(false);
    private final AtomicBoolean requestedSweepOutOfMemory = new AtomicBoolean(false);
    private final AtomicBoolean flushOutOfMemory = new AtomicBoolean(false);
    private final AtomicBoolean usageFlushQueued = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

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

    public void forgetUsage(String dbName, String collName) {
        usageTracker.forgetCollection(dbName, collName);
    }

    public void forgetDatabaseUsage(String dbName) {
        usageTracker.forgetDatabase(dbName);
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
        final var started = Executors.newScheduledThreadPool(1, r -> {
            final var t = new Thread(r, "memory-management-sweep");
            t.setDaemon(true);
            return t;
        });
        scheduler = started;
        usageFlushQueued.set(false);
        started.scheduleAtFixedRate(this::runEvictionSweepSafely, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        started.scheduleAtFixedRate(this::flushUsageProfileSafely, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        started.scheduleAtFixedRate(this::submitCleanupTask, USAGE_CLEANUP_INTERVAL_SECONDS,
                USAGE_CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        userCache.onOverBudget(() -> requestSweep(started));
    }

    public void stopSweepThread() {
        userCache.onOverBudget(null);
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        sweepQueued.set(false);
    }

    private void requestSweep(ScheduledExecutorService on) {
        if (!sweepQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            on.execute(() -> {
                sweepQueued.set(false);
                runRequestedSweepSafely();
            });
        } catch (RejectedExecutionException e) {
            sweepQueued.set(false);
        }
    }

    private void runEvictionSweepSafely() {
        runSurvivingErrors("Eviction sweep failed", this::runEvictionSweep, sweepOutOfMemory);
    }

    private void runRequestedSweepSafely() {
        runSurvivingErrors("Requested eviction sweep failed", () -> sweep(Measure.FULL), requestedSweepOutOfMemory);
    }

    private void flushUsageProfileSafely() {
        runSurvivingErrors("Usage profile flush failed", this::flushUsageProfile, flushOutOfMemory);
    }

    private void runSurvivingErrors(String failure, Runnable task, AtomicBoolean previousRunOutOfMemory) {
        try {
            task.run();
            previousRunOutOfMemory.set(false);
        } catch (OutOfMemoryError e) {
            logger.error(failure, e);
            if (previousRunOutOfMemory.getAndSet(true)) {
                throw e;
            }
        } catch (Exception | Error e) {
            logger.error(failure, e);
            previousRunOutOfMemory.set(false);
        }
    }

    public void flushUsageProfile() {
        if (isCachingDisabled() || !usageTracker.hasDirtyCounters()) {
            return;
        }
        if (usageFlushQueued.compareAndSet(false, true)
                && !taskManager.submitMaintenance(new UsageProfileFlushEvent())) {
            usageFlushQueued.set(false);
        }
    }

    public List<UsageCounter> takeDirtyUsage() {
        usageFlushQueued.set(false);
        return usageTracker.drainDirtyCounters();
    }

    public void requeueUsage(List<UsageCounter> dirty) {
        usageTracker.requeue(dirty);
    }

    private void submitCleanupTask() {
        try {
            taskManager.submitMaintenance(new UsageProfileCleanupEvent());
        } catch (Exception | Error e) {
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
        return userCache.footprintBytes() + estimatedBytes > config.getMaxMemoryBytes()
                ? AdmissionDecision.REJECT
                : AdmissionDecision.ADMIT;
    }

    public void runEvictionSweep() {
        sweep(Measure.FULL_FORCED);
    }

    private void sweep(Measure overBudgetMeasure) {
        if (isCachingDisabled() || config.isCacheUnlimited()) {
            return;
        }
        if (!sweepRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            final var maxBytes = config.getMaxMemoryBytes();
            final var remembered = userCache.footprintBytes();
            if (remembered > maxBytes) {
                evictDownTo(maxBytes, overBudgetMeasure, OVER_CAP_LOCK_WAIT_MILLIS);
            } else if (remembered * 100L >= maxBytes * FULL_MEASURE_FROM_PERCENT) {
                evictDownTo(maxBytes, Measure.FULL, NO_LOCK_WAIT);
            }
        } finally {
            sweepRunning.set(false);
        }
    }

    public void ensureHeadroomForBytes(long nextPageEstimateBytes) {
        if (isCachingDisabled() || isCacheUnlimited()) {
            return;
        }
        final var maxBytes = config.getMaxMemoryBytes();
        if (userCache.footprintBytes() + nextPageEstimateBytes <= maxBytes) {
            return;
        }
        evictDownTo(Math.max(0L, maxBytes - nextPageEstimateBytes), Measure.REMEMBERED, NO_LOCK_WAIT);
    }

    private void evictDownTo(long targetBytes, Measure measure, long lockWaitMillis) {
        final var resources = userCache.listCacheableResources(measure);
        var remaining = sumBytes(resources);
        if (remaining <= targetBytes) {
            return;
        }
        final var busyCollections = new HashSet<String>();
        for (var resource : EvictionRanking.rank(resources, usageTracker)) {
            if (remaining <= targetBytes) {
                break;
            }
            final var collection = Cache.getCollectionIdentifier(resource.dbName(), resource.collName());
            if (busyCollections.contains(collection)) {
                continue;
            }
            if (!lockForEviction(resource, lockWaitMillis)) {
                busyCollections.add(collection);
                continue;
            }
            final var indexedField = lockedFieldOf(resource);
            if (indexedField != null && !locks.tryLockIndex(resource.dbName(), resource.collName(), indexedField)) {
                locks.releaseWrite(resource.dbName(), resource.collName());
                continue;
            }
            try {
                userCache.evict(resource);
                remaining -= resource.estimatedSizeBytes();
            } finally {
                if (indexedField != null) {
                    locks.releaseIndex(resource.dbName(), resource.collName(), indexedField);
                }
                locks.releaseWrite(resource.dbName(), resource.collName());
            }
        }
    }

    private boolean lockForEviction(CacheableResource resource, long lockWaitMillis) {
        if (lockWaitMillis == NO_LOCK_WAIT) {
            return locks.tryLockWrite(resource.dbName(), resource.collName());
        }
        try {
            return locks.tryLockWrite(resource.dbName(), resource.collName(), lockWaitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String lockedFieldOf(CacheableResource resource) {
        if (resource.kind() != AccessKind.FIELD_INDEX || resource.indexKey() == null) {
            return null;
        }
        return CacheableResource.indexedFieldOf(resource.indexKey());
    }

    public long userCacheBytes() {
        return sumBytes(userCache.listCacheableResources(Measure.FULL));
    }

    private long sumBytes(List<CacheableResource> resources) {
        long total = 0L;
        for (var r : resources) {
            total += r.estimatedSizeBytes();
        }
        return total;
    }

}
