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
    private final Logger logger = Logger.logFor(ClusterRouter.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Tx2pcCoordinator tx2pcCoordinator = IocContainer.get(Tx2pcCoordinator.class);
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

    // Routes an operation issued while a transaction is open. Each write is routed to its collection's owner
    // (buffered locally when this node owns it, else forwarded to the owner, which becomes a 2PC participant);
    // a read is forwarded only to an owner already holding a slice (read-your-writes), else served locally.
    // START runs locally; COMMIT/ROLLBACK are driven by the coordinator (2PC unless a single owner is
    // involved, which keeps the 5a fast path); any other op falls through to a local rejection.
    private String routeActiveTransaction(OperationRequest request, String rawJson, OperationType type,
            String actingUser, UUID clientId) {
        if (type == OperationType.COMMIT_TRANSACTION) {
            return routeCommit(rawJson, actingUser, clientId);
        }
        if (type == OperationType.ROLLBACK_TRANSACTION) {
            return routeRollback(clientId);
        }
        if (WRITES.contains(type)) {
            return routeTransactionWrite(request, rawJson, type, actingUser, clientId);
        }
        if (READS.contains(type)) {
            return routeTransactionRead(request, rawJson, type, actingUser, clientId);
        }
        return null;
    }

    private String routeTransactionWrite(OperationRequest request, String rawJson, OperationType type,
            String actingUser, UUID clientId) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (Globals.ADMIN_DB_NAME.equals(dbName) || ownershipManager.isOwner(dbName, collName)) {
            clientTracker.markLocalSlice(clientId);
            return null;
        }
        final var ownerAddress = ownershipManager.ownerAddress(dbName, collName);
        if (ownerAddress == null) {
            clientTracker.markLocalSlice(clientId);
            return null;
        }
        clientTracker.addTransactionParticipant(clientId, ownerAddress);
        return forwardTx(rawJson, ownerAddress, type, actingUser, clientId);
    }

    private String routeTransactionRead(OperationRequest request, String rawJson, OperationType type, String actingUser,
            UUID clientId) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (Globals.ADMIN_DB_NAME.equals(dbName) || ownershipManager.isOwner(dbName, collName)) {
            return null;
        }
        final var ownerAddress = ownershipManager.ownerAddress(dbName, collName);
        if (ownerAddress != null && clientTracker.transactionParticipants(clientId).contains(ownerAddress)) {
            return forwardTx(rawJson, ownerAddress, type, actingUser, clientId);
        }
        return null;
    }

    private String routeCommit(String rawJson, String actingUser, UUID clientId) {
        final var remotes = clientTracker.transactionParticipants(clientId);
        final var local = clientTracker.hasLocalSlice(clientId);
        if (remotes.isEmpty()) {
            return null;
        }
        if (!local && remotes.size() == 1) {
            // Single remote owner: keep the 5a fast path (forward a plain COMMIT_TRANSACTION, no 2PC).
            final var response = forwardTx(rawJson, remotes.iterator().next(), OperationType.COMMIT_TRANSACTION,
                    actingUser, clientId);
            clientTracker.clearActiveTransaction(clientId);
            clientTracker.clearTransactionState(clientId);
            return response;
        }
        return eJson.toJson(tx2pcCoordinator.commit(clientId));
    }

    private String routeRollback(UUID clientId) {
        if (clientTracker.transactionParticipants(clientId).isEmpty()) {
            return null;
        }
        return eJson.toJson(tx2pcCoordinator.rollback(clientId));
    }

    // Rolls back a transaction whose edge connection is closing across its remote participants (and local
    // slice). Returns true when it handled the teardown (participants existed), so the caller skips the
    // purely-local cleanup path.
    public boolean teardownTransaction(UUID clientId) {
        if (!clusterConfig.isEnabled() || clientTracker.transactionParticipants(clientId).isEmpty()) {
            return false;
        }
        tx2pcCoordinator.rollback(clientId);
        return true;
    }

    private String forwardTx(String rawJson, String ownerAddress, OperationType type, String actingUser,
            UUID clientId) {
        final var message = new ClusterMessage(null, ClusterMessageType.FORWARD_TX_REQUEST, clusterConfig.secret(),
                membershipService.getSelf(), null);
        message.setForwardBody(ForwardBody.encode(rawJson));
        message.setActingUser(actingUser);
        message.setTxSessionId(clientId.toString());
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction != null) {
            message.setTxId(transaction.getTransactionId().toString());
        }
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
