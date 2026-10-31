package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.VacuumedPage;

final class PkIndexTier {
    static final long LOCK_FREE_POINT_WAIT_MILLIS = 100L;

    private final UserCache cache;

    private interface ResidentRead<R> {
        R read(List<PkIndexEntry> resident) throws IOException;
    }

    private interface DiskRead<R> {
        R read() throws IOException;
    }

    PkIndexTier(UserCache cache) {
        this.cache = cache;
    }

    List<PkIndexEntry> resident(String collectionIdentifier) {
        return cache.pkIndexes().get(collectionIdentifier);
    }

    List<PkIndexEntry> loadIfNecessary(String dbName, String collName) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var primaryKeyIndex = resident(collectionIdentifier);
        final var locked = cache.locks().holdsCollectionLock(dbName, collName);
        if (primaryKeyIndex == null) {
            final var fromDisk = cache.wholeLoads().pkIndex(dbName, collName, locked,
                    bytes -> cache.loadsAdmitted(dbName, collName, AccessKind.PK_INDEX, bytes));
            if (fromDisk != null && !cache.isCachingDisabled(dbName) && locked) {
                return admit(dbName, collName, collectionIdentifier, fromDisk);
            }
            return fromDisk;
        }
        if (locked) {
            return primaryKeyIndex;
        }
        return snapshotForLockFreeReader(dbName, collName, collectionIdentifier);
    }

    WriterPkRows forWriter(String dbName, String collName, Collection<String> ids) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        if (resident(collectionIdentifier) == null && cache.locks().holdsCollectionLock(dbName, collName)
                && pointScansOnly(dbName, collName, collectionIdentifier)) {
            final var found = ids.isEmpty()
                    ? Map.<String, PkIndexEntry>of()
                    : cache.fs().pkRowsFor(dbName, collName, ids);
            return WriterPkRows.scanned(found);
        }
        return WriterPkRows.of(loadIfNecessary(dbName, collName));
    }

    private List<PkIndexEntry> admit(String dbName, String collName, String collectionIdentifier,
            List<PkIndexEntry> fromDisk) {
        final var admitted = resident(collectionIdentifier);
        if (admitted != null) {
            return admitted;
        }
        final var rows = fromDisk.size();
        final var minimumEntryBytes = CacheSizeEstimator.minimumPkEntryBytes();
        if (!cache.admissionFeasible(AccessKind.PK_INDEX, rows * minimumEntryBytes)) {
            refuse(dbName, collName, collectionIdentifier, minimumEntryBytes, rows);
            return fromDisk;
        }
        final var bytesPerEntry = CacheSizeEstimator.pkIndexBytesPerEntry(fromDisk);
        if (!cache.shouldCache(dbName,
                new AdmissionRequest(AccessKind.PK_INDEX, dbName, collName, null, bytesPerEntry * rows))) {
            refuse(dbName, collName, collectionIdentifier, bytesPerEntry, rows);
            return fromDisk;
        }
        final var cached = PkIndexList.of(fromDisk);
        cached.chargeTo(cache.sizes().footprint().newCell(AccessKind.PK_INDEX), bytesPerEntry);
        final var raced = cache.pkIndexes().putIfAbsent(collectionIdentifier, cached);
        if (raced != null) {
            cached.retireCharge();
            return raced;
        }
        cache.sizes().clearRefusedPk(collectionIdentifier);
        return cached;
    }

    private void refuse(String dbName, String collName, String collectionIdentifier, long bytesPerEntry, long rows) {
        cache.sizes().refusePk(collectionIdentifier, bytesPerEntry, rows);
        cache.fitWarnings().pkIndexOutgrows(dbName, collName, bytesPerEntry * rows, cache.maxMemoryBytes());
    }

    private List<PkIndexEntry> snapshotForLockFreeReader(String dbName, String collName, String collectionIdentifier)
            throws IOException {
        var held = false;
        try {
            held = cache.locks().tryLockRead(dbName, collName, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!held) {
            return cache.wholeLoads().transientPkIndex(dbName, collName);
        }
        try {
            final var shared = resident(collectionIdentifier);
            return shared != null ? new ArrayList<>(shared) : cache.wholeLoads().transientPkIndex(dbName, collName);
        } finally {
            cache.locks().releaseRead(dbName, collName);
        }
    }

    void repositionAfterVacuum(VacuumedPage vacuumed) {
        final var primaryKeyIndex = resident(Cache.getCollectionIdentifier(vacuumed.dbName(), vacuumed.collName()));
        if (primaryKeyIndex != null) {
            vacuumed.reposition(id -> PkIndexList.rowOf(primaryKeyIndex, id));
        }
    }

    PkIndexEntry rowFor(String dbName, String collName, String id) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        if (cache.locks().holdsCollectionLock(dbName, collName)) {
            final var held = resident(collectionIdentifier);
            if (held != null) {
                return detached(PkIndexList.rowOf(held, id));
            }
            if (pointScansOnly(dbName, collName, collectionIdentifier)) {
                return cache.fs().pkRowsFor(dbName, collName, List.of(id)).get(id);
            }
            return detached(PkIndexList.rowOf(loadIfNecessary(dbName, collName), id));
        }
        return lockFreeRead(dbName, collName, collectionIdentifier,
                resident -> detached(PkIndexList.rowOf(resident, id)),
                () -> cache.fs().pkRowsFor(dbName, collName, List.of(id)).get(id));
    }

    List<PkIndexEntry> rowsFor(String dbName, String collName, Collection<String> ids, PkRows pinned)
            throws IOException {
        if (pinned != null) {
            final var rows = pinned.rows(dbName, collName);
            if (rows != null) {
                return pick(rows, ids);
            }
        }
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        if (cache.locks().holdsCollectionLock(dbName, collName)) {
            final var held = resident(collectionIdentifier);
            if (held != null) {
                return pick(held, ids);
            }
            if (pointScansOnly(dbName, collName, collectionIdentifier)) {
                return scanned(dbName, collName, ids);
            }
            return pick(loadIfNecessary(dbName, collName), ids);
        }
        return lockFreeRead(dbName, collName, collectionIdentifier, resident -> pick(resident, ids),
                () -> scanned(dbName, collName, ids));
    }

    int countForCoverage(String dbName, String collName) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        if (cache.locks().holdsCollectionLock(dbName, collName)) {
            return heldCount(dbName, collName, collectionIdentifier);
        }
        final var shared = resident(collectionIdentifier);
        if (shared != null) {
            return publishedSize(shared);
        }
        final var trusted = cache.fs().trustedLiveRows(dbName, collName);
        return trusted.isPresent() ? (int) trusted.getAsLong() : UserCache.UNKNOWN_COUNT;
    }

    int size(String dbName, String collName) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        if (cache.locks().holdsCollectionLock(dbName, collName)) {
            return heldCount(dbName, collName, collectionIdentifier);
        }
        final var shared = resident(collectionIdentifier);
        if (shared != null) {
            return publishedSize(shared);
        }
        final var trusted = cache.fs().trustedLiveRows(dbName, collName);
        return trusted.isPresent()
                ? (int) trusted.getAsLong()
                : cache.wholeLoads().transientPkIndex(dbName, collName).size();
    }

    private int heldCount(String dbName, String collName, String collectionIdentifier) throws IOException {
        final var held = resident(collectionIdentifier);
        if (held != null) {
            return held.size();
        }
        if (pointScansOnly(dbName, collName, collectionIdentifier)) {
            final var trusted = cache.fs().trustedLiveRows(dbName, collName);
            if (trusted.isPresent()) {
                return (int) trusted.getAsLong();
            }
        }
        return loadIfNecessary(dbName, collName).size();
    }

    private static int publishedSize(List<PkIndexEntry> resident) {
        return resident instanceof PkIndexList list ? list.publishedSize() : resident.size();
    }

    private boolean pointScansOnly(String dbName, String collName, String collectionIdentifier) {
        if (UserCache.isReservedDatabase(dbName)) {
            return false;
        }
        if (cache.isCachingDisabled(dbName)) {
            return true;
        }
        final var refused = cache.sizes().refusedPk(collectionIdentifier);
        if (refused != null) {
            return !cache.admissionFeasible(AccessKind.PK_INDEX, refused.bytes());
        }
        final var rows = cache.fs().trustedLiveRows(dbName, collName);
        final var minimumEntryBytes = CacheSizeEstimator.minimumPkEntryBytes();
        if (rows.isPresent() && !cache.admissionFeasible(AccessKind.PK_INDEX, rows.getAsLong() * minimumEntryBytes)) {
            refuse(dbName, collName, collectionIdentifier, minimumEntryBytes, rows.getAsLong());
            return true;
        }
        return false;
    }

    private <R> R lockFreeRead(String dbName, String collName, String collectionIdentifier, ResidentRead<R> onResident,
            DiskRead<R> fromDisk) throws IOException {
        if (resident(collectionIdentifier) == null || !tryPointLock(dbName, collName)) {
            return fromDisk.read();
        }
        try {
            final var shared = resident(collectionIdentifier);
            return shared != null ? onResident.read(shared) : fromDisk.read();
        } finally {
            cache.locks().releaseRead(dbName, collName);
        }
    }

    private boolean tryPointLock(String dbName, String collName) {
        try {
            return cache.locks().tryLockRead(dbName, collName, LOCK_FREE_POINT_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<PkIndexEntry> scanned(String dbName, String collName, Collection<String> ids) throws IOException {
        return new ArrayList<>(cache.fs().pkRowsFor(dbName, collName, ids).values());
    }

    private static List<PkIndexEntry> pick(List<PkIndexEntry> rows, Collection<String> ids) {
        final var picked = new ArrayList<PkIndexEntry>(ids.size());
        for (final var id : ids) {
            final var row = PkIndexList.rowOf(rows, id);
            if (row != null) {
                picked.add(row.detachedCopy());
            }
        }
        return picked;
    }

    private static PkIndexEntry detached(PkIndexEntry row) {
        return row == null ? null : row.detachedCopy();
    }

    void evict(String collectionIdentifier) {
        final var removed = cache.pkIndexes().remove(collectionIdentifier);
        if (removed instanceof PkIndexList list) {
            list.retireCharge();
        }
    }
}
