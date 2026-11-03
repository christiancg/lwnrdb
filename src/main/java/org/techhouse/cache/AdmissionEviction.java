package org.techhouse.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;

final class AdmissionEviction {
    static final long BACKOFF_NANOS = OverBudgetSignal.CHECK_INTERVAL_NANOS;
    private static final long NOT_BACKING_OFF = Long.MIN_VALUE;
    private static final Logger logger = Logger.logFor(AdmissionEviction.class);

    private final UserCache cache;
    private final LongSupplier clock;
    private final AtomicLongArray retryAfter = new AtomicLongArray(AccessKind.values().length);
    private final AtomicLong sameTierRetryAfter = new AtomicLong(NOT_BACKING_OFF);
    private final RefusalHistory refusals;

    record SameTierVictim(CacheableResource resource, long accessCount) {
    }

    AdmissionEviction(UserCache cache) {
        this(cache, System::nanoTime);
    }

    AdmissionEviction(UserCache cache, LongSupplier clock) {
        this.cache = cache;
        this.clock = clock;
        this.refusals = new RefusalHistory(clock);
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
        return backingOff(retryAfter.get(tier.ordinal()));
    }

    private boolean backingOff(long until) {
        return until != NOT_BACKING_OFF && clock.getAsLong() - until < 0;
    }

    void recordRefusal(AdmissionRequest request) {
        if (request.fieldName() != null) {
            refusals.record(refusalKeyOf(request));
        }
    }

    boolean makeRoomInTier(AdmissionRequest request, long maxBytes) {
        final var requester = new CacheableResource(AccessKind.FIELD_INDEX, request.dbName(), request.collName(),
                request.fieldName(), request.bytes());
        return makeRoomInTier(request, maxBytes, cache.usageTracker().accessCountFor(requester));
    }

    boolean makeRoomInTier(AdmissionRequest request, long maxBytes, long requesterAccessCount) {
        if (request.tier() != AccessKind.FIELD_INDEX || request.fieldName() == null || request.bytes() > maxBytes
                || !refusals.refusedRepeatedly(refusalKeyOf(request)) || backingOff(sameTierRetryAfter.get())) {
            return false;
        }
        try {
            final var victims = sameTierVictims(request, maxBytes, requesterAccessCount);
            for (final var victim : victims) {
                if (feasible(request.tier(), request.bytes(), maxBytes)) {
                    break;
                }
                evictSameTier(victim.resource(), request);
            }
            if (!victims.isEmpty() && feasible(request.tier(), request.bytes(), maxBytes)) {
                return true;
            }
        } catch (RuntimeException e) {
            logger.warning("Could not evict colder indexes to admit the field index " + request.fieldName() + " of "
                    + request.dbName() + "|" + request.collName() + ": " + e.getMessage());
        }
        sameTierRetryAfter.set(clock.getAsLong() + BACKOFF_NANOS);
        return false;
    }

    private static String refusalKeyOf(AdmissionRequest request) {
        return Cache.getCollectionIdentifier(request.dbName(), request.collName()) + Globals.COLL_IDENTIFIER_SEPARATOR
                + request.fieldName();
    }

    private List<SameTierVictim> sameTierVictims(AdmissionRequest request, long maxBytes, long requesterAccessCount) {
        final var footprint = cache.sizes().footprint();
        var needed = footprint.bytes() - footprint.bytesBelow(AccessKind.FIELD_INDEX) + request.bytes() - maxBytes;
        final var candidates = new ArrayList<CacheableResource>();
        for (final var resource : cache.listCacheableResources(Measure.REMEMBERED)) {
            if (mayBeDisplaced(resource, request)) {
                candidates.add(resource);
            }
        }
        final var victims = new ArrayList<SameTierVictim>();
        for (final var resource : EvictionRanking.rank(candidates, cache.usageTracker())) {
            if (needed <= 0) {
                break;
            }
            final var accessCount = displacedAccessCount(resource);
            if (requesterAccessCount / Globals.SAME_TIER_EVICTION_RATIO >= Math.max(1L, accessCount)) {
                victims.add(new SameTierVictim(resource, accessCount));
                needed -= resource.estimatedSizeBytes();
            }
        }
        return needed <= 0 ? victims : List.of();
    }

    private boolean mayBeDisplaced(CacheableResource resource, AdmissionRequest request) {
        final var locks = cache.locks();
        final var dbName = resource.dbName();
        final var collName = resource.collName();
        return switch (resource.kind()) {
            case COLLECTION -> false;
            case FIELD_INDEX -> resource.indexKey() != null
                    && !request.isFieldOf(dbName, collName, CacheableResource.indexedFieldOf(resource.indexKey()))
                    && !locks.holdsIndexLock(dbName, collName, CacheableResource.indexedFieldOf(resource.indexKey()));
            case PK_INDEX -> !UserCache.isReservedDatabase(dbName) && !locks.holdsCollectionLock(dbName, collName)
                    && cache.fs().trustedLiveRows(dbName, collName).isPresent();
        };
    }

    private long displacedAccessCount(CacheableResource resource) {
        final var usage = cache.usageTracker();
        if (resource.kind() != AccessKind.PK_INDEX) {
            return usage.accessCountFor(resource);
        }
        final var documents = new CacheableResource(AccessKind.COLLECTION, resource.dbName(), resource.collName(), null,
                0L);
        return usage.accessCountFor(resource) + usage.accessCountFor(documents);
    }

    private void evictSameTier(CacheableResource resource, AdmissionRequest request) {
        if (resource.kind() == AccessKind.PK_INDEX) {
            displacePk(resource);
        } else {
            evictIfFree(resource, request);
        }
    }

    private void displacePk(CacheableResource resource) {
        final var locks = cache.locks();
        final var dbName = resource.dbName();
        final var collName = resource.collName();
        if (locks.holdsCollectionLock(dbName, collName) || !locks.tryLockWrite(dbName, collName)) {
            return;
        }
        try {
            final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
            final var resident = cache.pkIndexes().get(collectionIdentifier);
            if (resident == null) {
                return;
            }
            final var rows = Math.max(1L, resident.size());
            cache.evict(resource);
            cache.sizes().displacePk(collectionIdentifier, Math.max(1L, resource.estimatedSizeBytes() / rows), rows);
        } finally {
            locks.releaseWrite(dbName, collName);
        }
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
