package org.techhouse.cluster;

import java.util.Set;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.resp.OperationResponse;

/**
 * Edge-side request routing: forwards a per-collection operation that this node does not own to the
 * collection's owner (the cache home / write coordinator) and relays the owner's response JSON verbatim.
 * Reads may fall back to the local (full) replica when the owner is unreachable.
 */
public class ClusterRouter {
    private static final Set<OperationType> ROUTABLE = Set.of(OperationType.SAVE, OperationType.BULK_SAVE,
            OperationType.DELETE, OperationType.FIND_BY_ID, OperationType.AGGREGATE);
    private static final Set<OperationType> READS = Set.of(OperationType.FIND_BY_ID, OperationType.AGGREGATE);
    private static final Set<OperationType> ADMIN_DDL = Set.of(OperationType.CREATE_DATABASE,
            OperationType.DROP_DATABASE, OperationType.CREATE_COLLECTION, OperationType.DROP_COLLECTION,
            OperationType.CREATE_INDEX, OperationType.DROP_INDEX, OperationType.REINDEX,
            OperationType.SET_DATABASE_OWNERS);
    private final Logger logger = Logger.logFor(ClusterRouter.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    /**
     * @return the response JSON to relay to the client when the request was forwarded to its owner, or
     *         {@code null} when the request should be executed locally (this node owns it, clustering is
     *         off, a transaction is open, the operation is not per-collection, or ownership is not yet
     *         established).
     */
    public String forward(OperationRequest request, String rawJson, boolean transactionActive, String actingUser) {
        if (!clusterConfig.isEnabled() || transactionActive) {
            return null;
        }
        final var type = request.getType();
        if (ADMIN_DDL.contains(type)) {
            return forwardAdmin(type, rawJson, actingUser);
        }
        if (!ROUTABLE.contains(type)) {
            return null;
        }
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (Globals.ADMIN_DB_NAME.equals(dbName) || ownershipManager.isOwner(dbName, collName)) {
            return null;
        }
        final var ownerAddress = ownershipManager.ownerAddress(dbName, collName);
        if (ownerAddress == null) {
            return null;
        }
        return forwardToOwner(type, rawJson, ownerAddress, null);
    }

    // Admin/DDL ops are coordinated by the admin coordinator; forward them there unless we are it. The acting
    // username travels along so the coordinator applies the op as the original user (e.g. DB owner).
    private String forwardAdmin(OperationType type, String rawJson, String actingUser) {
        if (ownershipManager.isAdminCoordinator()) {
            return null;
        }
        final var coordinatorAddress = ownershipManager.adminCoordinatorAddress();
        if (coordinatorAddress == null) {
            return null;
        }
        return forwardToOwner(type, rawJson, coordinatorAddress, actingUser);
    }

    private String forwardToOwner(OperationType type, String rawJson, String ownerAddress, String actingUser) {
        final var message = new ClusterMessage(null, ClusterMessageType.FORWARD_REQUEST, clusterConfig.secret(),
                membershipService.getSelf(), null);
        message.setForwardBody(ForwardBody.encode(rawJson));
        message.setActingUser(actingUser);
        try {
            final var response = pool.request(NodeAddress.parse(ownerAddress), message,
                    clusterConfig.replicationAckTimeoutMs());
            if (response.getType() == ClusterMessageType.FORWARD_RESPONSE) {
                return ForwardBody.decode(response.getForwardBody());
            }
            logger.warning("Owner " + ownerAddress + " rejected a forwarded request: " + response.getErrorMessage());
            return eJson.toJson(new OperationResponse(type, ErrorCode.OWNER_UNREACHABLE));
        } catch (Exception e) {
            if (READS.contains(type) && clusterConfig.readFallbackToLocal()) {
                return null;
            }
            logger.warning("Failed to forward request to owner " + ownerAddress + ": " + e.getMessage());
            return eJson.toJson(new OperationResponse(type, ErrorCode.OWNER_UNREACHABLE));
        }
    }
}
