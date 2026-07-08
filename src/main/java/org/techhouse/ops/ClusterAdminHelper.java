package org.techhouse.ops;

import java.util.Set;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.WriteGuard;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.resp.OperationResponse;

/**
 * Bridges the admin/DDL handlers to the clustering layer: rejects admin mutations this node may not apply
 * (no write quorum), and after a successful mutation has the admin coordinator replicate it (by re-execution)
 * to a majority of nodes. No-ops when clustering is off or this node is not the coordinator, so the
 * single-node admin path is unchanged.
 */
public final class ClusterAdminHelper {
    private static final Set<OperationType> ADMIN_DDL = Set.of(OperationType.CREATE_DATABASE,
            OperationType.DROP_DATABASE, OperationType.CREATE_COLLECTION, OperationType.DROP_COLLECTION,
            OperationType.CREATE_INDEX, OperationType.DROP_INDEX, OperationType.REINDEX,
            OperationType.SET_DATABASE_OWNERS);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);

    private ClusterAdminHelper() {
    }

    // Returns an error response if this node may not apply the admin mutation (no quorum), or null to proceed.
    public static OperationResponse guard(OperationRequest request) {
        if (!ADMIN_DDL.contains(request.getType())) {
            return null;
        }
        return coordinator.guardAdmin().kind() == WriteGuard.Kind.NO_QUORUM
                ? new OperationResponse(request.getType(), ErrorCode.NO_QUORUM)
                : null;
    }

    public static OperationResponse afterAdminOp(OperationRequest request, String actingUser,
            OperationResponse response) {
        if (!ADMIN_DDL.contains(request.getType()) || response.getStatus() != OperationStatus.OK) {
            return response;
        }
        return coordinator.replicateAdminOp(request, actingUser) == ReplicationOutcome.TIMEOUT
                ? new OperationResponse(request.getType(), ErrorCode.REPLICATION_TIMEOUT)
                : response;
    }
}
