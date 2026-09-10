package org.techhouse.cluster;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLException;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.ReplicatedUserApplyHelper;
import org.techhouse.ops.ScriptRunRegistry;
import org.techhouse.ops.TriggerRunResolution;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;

public class ClusterConnectionHandler implements Runnable {
    private final EJson eJson = IocContainer.get(EJson.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final AdminAntiEntropyService adminAntiEntropyService = IocContainer.get(AdminAntiEntropyService.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final ScriptRunDirectory scriptRunDirectory = IocContainer.get(ScriptRunDirectory.class);
    private final TriggerRunDirectory triggerRunDirectory = IocContainer.get(TriggerRunDirectory.class);
    private final ScriptRunRegistry scriptRunRegistry = IocContainer.get(ScriptRunRegistry.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Logger logger = Logger.logFor(ClusterConnectionHandler.class);
    private final Socket socket;

    public ClusterConnectionHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try (socket) {
            final var reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            final var writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final var request = eJson.fromJson(line, ClusterMessage.class);
                if (request == null) {
                    continue;
                }
                final var response = handle(request);
                response.setCorrelationId(request.getCorrelationId());
                writer.write(eJson.toJson(response));
                writer.newLine();
                writer.flush();
            }
        } catch (SSLException e) {
            logger.warning("Rejected cluster connection: TLS handshake failed");
        } catch (IOException e) {
            logger.warning("Cluster connection error: " + e.getMessage());
        }
    }

    private ClusterMessage handle(ClusterMessage request) {
        if (!clusterConfig.secret().equals(request.getSecret())) {
            final var error = new ClusterMessage();
            error.setType(ClusterMessageType.ERROR);
            error.setErrorMessage("Invalid cluster secret");
            return error;
        }
        return switch (request.getType()) {
            case JOIN_REQUEST -> membershipService.handleJoin(request);
            case GOSSIP -> membershipService.handleGossip(request);
            case REPLICATE -> handleReplicate(request);
            case REPLICATE_ADMIN -> handleReplicateAdmin(request);
            case REPLICATE_USER -> handleReplicateUser(request);
            case REPLICATE_TX -> ClusterTxMessageHandler.handleReplicateTx(request);
            case FORWARD_REQUEST -> handleForward(request);
            case FORWARD_TX_REQUEST -> ClusterTxMessageHandler.handleForwardTx(request);
            case PREPARE_TX -> ClusterTxMessageHandler.handlePrepareTx(request);
            case COMMIT_TX -> ClusterTxMessageHandler.handleCommitTx(request);
            case ABORT_TX -> ClusterTxMessageHandler.handleAbortTx(request);
            case TX_STATUS -> ClusterTxMessageHandler.handleTxStatus(request);
            case LIST_TX -> ClusterTxMessageHandler.handleListTx();
            case LIST_SCRIPTS -> handleListScripts();
            case CANCEL_SCRIPT -> handleCancelScript(request);
            case LIST_TRIGGER_RUNS -> handleListTriggerRuns(request);
            case RESOLVE_TRIGGER_RUN -> handleResolveTriggerRun(request);
            case ADMIN_SNAPSHOT -> handleAdminSnapshot();
            case DIGEST -> handleDigest(request);
            case PULL -> handlePull(request);
            default -> {
                final var error = new ClusterMessage();
                error.setType(ClusterMessageType.ERROR);
                error.setErrorMessage("Unsupported cluster message type: " + request.getType());
                yield error;
            }
        };
    }

    private ClusterMessage handleForward(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.FORWARD_RESPONSE, "Failed to execute forwarded request",
                response -> response.setForwardBody(ForwardBody.encode(eJson.toJson(executeForwarded(request)))));
    }

    // Reports the script runs executing on this node for a cluster-wide LIST_SCRIPTS aggregation.
    private ClusterMessage handleListScripts() {
        return ClusterMessages.reply(ClusterMessageType.LIST_SCRIPTS_ACK, "Failed to list running scripts",
                response -> response.setRunningScripts(scriptRunDirectory.localRuns()));
    }

