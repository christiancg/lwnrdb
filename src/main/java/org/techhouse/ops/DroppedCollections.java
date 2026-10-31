package org.techhouse.ops;

import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.ioc.IocContainer;

public final class DroppedCollections {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);

    private DroppedCollections() {
    }

    public static void forgetCollection(String dbName, String collName) {
        cache.evictCollection(dbName, collName);
        pendingIndexWrites.clearCollection(dbName, collName);
        GroupCommit.forget(dbName, collName);
        memoryManagement.forgetUsage(dbName, collName);
        SchemaValidationHelper.forgetCollection(dbName, collName);
    }

    public static void forgetDatabase(String dbName) {
        cache.evictDatabase(dbName);
        pendingIndexWrites.clearDatabase(dbName);
        GroupCommit.forgetDatabase(dbName);
        memoryManagement.forgetDatabaseUsage(dbName);
        SchemaValidationHelper.forgetDatabase(dbName);
    }
}
