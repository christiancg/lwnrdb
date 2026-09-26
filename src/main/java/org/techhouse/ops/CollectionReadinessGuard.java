package org.techhouse.ops;

import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
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
}