    // Cancels a run executing on this node. An id this node is not running is not an error: the operator
    // asked every member and only the one running it answers true.
    private ClusterMessage handleCancelScript(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.CANCEL_SCRIPT_ACK, "Failed to cancel the running script",
                response -> response.setCancelledRun(scriptRunRegistry.cancel(request.getCancelRunId())));
    }

    // Reports the trigger runs recorded on this node. admin/trigger_runs is not replicated, so a run's
    // record exists on exactly one node and only that node can answer for it.
    private ClusterMessage handleListTriggerRuns(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.LIST_TRIGGER_RUNS_ACK, "Failed to list trigger runs",
                response -> response
                        .setTriggerRuns(triggerRunDirectory.localRuns(statusFilter(request.getTriggerRunDecision()))));
    }

    // Replays or discards a run recorded here. A run this node does not hold is not an error: the operator
    // asked every member and only the one holding it answers true.
    private ClusterMessage handleResolveTriggerRun(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.RESOLVE_TRIGGER_RUN_ACK, "Failed to resolve the trigger run",
                response -> response.setTriggerRunResolved(
                        TriggerRunResolution.resolveLocal(request.getTriggerRunId(), request.getTriggerRunDecision())));
    }

    private static TriggerRunStatus statusFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TriggerRunStatus.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    // Reports this node's authoritative admin snapshot (epoch + databases/collections/users) for a rejoining
    // or lagging peer to conform to.
    private ClusterMessage handleAdminSnapshot() {
        return ClusterMessages.reply(ClusterMessageType.ADMIN_SNAPSHOT_ACK, "Failed to build admin snapshot",
                response -> response.setAdminSnapshot(adminAntiEntropyService.buildSnapshot()));
    }

    private ClusterMessage handleReplicateAdmin(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.REPLICATE_ADMIN_ACK, "Failed to apply replicated admin op",
                response -> {
                    final var result = executeForwarded(request);
                    if (result.getStatus() == OperationStatus.OK) {
                        adminEpoch.adopt(request.getAdminEpoch());
                    } else {
                        response.setType(ClusterMessageType.ERROR);
                        response.setErrorMessage("Replicated admin op failed: " + result.getMessage());
                    }
                });
    }

    // Re-parses and executes a forwarded/replicated request directly through OperationProcessor (bypassing
    // the router, so there is no forward loop). The edge already authenticated/authorized the client; a
    // short-lived synthetic client carries the acting user so admin ops apply with the correct identity.
    private OperationResponse executeForwarded(ClusterMessage request) {
        final var actingUser = request.getActingUser();
        final var clientId = actingUser != null ? clientTracker.registerForwardedClient(actingUser) : null;
        try {
            final var parsed = RequestParser.parseRequest(ForwardBody.decode(request.getForwardBody()));
            return operationProcessor.processMessage(parsed, clientId);
        } finally {
            if (clientId != null) {
                clientTracker.removeById(clientId);
            }
        }
    }

    private ClusterMessage handleReplicateUser(ClusterMessage request) {
        final var response = new ClusterMessage();
        if (ReplicatedUserApplyHelper.apply(request.getReplication())) {
            adminEpoch.adopt(request.getAdminEpoch());
            response.setType(ClusterMessageType.REPLICATE_USER_ACK);
        } else {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to apply replicated user mutation");
        }
        return response;
    }

    private ClusterMessage handleReplicate(ClusterMessage request) {
        final var response = new ClusterMessage();
        if (ReplicatedApplyHelper.apply(request.getReplication())) {
            response.setType(ClusterMessageType.REPLICATE_ACK);
        } else {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to apply replicated write");
        }
        return response;
    }

    private ClusterMessage handleDigest(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.DIGEST_ACK, "Failed to build digest", response -> {
            final var query = request.getAntiEntropy();
            response.setAntiEntropy(antiEntropyService.buildDigest(query.getDbName(), query.getCollName()));
        });
    }

    private ClusterMessage handlePull(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.PULL_ACK, "Failed to build pull response", response -> {
            final var query = request.getAntiEntropy();
            response.setAntiEntropy(
                    antiEntropyService.buildPull(query.getDbName(), query.getCollName(), query.getIds()));
        });
    }
}
