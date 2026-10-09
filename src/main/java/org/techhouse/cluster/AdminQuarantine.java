package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.OnDiskNameRegistry;

final class AdminQuarantine {
    private final Logger logger = Logger.logFor(AdminQuarantine.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    void quarantineStaleIncarnation(String dbName, String collName, long snapshotIncarnation) throws Exception {
        final var localEntry = cache.getAdminCollectionEntry(dbName, collName);
        if (localEntry == null || snapshotIncarnation == 0 || localEntry.getIncarnation() == 0
                || localEntry.getIncarnation() >= snapshotIncarnation) {
            return;
        }
        final var stale = localEntry.getIncarnation();
        final var moved = unregisterAndMoveAside(dbName, collName, stale);
        logger.warning("Quarantined collection " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                + ": its documents belong to incarnation " + stale + ", which was dropped, and the cluster has since"
                + " re-created the name as incarnation " + snapshotIncarnation + ". They were " + movedOrLeft(moved)
                + " and the collection is now empty here.");
    }

    private boolean unregisterAndMoveAside(String dbName, String collName, long incarnation) throws Exception {
        AdminOperationHelper.deleteCollectionEntry(dbName, collName);
        AdminOperationHelper.deletePageCollections(dbName, collName);
        listenManager.endAllForCollection(dbName, collName, ListenManager.COLLECTION_DROPPED);
        final var moved = fs.folderQuarantine().moveCollectionAside(dbName, collName, incarnation);
        cache.evictCollection(dbName, collName);
        pendingIndexWrites.clearCollection(dbName, collName);
        return moved;
    }

    private static String movedOrLeft(boolean moved) {
        return moved ? "moved aside on disk" : "left on disk";
    }

    boolean clearCaseVariantDatabase(String dbName, Set<String> snapshotDbNames, long waitMillis) throws Exception {
        final var sibling = OnDiskNameRegistry.collidingDatabase(dbName);
        if (sibling == null || cache.getAdminDbEntry(sibling) == null) {
            return true;
        }
        if (snapshotDbNames.contains(sibling)) {
            warnAmbiguous("database " + dbName, "database " + sibling);
            return false;
        }
        return dropDatabase(sibling, waitMillis);
    }

    boolean clearCaseVariantCollection(String dbName, String collName, Set<String> snapshotCollIds, long waitMillis)
            throws Exception {
        final var sibling = OnDiskNameRegistry.collidingCollection(dbName, collName);
        if (sibling == null || cache.getAdminCollectionEntry(dbName, sibling) == null) {
            return true;
        }
        if (snapshotCollIds.contains(Cache.getCollectionIdentifier(dbName, sibling))) {
            warnAmbiguous("collection " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName,
                    "collection " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + sibling);
            return false;
        }
        return dropCollection(dbName, sibling, waitMillis);
    }

    private void warnAmbiguous(String installed, String sibling) {
        logger.warning("Skipping the install of " + installed + ": the winning admin snapshot also holds " + sibling
                + ", and both names share one folder on disk. An operator has to drop one of them.");
    }

    boolean dropAbsentCollections(HashMap<String, AdminDbEntry> snapshotDbs, HashSet<String> snapshotColls,
            long waitMillis) throws Exception {
        var complete = true;
        for (final var dbName : new ArrayList<>(cache.getUserDatabaseNames())) {
            if (!snapshotDbs.containsKey(dbName)) {
                continue;
            }
            for (final var collName : new ArrayList<>(cache.getCollectionNamesForDatabase(dbName))) {
                if (!snapshotColls.contains(Cache.getCollectionIdentifier(dbName, collName))) {
                    complete &= dropCollection(dbName, collName, waitMillis);
                }
            }
        }
        return complete;
    }

    private boolean dropCollection(String dbName, String collName, long waitMillis) throws Exception {
        if (!locks.tryLockWrite(dbName, collName, waitMillis)) {
            logger.warning("Skipping the quarantine of " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": its write lock stayed held for " + waitMillis + "ms. The next round retries it.");
            return false;
        }
        try {
            final var entry = cache.getAdminCollectionEntry(dbName, collName);
            final var moved = unregisterAndMoveAside(dbName, collName, entry != null ? entry.getIncarnation() : 0);
            logger.warning("Quarantined collection " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": it is absent from the winning admin snapshot. Its documents were " + movedOrLeft(moved)
                    + " and it no longer serves reads or writes until an operator reinstates or removes it.");
            return true;
        } finally {
            locks.release(dbName, collName);
            locks.removeLock(dbName, collName);
        }
    }

    boolean dropAbsentDatabases(HashMap<String, AdminDbEntry> snapshotDbs, long waitMillis) throws Exception {
        var complete = true;
        for (final var dbName : new ArrayList<>(cache.getUserDatabaseNames())) {
            if (!snapshotDbs.containsKey(dbName)) {
                complete &= dropDatabase(dbName, waitMillis);
            }
        }
        return complete;
    }

    private boolean dropDatabase(String dbName, long waitMillis) throws Exception {
        if (!locks.tryLockDatabaseExclusive(dbName, waitMillis)) {
            logger.warning("Skipping the quarantine of database " + dbName + ": it stayed locked for " + waitMillis
                    + "ms. The next round retries it.");
            return false;
        }
        try {
            return quarantineDatabaseUnderBarrier(dbName, waitMillis);
        } finally {
            locks.releaseDatabaseExclusive(dbName);
            locks.removeDatabaseLock(dbName);
        }
    }

    private boolean quarantineDatabaseUnderBarrier(String dbName, long waitMillis) throws Exception {
        final var dbEntry = cache.getAdminDbEntry(dbName);
        final var collNames = dbEntry != null ? new ArrayList<>(dbEntry.getCollections()) : new ArrayList<String>();
        Collections.sort(collNames);
        final var lockedColls = new ArrayList<String>();
        try {
            for (final var collName : collNames) {
                if (!locks.tryLockWrite(dbName, collName, waitMillis)) {
                    logger.warning("Skipping the quarantine of database " + dbName + ": the write lock of " + collName
                            + " stayed held for " + waitMillis + "ms. The next round retries it.");
                    return false;
                }
                lockedColls.add(collName);
            }
            AdminOperationHelper.deleteDatabaseEntry(dbName);
            listenManager.endAllForDatabase(dbName, ListenManager.DATABASE_DROPPED);
            final var moved = fs.folderQuarantine().moveDatabaseAside(dbName);
            cache.evictDatabase(dbName);
            pendingIndexWrites.clearDatabase(dbName);
            compiledProcedures.invalidateDatabase(dbName);
            scheduleRegistry.removeDatabase(dbName);
            logger.warning("Quarantined database " + dbName + ": it is absent from the winning admin snapshot. Its"
                    + " documents were " + movedOrLeft(moved) + " and it no longer serves reads or writes until an"
                    + " operator reinstates or removes it.");
            return true;
        } finally {
            for (final var collName : lockedColls) {
                locks.release(dbName, collName);
            }
            for (final var collName : lockedColls) {
                locks.removeLock(dbName, collName);
            }
        }
    }
}
