package org.techhouse.ops;

import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.OperationResponse;

public final class CollectionReadinessGuard {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);

    private CollectionReadinessGuard() {
    }

    public static OperationResponse check(OperationType type, String dbName, String collName) {
        if (cache.getAdminCollectionEntry(dbName, collName) != null) {
            return null;
        }
        return clusterConfig.isEnabled()
                ? new OperationResponse(type, ErrorCode.COLLECTION_NOT_READY)
                : new OperationResponse(type, ErrorCode.COLLECTION_NOT_FOUND);
    }

    public static OperationResponse checkRead(OperationType type, String dbName, List<String> collNames) {
        if (isAdminDatabase(dbName)) {
            return null;
        }
        for (final var collName : collNames) {
            if (!Globals.SCRIPT_RUNS_COLLECTION_NAME.equals(collName)) {
                final var refusal = check(type, dbName, collName);
                if (refusal != null) {
                    return refusal;
                }
            }
        }
        return null;
    }

    private static boolean isAdminDatabase(String dbName) {
        return Globals.ADMIN_DB_NAME.equals(dbName) || Globals.ADMIN_PAGES_DB_NAME.equals(dbName);
    }
}
