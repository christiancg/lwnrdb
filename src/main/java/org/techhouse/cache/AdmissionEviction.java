package org.techhouse.cache;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;
import org.techhouse.log.Logger;

final class AdmissionEviction {
    static final long BACKOFF_NANOS = OverBudgetSignal.CHECK_INTERVAL_NANOS;
    private static final long NOT_BACKING_OFF = Long.MIN_VALUE;
    private static final Logger logger = Logger.logFor(AdmissionEviction.class);

    private final UserCache cache;
    private final LongSupplier clock;
    private final AtomicLongArray retryAfter = new AtomicLongArray(AccessKind.values().length);

    AdmissionEviction(UserCache cache) {
        this(cache, System::nanoTime);
    }

    AdmissionEviction(UserCache cache, LongSupplier clock) {
        this.cache = cache;
        this.clock = clock;
        for (var tier = 0; tier < retryAfter.length(); tier++) {
            retryAfter.set(tier, NOT_BACKING_OFF);
        }
    }

    boolean feasible(AccessKind tier, long bytes, long maxBytes) {
        final var footprint = cache.sizes().footprint();
        return footprint.bytes() - footprint.bytesBelow(tier) + bytes <= maxBytes;
    }

    boolean mayMakeRoom(AdmissionRequest request, long maxBytes) {
        return request.tier() != AccessKind.COLLECTION && feasible(request.tier(), request.bytes(), maxBytes)
                && !backingOff(request.tier());
    }

    boolean makeRoom(AdmissionRequest request, long maxBytes) {
        if (!mayMakeRoom(request, maxBytes)) {
            return false;
        }
        try {
            if (evictBelow(request, maxBytes)) {
                return true;
            }
        } catch (RuntimeException e) {
            logger.warning("Could not evict lower cache tiers to admit a " + request.tier() + " of " + request.dbName()
                    + "|" + request.collName() + ": " + e.getMessage());
        }
        retryAfter.set(request.tier().ordinal(), clock.getAsLong() + BACKOFF_NANOS);
        return false;
    }

    private boolean backingOff(AccessKind tier) {
        final var until = retryAfter.get(tier.ordinal());
        return until != NOT_BACKING_OFF && clock.getAsLong() - until < 0;
    }

    private boolean evictBelow(AdmissionRequest request, long maxBytes) {
        final var requestedTier = EvictionRanking.tierOrdinal(request.tier());
        final var candidates = new ArrayList<CacheableResource>();
        for (final var resource : cache.listCacheableResources(Measure.REMEMBERED)) {
            if (EvictionRanking.tierOrdinal(resource.kind()) < requestedTier) {
                candidates.add(resource);
            }
        }
        for (final var resource : EvictionRanking.rank(candidates, cache.usageTracker())) {
            if (fits(request, maxBytes)) {
                return true;
            }
            evictIfFree(resource, request);
        }
        return fits(request, maxBytes);
    }

    private boolean fits(AdmissionRequest request, long maxBytes) {
        return cache.footprintBytes() + request.bytes() <= maxBytes;
    }

    private void evictIfFree(CacheableResource resource, AdmissionRequest request) {
        final var locks = cache.locks();
        final var dbName = resource.dbName();
        final var collName = resource.collName();
        final var field = resource.kind() == AccessKind.FIELD_INDEX && resource.indexKey() != null
                ? CacheableResource.indexedFieldOf(resource.indexKey())
                : null;
        if (field != null
                && (request.isFieldOf(dbName, collName, field) || locks.holdsIndexLock(dbName, collName, field))) {
            return;
        }
        final var alreadyHeld = locks.holdsCollectionLock(dbName, collName);
        if (!alreadyHeld && !tryReadLock(dbName, collName)) {
            return;
        }
        try {
            if (field == null) {
                cache.evict(resource);
            } else if (locks.tryLockIndex(dbName, collName, field)) {
                try {
                    cache.evict(resource);
                } finally {
                    locks.releaseIndex(dbName, collName, field);
                }
            }
        } finally {
            if (!alreadyHeld) {
                locks.releaseRead(dbName, collName);
            }
        }
    }

    private boolean tryReadLock(String dbName, String collName) {
        try {
            return cache.locks().tryLockRead(dbName, collName, 0L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
