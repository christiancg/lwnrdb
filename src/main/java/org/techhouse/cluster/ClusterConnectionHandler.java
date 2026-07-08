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
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.req.RequestParser;

public class ClusterConnectionHandler implements Runnable {
    private final EJson eJson = IocContainer.get(EJson.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
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
            case FORWARD_REQUEST -> handleForward(request);
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
            // The edge node already authenticated and authorized the client; the owner re-parses the raw
            // request and executes it directly (bypassing the router, so there is no forward loop).
            final var parsed = RequestParser.parseRequest(ForwardBody.decode(request.getForwardBody()));
            response.setType(ClusterMessageType.FORWARD_RESPONSE);
            response.setForwardBody(ForwardBody.encode(eJson.toJson(operationProcessor.processMessage(parsed))));
        } catch (Exception e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to execute forwarded request: " + e.getMessage());
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
}
