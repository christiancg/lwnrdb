package org.techhouse.ops;

import java.util.Set;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.WriteGuard;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.resp.OperationResponse;

/**
 * Bridges the admin handlers to the clustering layer: rejects admin mutations this node may not apply (no
 * write quorum), and after a successful mutation has the admin coordinator replicate it to a majority of
 * nodes. Structural DDL is replicated by re-execution; user/permission ops ship the committed admin/users
 * record (so the salted password hash is identical everywhere). No-ops when clustering is off or this node is
 * not the coordinator, so the single-node admin path is unchanged.
 */
public final class ClusterAdminHelper {
    private static final Set<OperationType> ADMIN_DDL = Set.of(OperationType.CREATE_DATABASE,
            OperationType.DROP_DATABASE, OperationType.CREATE_COLLECTION, OperationType.DROP_COLLECTION,
            OperationType.CREATE_INDEX, OperationType.DROP_INDEX, OperationType.REINDEX,
            OperationType.SET_DATABASE_OWNERS);
    private static final Set<OperationType> USER_OPS = Set.of(OperationType.CREATE_USER, OperationType.DELETE_USER,
            OperationType.SET_PASSWORD, OperationType.CHANGE_PERMISSIONS);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);

    private ClusterAdminHelper() {
    }

    // Admin/user mutations that are serialized and replicated by the admin coordinator.
    public static boolean isCoordinatedAdminOp(OperationType type) {
        return ADMIN_DDL.contains(type) || USER_OPS.contains(type);
    }

    // Returns an error response if this node may not apply the admin mutation (no quorum), or null to proceed.
    public static OperationResponse guard(OperationRequest request) {
        if (!isCoordinatedAdminOp(request.getType())) {
            return null;
        }
        return coordinator.guardAdmin().kind() == WriteGuard.Kind.NO_QUORUM
                ? new OperationResponse(request.getType(), ErrorCode.NO_QUORUM)
                : null;
    }

    public static OperationResponse afterAdminOp(OperationRequest request, String actingUser,
            OperationResponse response) {
        final var type = request.getType();
        if (!isCoordinatedAdminOp(type) || response.getStatus() != OperationStatus.OK) {
            return response;
        }
        final var outcome = USER_OPS.contains(type)
                ? coordinator.replicateUserOp(usernameOf(request), type == OperationType.DELETE_USER)
                : coordinator.replicateAdminOp(request, actingUser);
        return outcome == ReplicationOutcome.TIMEOUT
                ? new OperationResponse(type, ErrorCode.REPLICATION_TIMEOUT)
                : response;
    }

    private static String usernameOf(OperationRequest request) {
        return switch (request.getType()) {
            case CREATE_USER -> ((CreateUserRequest) request).getUsername();
            case DELETE_USER -> ((DeleteUserRequest) request).getUsername();
            case SET_PASSWORD -> ((SetPasswordRequest) request).getUsername();
            case CHANGE_PERMISSIONS -> ((ChangePermissionsRequest) request).getUsername();
            default -> null;
        };
    }
}
