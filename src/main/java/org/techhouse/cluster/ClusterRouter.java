package org.techhouse.cluster;

import java.util.Set;
import java.util.UUID;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ClusterAdminHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
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
    private static final Set<OperationType> WRITES = Set.of(OperationType.SAVE, OperationType.BULK_SAVE,
            OperationType.DELETE);
    // Operations a bound-remote transaction forwards to its owner; anything else (DDL/admin/listen) is left to
    // execute locally, where the OperationProcessor guard rejects it as not-allowed-in-transaction.
    private static final Set<OperationType> FORWARDABLE_TX_OPS = Set.of(OperationType.SAVE, OperationType.BULK_SAVE,
            OperationType.DELETE, OperationType.FIND_BY_ID, OperationType.AGGREGATE, OperationType.COMMIT_TRANSACTION,
            OperationType.ROLLBACK_TRANSACTION);
    private final Logger logger = Logger.logFor(ClusterRouter.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    /**
     * @return the response JSON to relay to the client when the request was forwarded to its owner, or
     *         {@code null} when the request should be executed locally (this node owns it, clustering is
     *         off, the operation is not per-collection, or ownership is not yet established).
     */
    public String forward(OperationRequest request, String rawJson, boolean transactionActive, String actingUser,
            UUID clientId) {
        if (!clusterConfig.isEnabled()) {
            return null;
        }
        final var type = request.getType();
        if (transactionActive) {
            return routeActiveTransaction(request, rawJson, type, actingUser, clientId);
        }
        if (ClusterAdminHelper.isCoordinatedAdminOp(type)) {
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

    // Routes an operation issued while a transaction is open. The transaction is pinned to one owner: bound on
    // the first write, forwarded there (session-scoped) thereafter. START and reads-before-the-first-write run
    // locally; a bound-local transaction runs locally; disallowed ops fall through to a local rejection.
    private String routeActiveTransaction(OperationRequest request, String rawJson, OperationType type,
            String actingUser, UUID clientId) {
        if (type == OperationType.START_TRANSACTION) {
            return null;
        }
        if (clientTracker.isTransactionBound(clientId)) {
            final var owner = clientTracker.getTransactionOwner(clientId);
            if (owner == null || !FORWARDABLE_TX_OPS.contains(type)) {
                return null;
            }
            final var response = forwardTx(rawJson, owner, type, actingUser, clientId);
            if (type == OperationType.COMMIT_TRANSACTION || type == OperationType.ROLLBACK_TRANSACTION) {
                clearEdgeTransaction(clientId);
            }
            return response;
        }
        if (!WRITES.contains(type)) {
            return null;
        }
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (Globals.ADMIN_DB_NAME.equals(dbName) || ownershipManager.isOwner(dbName, collName)) {
            clientTracker.bindLocalTransaction(clientId);
            return null;
        }
        final var ownerAddress = ownershipManager.ownerAddress(dbName, collName);
        if (ownerAddress == null) {
            clientTracker.bindLocalTransaction(clientId);
            return null;
        }
        clientTracker.bindRemoteTransaction(clientId, ownerAddress);
        return forwardTx(rawJson, ownerAddress, type, actingUser, clientId);
    }

    // Tells the bound remote owner to roll back a transaction whose edge connection is closing, so the owner
    // releases the write locks it holds. A no-op for a local or unbound transaction.
    public void teardownTransaction(UUID clientId) {
        if (!clusterConfig.isEnabled() || !clientTracker.isTransactionBound(clientId)) {
            return;
        }
        final var owner = clientTracker.getTransactionOwner(clientId);
        if (owner == null) {
            return;
        }
        forwardTx(eJson.toJson(new RollbackTransactionRequest()), owner, OperationType.ROLLBACK_TRANSACTION, null,
                clientId);
    }

    private void clearEdgeTransaction(UUID clientId) {
        clientTracker.clearActiveTransaction(clientId);
        clientTracker.clearTransactionBinding(clientId);
    }

    private String forwardTx(String rawJson, String ownerAddress, OperationType type, String actingUser,
            UUID clientId) {
        final var message = new ClusterMessage(null, ClusterMessageType.FORWARD_TX_REQUEST, clusterConfig.secret(),
                membershipService.getSelf(), null);
        message.setForwardBody(ForwardBody.encode(rawJson));
        message.setActingUser(actingUser);
        message.setTxSessionId(clientId.toString());
        try {
            final var response = pool.request(NodeAddress.parse(ownerAddress), message,
                    clusterConfig.replicationAckTimeoutMs());
            if (response.getType() == ClusterMessageType.FORWARD_RESPONSE) {
                return ForwardBody.decode(response.getForwardBody());
            }
            logger.warning(
                    "Owner " + ownerAddress + " rejected a forwarded transaction op: " + response.getErrorMessage());
        } catch (Exception e) {
            logger.warning("Failed to forward transaction op to owner " + ownerAddress + ": " + e.getMessage());
        }
        return eJson.toJson(new OperationResponse(type, ErrorCode.OWNER_UNREACHABLE));
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
