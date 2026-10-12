package org.techhouse.cluster;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLException;
import org.techhouse.cluster.admin.AdminRecordMerge;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.conn.BoundedLineReader;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.InFlightRequests;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.EJson;
import org.techhouse.ex.RequestTooLargeException;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.SchemaValidationHelper;
import org.techhouse.ops.ScriptRunRegistry;
import org.techhouse.ops.TriggerRunResolution;
import org.techhouse.ops.index.IndexOperationHelper;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.ops.resp.OperationResponse;

public class ClusterConnectionHandler implements Runnable {
    private final EJson eJson = IocContainer.get(EJson.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final AdminAntiEntropyService adminAntiEntropyService = IocContainer.get(AdminAntiEntropyService.class);
    private final ScriptRunDirectory scriptRunDirectory = IocContainer.get(ScriptRunDirectory.class);
    private final TriggerRunDirectory triggerRunDirectory = IocContainer.get(TriggerRunDirectory.class);
    private final ScriptRunRegistry scriptRunRegistry = IocContainer.get(ScriptRunRegistry.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final InFlightRequests inFlightRequests = IocContainer.get(InFlightRequests.class);
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private final Logger logger = Logger.logFor(ClusterConnectionHandler.class);
    private final Socket socket;
    private final BooleanSupplier refusingWrites;
    private final Runnable handshakeDone;

    public ClusterConnectionHandler(Socket socket, BooleanSupplier refusingWrites, Runnable handshakeDone) {
        this.socket = socket;
        this.refusingWrites = refusingWrites;
        this.handshakeDone = handshakeDone;
    }

    @Override
    public void run() {
        try (socket) {
            final var reader = new BoundedLineReader(socket.getInputStream(), socket::setSoTimeout);
            final var accepted = ClusterHandshake.accept(reader);
            handshakeDone.run();
            if (accepted) {
                serve(reader);
            }
        } catch (RequestTooLargeException e) {
            logger.warning("Closed a cluster connection: a frame exceeded clusterMaxFrameSize");
        } catch (SSLException e) {
            logger.warning("Rejected cluster connection: TLS handshake failed");
        } catch (IOException e) {
            logger.warning("Cluster connection error: " + e.getMessage());
        } finally {
            handshakeDone.run();
        }
    }

    private void serve(BoundedLineReader reader) throws IOException {
        // Both executors are declared after the socket so both close first: close() drains what is
        // outstanding, and draining while the socket is still open is what lets those answers be written.
        try (var concurrent = Executors.newVirtualThreadPerTaskExecutor();
                var ordered = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())) {
            final var writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            // One lock because gossip answers off this thread: two writers interleaving their JSON would
            // corrupt the frame stream. Replying out of order is fine - the peer dispatches on correlationId.
            final var writerLock = new ReentrantLock();
            final var maxFrameBytes = clusterConfig.maxFrameBytes();
            String line;
            while ((line = reader.readLine(maxFrameBytes)) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final var request = eJson.fromJson(line, ClusterMessage.class);
                if (request == null) {
                    continue;
                }
                if (mayOvertake(request.getType())) {
                    concurrent.execute(() -> respondSafely(writer, writerLock, request));
                } else {
                    ordered.execute(() -> respondSafely(writer, writerLock, request));
                }
            }
        }
    }

    private static boolean mayOvertake(ClusterMessageType type) {
        return switch (type) {
            case GOSSIP, FORWARD_REQUEST, DIGEST, PULL, TX_STATUS, LIST_TX -> true;
            default -> false;
        };
    }

    private void respond(BufferedWriter writer, ReentrantLock writerLock, ClusterMessage request) throws IOException {
        final var response = handle(request);
        if (faultInjector.dropsAt(FaultPoint.PEER_REPLY, String.valueOf(request.getType()))) {
            return;
        }
        response.setCorrelationId(request.getCorrelationId());
        writerLock.lock();
        try {
            writer.write(eJson.toJson(response));
            writer.newLine();
            writer.flush();
        } finally {
            writerLock.unlock();
        }
    }

    private void respondSafely(BufferedWriter writer, ReentrantLock writerLock, ClusterMessage request) {
        try {
            respond(writer, writerLock, request);
        } catch (IOException e) {
            logger.warning("Could not answer a " + request.getType() + " request: " + e.getMessage());
        }
    }

    private ClusterMessage handle(ClusterMessage request) {
        if (!ClusterSecret.matches(request.getSecret())) {
            final var error = new ClusterMessage();
            error.setType(ClusterMessageType.ERROR);
            error.setErrorMessage("Invalid cluster secret");
            return error;
        }
        if (!carriesWrite(request)) {
            return dispatch(request);
        }
        inFlightRequests.enter();
        try {
            return refusingWrites.getAsBoolean() ? refusal(request) : dispatch(request);
        } finally {
            inFlightRequests.exit();
        }
    }

    private ClusterMessage dispatch(ClusterMessage request) {
        return switch (request.getType()) {
            case JOIN_REQUEST -> membershipService.handleJoin(request);
            case GOSSIP -> membershipService.handleGossip(request);
            case REPLICATE -> handleReplicate(request);
            case REPLICATE_ADMIN -> handleReplicateAdmin(request);
            case REINDEX_BROADCAST -> handleReindexBroadcast(request);
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

    private ClusterMessage handleListScripts() {
        return ClusterMessages.reply(ClusterMessageType.LIST_SCRIPTS_ACK, "Failed to list running scripts",
                response -> response.setRunningScripts(scriptRunDirectory.localRuns()));
    }

    private ClusterMessage handleCancelScript(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.CANCEL_SCRIPT_ACK, "Failed to cancel the running script",
                response -> response.setCancelledRun(scriptRunRegistry.cancel(request.getCancelRunId())));
    }

    // admin/trigger_runs is not replicated, so a run's record exists on exactly one node and only that
    // node can answer for it.
    private ClusterMessage handleListTriggerRuns(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.LIST_TRIGGER_RUNS_ACK, "Failed to list trigger runs",
                response -> response
                        .setTriggerRuns(triggerRunDirectory.localRuns(statusFilter(request.getTriggerRunDecision()))));
    }

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

    private ClusterMessage handleAdminSnapshot() {
        return ClusterMessages.reply(ClusterMessageType.ADMIN_SNAPSHOT_ACK, "Failed to build admin snapshot",
                response -> response.setAdminSnapshot(adminAntiEntropyService.buildSnapshot()));
    }

    private ClusterMessage handleReplicateAdmin(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.REPLICATE_ADMIN_ACK, "Failed to apply the admin records",
                response -> {
                    if (!AdminRecordMerge.apply(request.getAdminSnapshot().getRecords(), true).complete()) {
                        adminAntiEntropyService.reconcileSoon();
                        response.setType(ClusterMessageType.ERROR);
                        response.setErrorMessage("Could not apply every replicated admin record; an admin"
                                + " anti-entropy round retries the rest");
                    }
                });
    }

