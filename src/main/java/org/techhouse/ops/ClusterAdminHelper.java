package org.techhouse.ops;

import java.util.Set;
import java.util.function.Supplier;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.AdminLane;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.WriteGuard;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.resp.OperationResponse;

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
    private static final AdminLane adminLane = IocContainer.get(AdminLane.class);
    private static final MembershipService membershipService = IocContainer.get(MembershipService.class);

    private ClusterAdminHelper() {
    }

    public static OperationResponse inAdminLane(OperationRequest request, Supplier<OperationResponse> op) {
        if (!needsAdminLane(request)) {
            return op.get();
        }
        return adminLane.within(clusterConfig.adminLaneTimeoutMs(), op,
                () -> new OperationResponse(request.getType(), ErrorCode.ADMIN_LANE_BUSY));
    }

    public static boolean holdsAdminLane() {
        return adminLane.isHeldByCurrentThread();
    }

    private static boolean needsAdminLane(OperationRequest request) {
        return isCoordinatedAdminOp(request.getType()) && !request.isReplicated() && clusterConfig.isEnabled()
                && ownershipManager.isAdminCoordinator();
    }

    public static boolean isCoordinatedAdminOp(OperationType type) {
        return ADMIN_DDL.contains(type) || USER_OPS.contains(type);
    }

    // A coordinator still catching up on rejoin rejects with a retryable ADMIN_SYNCING, never committing on a stale base.
    public static OperationResponse guard(OperationRequest request) {
        if (!isCoordinatedAdminOp(request.getType())) {
            return null;
        }
        if (clusterConfig.isEnabled() && !request.isReplicated() && !holdsAdminLane()) {
            return new OperationResponse(request.getType(), ErrorCode.NOT_COLLECTION_OWNER);
        }
        if (coordinator.guardAdmin().kind() == WriteGuard.Kind.NO_QUORUM) {
            return new OperationResponse(request.getType(), ErrorCode.NO_QUORUM);
        }
        if (clusterConfig.isEnabled() && ownershipManager.isAdminCoordinator()
                && !adminAntiEntropyService.hasCompletedAdminSync()) {
            return new OperationResponse(request.getType(), ErrorCode.ADMIN_SYNCING);
        }
        if (clusterConfig.isEnabled() && !request.isReplicated() && behindAPeer()) {
            adminAntiEntropyService.reconcileSoon();
            return new OperationResponse(request.getType(), ErrorCode.ADMIN_SYNCING);
        }
        return null;
    }

    private static boolean behindAPeer() {
        final var local = adminEpoch.state();
        return membershipService.membershipView().peers(membershipService.getSelf()).stream()
                .anyMatch(peer -> outranksLocal(peer, local));
    }

    private static boolean outranksLocal(NodeInfo peer, AdminEpoch.State local) {
        if (peer.getAdminEpoch() != local.epoch()) {
            return peer.getAdminEpoch() > local.epoch();
        }
        return !local.confirmed() && !peer.isAdminEpochUnconfirmed();
    }

    public static OperationResponse afterAdminOp(OperationRequest request, String actingUser,
            OperationResponse response) {
        final var type = request.getType();
        if (!isCoordinatedAdminOp(type) || request.isReplicated() || response.getStatus() != OperationStatus.OK) {
            return response;
        }
        final var admitted = holdsAdminLane();
        // Bump the epoch before replicating so the new value ships on the replication message.
        if (admitted) {
            adminEpoch.bump();
        }
        final var outcome = USER_OPS.contains(type)
                ? coordinator.replicateUserOp(usernameOf(request), type == OperationType.DELETE_USER)
                : coordinator.replicateAdminOp(request, actingUser);
        recordReplicationReach(admitted, outcome);
        return switch (outcome) {
            case TIMEOUT -> new OperationResponse(type, ErrorCode.REPLICATION_TIMEOUT);
            case NOT_COORDINATOR, NOT_OWNER ->
                new OperationResponse(type, admitted ? ErrorCode.REPLICATION_TIMEOUT : ErrorCode.NOT_COLLECTION_OWNER);
            case NOT_CLUSTERED, QUORUM_MET -> response;
        };
    }

    private static void recordReplicationReach(boolean admitted, ReplicationOutcome outcome) {
        if (!admitted) {
            return;
        }
        final var reachedQuorum = switch (outcome) {
            case QUORUM_MET, NOT_CLUSTERED -> true;
            case TIMEOUT, NOT_COORDINATOR, NOT_OWNER -> false;
        };
        if (reachedQuorum) {
            adminEpoch.confirm();
        } else {
            adminEpoch.markUnconfirmed();
        }
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
