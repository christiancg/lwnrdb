package org.techhouse.cluster;

import java.util.Set;
import java.util.UUID;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
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

public class ClusterRouter {
    private static final Set<OperationType> ROUTABLE = Set.of(OperationType.SAVE, OperationType.BULK_SAVE,
            OperationType.DELETE, OperationType.FIND_BY_ID, OperationType.AGGREGATE);
    private static final Set<OperationType> READS = Set.of(OperationType.FIND_BY_ID, OperationType.AGGREGATE);
    private static final Set<OperationType> WRITES = Set.of(OperationType.SAVE, OperationType.BULK_SAVE,
            OperationType.DELETE);
    private static final Set<OperationType> SCRIPT_OPS = Set.of(OperationType.RUN_SCRIPT, OperationType.CALL_PROCEDURE);
    private final Logger logger = Logger.logFor(ClusterRouter.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Tx2pcCoordinator tx2pcCoordinator = IocContainer.get(Tx2pcCoordinator.class);
    private final ScriptPlacement scriptPlacement = IocContainer.get(ScriptPlacement.class);
    private final Configuration configuration = Configuration.getInstance();
    private final EJson eJson = IocContainer.get(EJson.class);

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
        if (SCRIPT_OPS.contains(type)) {
            return forwardScript(type, request.getDatabaseName(), rawJson, actingUser);
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

    public boolean teardownTransaction(UUID clientId) {
        if (!clusterConfig.isEnabled() || clientTracker.transactionParticipants(clientId).isEmpty()) {
            return false;
        }
        tx2pcCoordinator.rollback(clientId);
        return true;
    }

    private String forwardTx(String rawJson, String ownerAddress, OperationType type, String actingUser,
            UUID clientId) {
        final var message = PeerRequest.message(ClusterMessageType.FORWARD_TX_REQUEST);
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

    // Falling back to local execution is only correct when the target provably never got the request: any
    // other failure may leave it still running, and a script writes, so a second run is silent corruption.
    private String forwardScript(OperationType type, String databaseName, String rawJson, String actingUser) {
        final var target = scriptPlacement.choose(databaseName);
        if (target == null) {
            return null;
        }
        final var message = PeerRequest.message(ClusterMessageType.FORWARD_REQUEST);
        message.setForwardBody(ForwardBody.encode(rawJson));
        message.setActingUser(actingUser);
        // The whole script budget, not replicationAckTimeoutMs: that is sized for a single write ack and
        // would report the target unreachable while it is still running the script.
        final var timeout = configuration.getScriptTimeoutMs() + clusterConfig.replicationAckTimeoutMs();
        try {
            final var response = pool.request(target.address(), message, timeout);
            if (response.getType() == ClusterMessageType.FORWARD_RESPONSE) {
                scriptPlacement.recordForward();
                return ForwardBody.decode(response.getForwardBody());
            }
            return outcomeUnknown(type, target, "answered " + response.getErrorMessage());
        } catch (PeerUnreachableException e) {
            logger.warning("Could not reach " + target.address() + " to place a script, running it locally: "
                    + e.getMessage());
            scriptPlacement.recordFallback();
            return null;
        } catch (Exception e) {
            return outcomeUnknown(type, target, "did not answer: " + e.getMessage());
        }
    }

    private String outcomeUnknown(OperationType type, NodeInfo target, String detail) {
        logger.warning("Script placed on " + target.address() + " " + detail
                + "; not running it here, because it may already have run there");
        scriptPlacement.recordOutcomeUnknown();
        return eJson.toJson(new OperationResponse(type, ErrorCode.SCRIPT_OUTCOME_UNKNOWN));
    }

    private String forwardToOwner(OperationType type, String rawJson, String ownerAddress, String actingUser) {
        final var message = PeerRequest.message(ClusterMessageType.FORWARD_REQUEST);
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
