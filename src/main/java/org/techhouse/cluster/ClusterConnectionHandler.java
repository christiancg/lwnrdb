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
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.ReplicatedTxApplyHelper;
import org.techhouse.ops.ReplicatedUserApplyHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
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
            case REPLICATE_TX -> handleReplicateTx(request);
            case FORWARD_REQUEST -> handleForward(request);
            case FORWARD_TX_REQUEST -> handleForwardTx(request);
            case PREPARE_TX -> handlePrepareTx(request);
            case COMMIT_TX -> handleCommitTx(request);
            case ABORT_TX -> handleAbortTx(request);
            case TX_STATUS -> handleTxStatus(request);
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

    // Runs one operation of a forwarded transaction on this (owner) node, under a persistent synthetic client
    // keyed by the edge's session id so the buffered transaction survives across the session's messages. The
    // transaction is started lazily on the first data/read op; commit/rollback tears the session down.
    private ClusterMessage handleForwardTx(ClusterMessage request) {
        final var response = new ClusterMessage();
        final var sessionId = request.getTxSessionId();
        try {
            final var edgeNodeId = request.getSender() != null ? request.getSender().getNodeId() : null;
            final var session = clientTracker.registerTxSession(sessionId, request.getActingUser(), edgeNodeId);
            final var clientId = session.clientId();
            final var parsed = RequestParser.parseRequest(ForwardBody.decode(request.getForwardBody()));
            final var type = parsed.getType();
            // Run every op of the session on its own single-thread executor so the collection write locks it
            // holds across messages are acquired and released by the same thread.
            final var txId = request.getTxId();
            final var result = session.submit(() -> {
                if (startsTransaction(type) && clientTracker.getActiveTransaction(clientId) == null) {
                    // Start with the coordinator's distributed-tx id so the buffered slice and 2PC markers
                    // key on the same id everywhere.
                    TransactionOperationHelper.start(clientId, java.util.UUID.fromString(txId));
                }
                return operationProcessor.processMessage(parsed, clientId);
            }).get();
            if (type == OperationType.COMMIT_TRANSACTION || type == OperationType.ROLLBACK_TRANSACTION) {
                clientTracker.removeTxSession(sessionId);
            }
            response.setType(ClusterMessageType.FORWARD_RESPONSE);
            response.setForwardBody(ForwardBody.encode(eJson.toJson(result)));
        } catch (Exception e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to execute forwarded transaction op: " + e.getMessage());
        }
        return response;
    }

    private static boolean startsTransaction(OperationType type) {
        return switch (type) {
            case SAVE, BULK_SAVE, DELETE, FIND_BY_ID, AGGREGATE -> true;
            default -> false;
        };
    }

    private ClusterMessage handleReplicateTx(ClusterMessage request) {
        final var response = new ClusterMessage();
        if (ReplicatedTxApplyHelper.apply(request.getTxReplication())) {
            response.setType(ClusterMessageType.REPLICATE_TX_ACK);
        } else {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to apply replicated transaction");
        }
        return response;
    }

    // Phase 5b participant: votes on a PREPARE from the coordinator, running on the session's executor thread
    // (the holder of its write locks). No session means nothing was buffered here, so it votes no.
    private ClusterMessage handlePrepareTx(ClusterMessage request) {
        final var response = new ClusterMessage();
        final var session = clientTracker.txSession(request.getTxSessionId());
        final var coordinatorAddress = request.getSender() != null ? request.getSender().address().toString() : null;
        final var participants = request.getTxParticipants();
        var vote = false;
        if (session != null) {
            try {
                vote = session.submit(
                        () -> TransactionOperationHelper.prepare(session.clientId(), coordinatorAddress, participants))
                        .get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.warning("Failed to prepare forwarded transaction: " + e.getMessage());
            }
        }
        if (vote) {
            response.setType(ClusterMessageType.PREPARE_TX_ACK);
        } else {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Participant voted no");
        }
        return response;
    }

    private ClusterMessage handleCommitTx(ClusterMessage request) {
        return resolveTx(request, true, ClusterMessageType.COMMIT_TX_ACK);
    }

    private ClusterMessage handleAbortTx(ClusterMessage request) {
        return resolveTx(request, false, ClusterMessageType.ABORT_TX_ACK);
    }

    // Commits or aborts a prepared participant slice. If the in-memory session is still present the work runs
    // on its executor thread; otherwise (e.g. after a participant restart during coordinator re-drive) it
    // resolves the durable slice directly.
    private ClusterMessage resolveTx(ClusterMessage request, boolean commit, ClusterMessageType ackType) {
        final var response = new ClusterMessage();
        final var sessionId = request.getTxSessionId();
        final var session = clientTracker.txSession(sessionId);
        try {
            if (session != null) {
                session.submit(() -> commit
                        ? TransactionOperationHelper.commitPrepared(session.clientId())
                        : TransactionOperationHelper.abort(session.clientId())).get();
                clientTracker.removeTxSession(sessionId);
            } else {
                TransactionOperationHelper.resolveFromDurable(request.getTxId(), commit);
            }
            response.setType(ackType);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Interrupted resolving transaction");
        } catch (Exception e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to resolve transaction: " + e.getMessage());
        }
        return response;
    }

    // Phase 5b status query: reports this node's own knowledge of the transaction
    // (COMMITTED/ABORTED/PREPARED/UNKNOWN) for the coordinator's presumed-abort check and for a peer's
    // cooperative termination.
    private ClusterMessage handleTxStatus(ClusterMessage request) {
        final var response = new ClusterMessage();
        try {
            response.setType(ClusterMessageType.TX_STATUS_ACK);
            response.setTxStatus(Tx2pcLog.status(request.getTxId()).name());
        } catch (Exception e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to read transaction status: " + e.getMessage());
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
