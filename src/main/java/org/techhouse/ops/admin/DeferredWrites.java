package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.List;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.DeferredApplyHook;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.DeferredApplies;
import org.techhouse.fs.DeferredApplyListener;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class DeferredWrites implements DeferredApplyHook, DeferredApplyListener {
    private static final Logger logger = Logger.logFor(DeferredWrites.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    DeferredWrites() {
    }

    public static void install() {
        final var installed = new DeferredWrites();
        fs.onDeferredApply(installed);
        ResourceLocking.useDeferredApplyHook(installed);
    }

    @Override
    public boolean pending(String lockName) {
        return DeferredApplies.pending(lockName);
    }

    @Override
    public void apply(String lockName) {
        final var separator = lockName.indexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
        if (separator <= 0 || lockName.indexOf(Globals.COLL_IDENTIFIER_SEPARATOR, separator + 1) >= 0) {
            return;
        }
        final var dbName = lockName.substring(0, separator);
        final var collName = lockName.substring(separator + 1);
        final boolean applied;
        try {
            applied = fs.applyDeferredWrites(dbName, collName);
        } catch (IOException e) {
            logger.warning("Could not apply the logged writes of " + lockName + " before using the collection; the"
                    + " next use retries: " + e.getMessage());
            return;
        }
        if (applied && !Globals.ADMIN_PAGES_DB_NAME.equals(dbName)) {
            reconcilePageRows(dbName, collName);
        }
    }

    private static void reconcilePageRows(String dbName, String collName) {
        try {
            PageOccupancyReconciler.reconcileRows(dbName, collName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warning("Applied the logged writes of " + Cache.getCollectionIdentifier(dbName, collName)
                    + " but could not reconcile its page rows; the next startup rebuilds them: " + e.getMessage());
        }
    }

    @Override
    public long replaying(String dbName, String collName, List<PkIndexEntry> rows) {
        return isUserDatabase(dbName) ? pendingIndexWrites.mark(dbName, collName, idsOf(rows)) : 0L;
    }

    @Override
    public void replayed(String dbName, String collName, List<PkIndexEntry> rows, long generation) {
        if (!isUserDatabase(dbName)) {
            AdminReplayedRows.publish(dbName, collName, rows);
            return;
        }
        repairAfterRedo(dbName, collName, rows, generation);
    }

    @Override
    public void notReplayed(String dbName, String collName, List<PkIndexEntry> rows, long generation) {
        if (isUserDatabase(dbName)) {
            repairAfterRedo(dbName, collName, rows, generation);
        }
    }

    private static void repairAfterRedo(String dbName, String collName, List<PkIndexEntry> rows, long generation) {
        cache.evictCollection(dbName, collName);
        PageOccupancyReconciler.scheduleIndexRepairFor(
                List.of(new PageOccupancyReconciler.ReplayedCollection(dbName, collName, rows, generation)));
    }

    private static boolean isUserDatabase(String dbName) {
        return !Globals.ADMIN_DB_NAME.equals(dbName) && !Globals.ADMIN_PAGES_DB_NAME.equals(dbName);
    }

    private static List<String> idsOf(List<PkIndexEntry> rows) {
        return rows.stream().map(PkIndexEntry::getValue).toList();
    }
}
