package org.techhouse.ops;

import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;

public final class CollectionAccessHelper {
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private static final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);

    private CollectionAccessHelper() {
    }

    public static void recordCollectionAccess(String dbName, String collName) {
        recordAccess(AccessKind.COLLECTION, dbName, collName);
    }

    public static void recordPkIndexAccess(String dbName, String collName) {
        recordAccess(AccessKind.PK_INDEX, dbName, collName);
    }

    private static void recordAccess(AccessKind kind, String dbName, String collName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        memoryManagement.recordAccess(kind, dbName, collName, null);
        taskManager.submitBackgroundTask(
                new CollectionUsageEvent(kind, dbName, collName, null, System.currentTimeMillis()));
    }
}
