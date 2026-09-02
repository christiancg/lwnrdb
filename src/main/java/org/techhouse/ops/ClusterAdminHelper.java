package org.techhouse.ops;

import java.util.Set;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.WriteGuard;
import org.techhouse.cluster.ownership.OwnershipManager;
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
            OperationType.SET_DATABASE_OWNERS, OperationType.SAVE_SCHEMA, OperationType.DELETE_SCHEMA,
            OperationType.SAVE_PROCEDURE, OperationType.DELETE_PROCEDURE, OperationType.SAVE_TRIGGER,
            OperationType.DELETE_TRIGGER, OperationType.SAVE_SCHEDULE, OperationType.DELETE_SCHEDULE);
    private static final Set<OperationType> USER_OPS = Set.of(OperationType.CREATE_USER, OperationType.DELETE_USER,
            OperationType.SET_PASSWORD, OperationType.CHANGE_PERMISSIONS);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private static final AdminAntiEntropyService adminAntiEntropyService = IocContainer
            .get(AdminAntiEntropyService.class);

    private ClusterAdminHelper() {
    }

    // Admin/user mutations that are serialized and replicated by the admin coordinator.
    public static boolean isCoordinatedAdminOp(OperationType type) {
        return ADMIN_DDL.contains(type) || USER_OPS.contains(type);
    }

    // Returns an error response if this node may not apply the admin mutation, or null to proceed. Rejects on
    // no quorum, and — while a coordinator is still catching up on rejoin — with a retryable ADMIN_SYNCING so
    // it never commits an admin op on a stale base before its first reconciliation.
    public static OperationResponse guard(OperationRequest request) {
        if (!isCoordinatedAdminOp(request.getType())) {
            return null;
        }
        if (coordinator.guardAdmin().kind() == WriteGuard.Kind.NO_QUORUM) {
            return new OperationResponse(request.getType(), ErrorCode.NO_QUORUM);
        }
        if (clusterConfig.isEnabled() && ownershipManager.isAdminCoordinator()
                && !adminAntiEntropyService.hasCompletedAdminSync()) {
            return new OperationResponse(request.getType(), ErrorCode.ADMIN_SYNCING);
        }
        return null;
    }

    public static OperationResponse afterAdminOp(OperationRequest request, String actingUser,
            OperationResponse response) {
        final var type = request.getType();
        if (!isCoordinatedAdminOp(type) || response.getStatus() != OperationStatus.OK) {
            return response;
        }
        // Bump the epoch before replicating so the new value ships on the REPLICATE_ADMIN/REPLICATE_USER
        // message; only the coordinator advances the single cluster-wide admin lineage.
        if (clusterConfig.isEnabled() && ownershipManager.isAdminCoordinator()) {
            adminEpoch.bump();
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