    private ClusterMessage handleReindexBroadcast(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.REINDEX_BROADCAST_ACK, "Failed to rebuild the indexes",
                response -> {
                    final var reindex = (ReindexRequest) RequestParser
                            .parseRequest(ForwardBody.decode(request.getForwardBody()));
                    final var validation = RequestValidator.validate(reindex);
                    if (!validation.isValid()) {
                        response.setType(ClusterMessageType.ERROR);
                        response.setErrorMessage("Refused the index rebuild: " + validation.getErrorMessage());
                        return;
                    }
                    final var result = IndexOperationHelper.processReindex(reindex, true);
                    if (result.getStatus() != OperationStatus.OK) {
                        response.setType(ClusterMessageType.ERROR);
                        response.setErrorMessage("Rebuilding the indexes failed: " + result.getMessage());
                    }
                });
    }

    // Goes straight to OperationProcessor, bypassing the router, so a forwarded request cannot forward
    // again. The synthetic client carries the acting user so admin ops apply with the correct identity.
    private OperationResponse executeForwarded(ClusterMessage request) {
        final var actingUser = request.getActingUser();
        final var parsed = RequestParser.parseRequest(ForwardBody.decode(request.getForwardBody()));
        final var refused = ForwardedRequestGate.check(parsed, actingUser);
        if (refused != null) {
            return refused;
        }
        final var clientId = actingUser != null ? clientTracker.registerForwardedClient(actingUser) : null;
        try {
            final var schemaError = SchemaValidationHelper.check(parsed);
            if (schemaError != null) {
                return schemaError;
            }
            return operationProcessor.processMessage(parsed, clientId);
        } finally {
            if (clientId != null) {
                clientTracker.removeById(clientId);
            }
        }
    }

    private ClusterMessage handleReplicate(ClusterMessage request) {
        if (!PeerNames.payload(request.getReplication())) {
            return ClusterMessages.invalidPayload();
        }
        final var response = new ClusterMessage();
        if (ReplicatedApplyHelper.apply(request.getReplication(), clusterConfig.replicationAckTimeoutMs())) {
            response.setType(ClusterMessageType.REPLICATE_ACK);
        } else {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to apply replicated write");
        }
        return response;
    }

    private ClusterMessage notAdminSyncedYet() {
        final var response = new ClusterMessage();
        response.setType(ClusterMessageType.ERROR);
        response.setErrorMessage("This node has not completed its first admin reconciliation, so its documents"
                + " cannot be described yet");
        return response;
    }

    private ClusterMessage handleDigest(ClusterMessage request) {
        if (!PeerNames.antiEntropy(request.getAntiEntropy())) {
            return ClusterMessages.invalidPayload();
        }
        if (adminAntiEntropyService.isSyncing()) {
            return notAdminSyncedYet();
        }
        return ClusterMessages.reply(ClusterMessageType.DIGEST_ACK, "Failed to build digest", response -> {
            final var query = request.getAntiEntropy();
            response.setAntiEntropy(antiEntropyService.buildDigest(query.getDbName(), query.getCollName(),
                    query.getSummary(), query.incarnationValue()));
        });
    }

    private ClusterMessage handlePull(ClusterMessage request) {
        if (!PeerNames.antiEntropy(request.getAntiEntropy())) {
            return ClusterMessages.invalidPayload();
        }
        if (adminAntiEntropyService.isSyncing()) {
            return notAdminSyncedYet();
        }
        return ClusterMessages.reply(ClusterMessageType.PULL_ACK, "Failed to build pull response", response -> {
            final var query = request.getAntiEntropy();
            response.setAntiEntropy(antiEntropyService.buildPull(query.getDbName(), query.getCollName(), query.getIds(),
                    query.incarnationValue()));
        });
    }

    private static boolean carriesWrite(ClusterMessage request) {
        return switch (request.getType()) {
            case REPLICATE, REPLICATE_TX, REPLICATE_ADMIN, REINDEX_BROADCAST, FORWARD_REQUEST, PREPARE_TX, COMMIT_TX ->
                true;
            case FORWARD_TX_REQUEST -> forwardedType(request) != OperationType.ROLLBACK_TRANSACTION;
            default -> false;
        };
    }

    private ClusterMessage refusal(ClusterMessage request) {
        final var response = new ClusterMessage();
        final var forwardedType = isForward(request.getType()) ? forwardedType(request) : null;
        if (forwardedType == null) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Refused " + request.getType() + ": this node is shutting down");
            return response;
        }
        response.setType(ClusterMessageType.FORWARD_RESPONSE);
        response.setForwardBody(
                ForwardBody.encode(eJson.toJson(new OperationResponse(forwardedType, ErrorCode.SERVER_SHUTTING_DOWN))));
        return response;
    }

    private static boolean isForward(ClusterMessageType type) {
        return type == ClusterMessageType.FORWARD_REQUEST || type == ClusterMessageType.FORWARD_TX_REQUEST;
    }

    private static OperationType forwardedType(ClusterMessage request) {
        try {
            return RequestParser.parseRequest(ForwardBody.decode(request.getForwardBody())).getType();
        } catch (RuntimeException unparseable) {
            return null;
        }
    }
}
