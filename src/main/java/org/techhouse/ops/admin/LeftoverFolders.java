package org.techhouse.ops.admin;

import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.CompiledProcedureCache;

public final class LeftoverFolders {
    private static final Logger logger = Logger.logFor(LeftoverFolders.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);

    private LeftoverFolders() {
    }

    public static boolean moveAsideUnregisteredCollection(String dbName, String collName) {
        if (cache.getAdminCollectionEntry(dbName, collName) != null
                || !fs.folderQuarantine().holdsLeftoverCollection(dbName, collName)) {
            return true;
        }
        cache.evictCollection(dbName, collName);
        final var moved = fs.folderQuarantine().moveCollectionAside(dbName, collName, 0);
        logOutcome("collection " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName, moved);
        return moved;
    }

    public static boolean moveAsideUnregisteredDatabase(String dbName) {
        if (cache.getAdminDbEntry(dbName) != null || !fs.folderQuarantine().holdsLeftoverDatabase(dbName)) {
            return true;
        }
        cache.evictDatabase(dbName);
        compiledProcedures.invalidateDatabase(dbName);
        scheduleRegistry.removeDatabase(dbName);
        final var moved = fs.folderQuarantine().moveDatabaseAside(dbName);
        logOutcome("database " + dbName, moved);
        return moved;
    }

    private static void logOutcome(String what, boolean moved) {
        if (moved) {
            logger.warning("Moved aside the leftover folder of " + what
                    + ": it holds data but is not registered, so it belongs to a dropped incarnation and is never"
                    + " adopted by a new one.");
        } else {
            logger.error("Could not move aside the leftover folder of " + what
                    + ": it holds data but is not registered, so it is not adopted and the create is refused.");
        }
    }
}
