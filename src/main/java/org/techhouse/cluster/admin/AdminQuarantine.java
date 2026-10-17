package org.techhouse.cluster.admin;

import java.util.List;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ex.CollectionBusyException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.admin.CollectionIncarnation;

final class AdminQuarantine {
    private final Logger logger = Logger.logFor(AdminQuarantine.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);

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
        triggerExecutor.discardQueuedFor(dbName, collName);
        TriggerRunLog.discardAllFor(dbName, collName);
        return moved;
    }

    private static String movedOrLeft(boolean moved) {
        return moved ? "moved aside on disk" : "left on disk";
    }

    boolean dropCollection(String dbName, String collName, long waitMillis) throws Exception {
        if (!locks.tryLockWrite(dbName, collName, waitMillis)) {
            logger.warning("Skipping the quarantine of " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": its write lock stayed held for " + waitMillis + "ms. The next round retries it.");
            return false;
        }
        try {
            return dropCollectionUnderNamesLock(dbName, collName, waitMillis);
        } finally {
            locks.release(dbName, collName);
            locks.removeLock(dbName, collName);
        }
    }

    private boolean dropCollectionUnderNamesLock(String dbName, String collName, long waitMillis) throws Exception {
        if (!locks.tryLockWrite(dbName, Globals.COLLECTION_NAMES_LOCK, waitMillis)) {
            logger.warning("Skipping the quarantine of " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": the collection names lock stayed held for " + waitMillis + "ms. The next round retries it.");
            return false;
        }
        try {
            final var moved = unregisterAndMoveAside(dbName, collName, CollectionIncarnation.current(dbName, collName));
            logger.warning("Quarantined collection " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": the cluster dropped it while this node held it. Its documents were " + movedOrLeft(moved)
                    + " and it no longer serves reads or writes until an operator reinstates or removes it.");
            return true;
        } finally {
            locks.release(dbName, Globals.COLLECTION_NAMES_LOCK);
        }
    }

    boolean dropDatabase(String dbName, long waitMillis) throws Exception {
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
        final var collNames = dbEntry != null ? dbEntry.getCollections() : List.<String>of();
        try {
            return locks.withRetiringCollectionLocks(dbName, collNames, waitMillis, () -> {
                AdminOperationHelper.deleteDatabaseEntry(dbName);
                listenManager.endAllForDatabase(dbName, ListenManager.DATABASE_DROPPED);
                final var moved = fs.folderQuarantine().moveDatabaseAside(dbName);
                cache.evictDatabase(dbName);
                pendingIndexWrites.clearDatabase(dbName);
                compiledProcedures.invalidateDatabase(dbName);
                scheduleRegistry.removeDatabase(dbName);
                triggerExecutor.discardQueuedFor(dbName, null);
                TriggerRunLog.discardAllFor(dbName, null);
                logger.warning("Quarantined database " + dbName + ": the cluster dropped it while this node held it."
                        + " Its documents were " + movedOrLeft(moved) + " and it no longer serves reads or writes"
                        + " until an operator reinstates or removes it.");
                return true;
            });
        } catch (CollectionBusyException busy) {
            logger.warning("Skipping the quarantine of database " + dbName + ": " + busy.getMessage()
                    + ". The next round retries it.");
            return false;
        }
    }
}
