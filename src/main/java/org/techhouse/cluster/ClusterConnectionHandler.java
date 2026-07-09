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
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.ReplicatedUserApplyHelper;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;

public class ClusterConnectionHandler implements Runnable {
    private final EJson eJson = IocContainer.get(EJson.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
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
            case FORWARD_REQUEST -> handleForward(request);
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
        final var response = new ClusterMessage();
        try {
            response.setType(ClusterMessageType.FORWARD_RESPONSE);
            response.setForwardBody(ForwardBody.encode(eJson.toJson(executeForwarded(request))));
        } catch (Exception e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to execute forwarded request: " + e.getMessage());
        }
        return response;
    }

    private ClusterMessage handleReplicateAdmin(ClusterMessage request) {
        final var response = new ClusterMessage();
        try {
            final var result = executeForwarded(request);
            if (result.getStatus() == OperationStatus.OK) {
                response.setType(ClusterMessageType.REPLICATE_ADMIN_ACK);
            } else {
                response.setType(ClusterMessageType.ERROR);
                response.setErrorMessage("Replicated admin op failed: " + result.getMessage());
            }
        } catch (Exception e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to apply replicated admin op: " + e.getMessage());
        }
        return response;
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
        final var response = new ClusterMessage();
        try {
            final var query = request.getAntiEntropy();
            response.setType(ClusterMessageType.DIGEST_ACK);
            response.setAntiEntropy(antiEntropyService.buildDigest(query.getDbName(), query.getCollName()));
        } catch (Exception e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to build digest: " + e.getMessage());
        }
        return response;
    }

    private ClusterMessage handlePull(ClusterMessage request) {
        final var response = new ClusterMessage();
        try {
            final var query = request.getAntiEntropy();
            response.setType(ClusterMessageType.PULL_ACK);
            response.setAntiEntropy(
                    antiEntropyService.buildPull(query.getDbName(), query.getCollName(), query.getIds()));
        } catch (Exception e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to build pull response: " + e.getMessage());
        }
        return response;
    }
}
