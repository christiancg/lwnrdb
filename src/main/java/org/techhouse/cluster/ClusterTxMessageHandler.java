package org.techhouse.cluster;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.EJson;
import org.techhouse.ex.DurableReplayIncompleteException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ReplicatedTxApplyHelper;
import org.techhouse.ops.SchemaValidationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.tx.SliceStateMachine;
import org.techhouse.ops.tx.SliceStateMachine.Message;

final class ClusterTxMessageHandler {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final Tx2pcDirectory tx2pcDirectory = IocContainer.get(Tx2pcDirectory.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private static final Tx2pcRecovery tx2pcRecovery = IocContainer.get(Tx2pcRecovery.class);
    private static final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private static final Logger logger = Logger.logFor(ClusterConnectionHandler.class);

    private ClusterTxMessageHandler() {
    }

    static ClusterMessage handleForwardTx(ClusterMessage request) {
        final var response = new ClusterMessage();
        final var sessionId = request.getTxSessionId();
        try {
            final var parsed = RequestParser.parseRequest(ForwardBody.decode(request.getForwardBody()));
            final var schemaError = SchemaValidationHelper.check(parsed);
            if (schemaError != null) {
                response.setType(ClusterMessageType.FORWARD_RESPONSE);
                response.setForwardBody(ForwardBody.encode(eJson.toJson(schemaError)));
                return response;
            }
            final var edgeNodeId = request.getSender() != null ? request.getSender().getNodeId() : null;
            final var session = clientTracker.registerTxSession(sessionId, request.getActingUser(), edgeNodeId);
            final var clientId = session.clientId();
            final var message = forwardedMessage(parsed.getType(), request.isTxContinuation());
            final var result = session
                    .submit(() -> forwarded(clientId, request.getActingUser(), request.getTxId(), message, parsed))
                    .get(clusterConfig.replicationAckTimeoutMs(), TimeUnit.MILLISECONDS);
            clientTracker.updateLastCommandTime(clientId);
            if (clientTracker.getActiveTransaction(clientId) == null) {
                clientTracker.removeTxSession(sessionId);
            }
            response.setType(ClusterMessageType.FORWARD_RESPONSE);
            response.setForwardBody(ForwardBody.encode(eJson.toJson(result)));
        } catch (TimeoutException e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Forwarded transaction op did not resolve within the ack timeout");
        } catch (Exception e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to execute forwarded transaction op: " + e.getMessage());
        }
        return response;
    }

    static ClusterMessage handleReplicateTx(ClusterMessage request) {
        final var response = new ClusterMessage();
        if (ReplicatedTxApplyHelper.apply(request.getTxReplication(), clusterConfig.replicationAckTimeoutMs())) {
            response.setType(ClusterMessageType.REPLICATE_TX_ACK);
        } else {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to apply replicated transaction");
        }
        return response;
    }

    static ClusterMessage handlePrepareTx(ClusterMessage request) {
        final var response = new ClusterMessage();
        final var session = clientTracker.txSession(request.getTxSessionId());
        final var coordinatorAddress = request.getSender() != null ? request.getSender().address().toString() : null;
        var vote = false;
        if (session != null) {
            try {
                vote = session
                        .submit(() -> prepare(session.clientId(), request.getTxId(), coordinatorAddress,
                                request.getTxParticipants()))
                        .get(clusterConfig.replicationAckTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (TimeoutException e) {
                logger.warning("Forwarded transaction prepare did not resolve within the ack timeout");
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

    static ClusterMessage handleCommitTx(ClusterMessage request) {
        return resolveTx(request, true, ClusterMessageType.COMMIT_TX_ACK);
    }

    static ClusterMessage handleAbortTx(ClusterMessage request) {
        return resolveTx(request, false, ClusterMessageType.ABORT_TX_ACK);
    }

    private static ClusterMessage resolveTx(ClusterMessage request, boolean commit, ClusterMessageType ackType) {
        final var response = new ClusterMessage();
        final var sessionId = request.getTxSessionId();
        final var txId = request.getTxId();
        final var session = clientTracker.txSession(sessionId);
        final var budget = clusterConfig.replicationAckTimeoutMs();
        try {
            final boolean resolved;
            if (session != null && holdsSlice(session.clientId(), txId)) {
                final var result = session.submit(() -> resolveLive(session.clientId(), txId, commit)).get(budget,
                        TimeUnit.MILLISECONDS);
                if (clientTracker.getActiveTransaction(session.clientId()) == null) {
                    clientTracker.removeTxSession(sessionId);
                }
                resolved = TwoPhaseParticipant.resolved(result);
            } else {
                resolved = tx2pcRecovery.onRecoveryThread(() -> resolvesDurably(txId, commit), budget);
            }
            if (resolved) {
                response.setType(ackType);
            } else {
                response.setType(ClusterMessageType.ERROR);
                response.setErrorMessage("Participant cannot " + (commit ? "commit" : "abort") + " transaction " + txId
                        + " in its current state");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Interrupted resolving transaction");
        } catch (TimeoutException e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Participant did not resolve the transaction within the ack timeout");
        } catch (DurableReplayIncompleteException incomplete) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage(incomplete.getMessage());
        } catch (Exception e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Failed to resolve transaction: " + e.getMessage());
        }
        return response;
    }

    private static boolean holdsSlice(UUID clientId, String txId) {
        final var active = clientTracker.getActiveTransaction(clientId);
        return active != null && active.getTransactionId().toString().equals(txId);
    }

    static ClusterMessage handleTxStatus(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.TX_STATUS_ACK, "Failed to read transaction status",
                response -> response.setTxStatus(Tx2pcLog.status(request.getTxId(), selfAddress()).name()));
    }

    private static String selfAddress() {
        final var self = membershipService.getSelf();
        return self == null ? null : self.address().toString();
    }

    static ClusterMessage handleListTx() {
        return ClusterMessages.reply(ClusterMessageType.LIST_TX_ACK, "Failed to list in-doubt transactions",
                response -> response.setInDoubtTransactions(tx2pcDirectory.localInDoubt()));
    }

    private static Message forwardedMessage(OperationType type, boolean continuation) {
        return switch (type) {
            case COMMIT_TRANSACTION -> Message.COMMIT;
            case ROLLBACK_TRANSACTION -> Message.ROLLBACK;
            default -> continuation ? Message.CONTINUED_OP : Message.FIRST_OP;
        };
    }

    private static OperationResponse forwarded(UUID clientId, String actingUser, String namedTxId, Message message,
            OperationRequest request) throws Exception {
        final var type = request.getType();
        final var txId = namedTxId != null ? namedTxId : liveOrFreshTxId(clientId);
        if (!retiredAnotherSlice(clientId, txId)) {
            return new OperationResponse(type, ErrorCode.TRANSACTION_PREVIOUS_UNRESOLVED);
        }
        final var live = clientTracker.getActiveTransaction(clientId);
        final var decision = SliceStateMachine.decide(SliceStateMachine.stateOf(txId, live), message);
        return switch (decision.action()) {
            case START_AND_RUN -> {
                clientTracker.setAuthenticatedUser(clientId, actingUser);
                TransactionOperationHelper.start(clientId, UUID.fromString(txId), request.getTriggerDepth());
                yield operationProcessor.processMessage(request, clientId);
            }
            case RUN -> operationProcessor.processMessage(request, clientId);
            case COMMIT, FINISH_COMMIT -> live != null ? commitRecorded(clientId, txId) : finishDurably(txId, type);
            case ABORT -> rollbackRecorded(clientId, txId);
            case REPLAY_REPLY -> SliceStateMachine.recordedReply(txId, type);
            default -> new OperationResponse(type, decision.refusal());
        };
    }

    private static boolean prepare(UUID clientId, String txId, String coordinatorAddress, List<String> participants)
            throws Exception {
        if (holdsAnotherSlice(clientId, txId)) {
            logger.warning("Voting no on PREPARE for " + txId + ": its session holds transaction "
                    + clientTracker.getActiveTransaction(clientId).getTransactionId());
            return false;
        }
        final var live = clientTracker.getActiveTransaction(clientId);
        final var decision = SliceStateMachine.decide(SliceStateMachine.stateOf(txId, live), Message.PREPARE);
        return switch (decision.action()) {
            case PREPARE -> TwoPhaseParticipant.prepare(clientId, coordinatorAddress, participants);
            case VOTE_YES -> true;
            default -> false;
        };
    }

    private static OperationResponse resolveLive(UUID clientId, String txId, boolean commit) throws Exception {
        final var live = clientTracker.getActiveTransaction(clientId);
        final var decision = SliceStateMachine.decide(SliceStateMachine.stateOf(txId, live),
                commit ? Message.COMMIT_TX : Message.ABORT_TX);
        final var type = commit ? OperationType.COMMIT_TRANSACTION : OperationType.ROLLBACK_TRANSACTION;
        return switch (decision.action()) {
            case FINISH_COMMIT -> TwoPhaseParticipant.commitPrepared(clientId);
            case ABORT -> TransactionOperationHelper.abort(clientId);
            case ACK -> OperationResponse.ok(type, "Already resolved");
            default -> new OperationResponse(type, ErrorCode.ERROR_TRANSACTION);
        };
    }

    private static boolean resolvesDurably(String txId, boolean commit) throws Exception {
        final var decision = SliceStateMachine.decide(SliceStateMachine.stateOf(txId, null),
                commit ? Message.COMMIT_TX : Message.ABORT_TX);
        return switch (decision.action()) {
            case FINISH_COMMIT, ABORT -> {
                TwoPhaseParticipant.resolveFromDurable(txId, commit, clusterConfig.replicationAckTimeoutMs());
                yield true;
            }
            case ACK -> true;
            default -> false;
        };
    }

    private static OperationResponse finishDurably(String txId, OperationType type) throws Exception {
        final var budget = clusterConfig.replicationAckTimeoutMs();
        tx2pcRecovery.onRecoveryThread(() -> {
            TwoPhaseParticipant.resolveFromDurable(txId, true, budget);
            return null;
        }, budget);
        return SliceStateMachine.recordedReply(txId, type);
    }

    private static OperationResponse commitRecorded(UUID clientId, String txId) throws Exception {
        final var committed = TransactionOperationHelper.commitForwarded(clientId);
        if (clientTracker.getActiveTransaction(clientId) == null && !Tx2pcLog.hasOutcome(txId)) {
            Tx2pcLog.recordOutcome(txId, false, null);
        }
        return committed;
    }

    private static OperationResponse rollbackRecorded(UUID clientId, String txId) throws Exception {
        final var rolledBack = TransactionOperationHelper.rollback(clientId);
        if (rolledBack.getStatus() == OperationStatus.OK) {
            Tx2pcLog.recordOutcome(txId, false, null);
        }
        return rolledBack;
    }

    private static String liveOrFreshTxId(UUID clientId) {
        final var active = clientTracker.getActiveTransaction(clientId);
        return (active != null ? active.getTransactionId() : UUID.randomUUID()).toString();
    }

    private static boolean holdsAnotherSlice(UUID clientId, String txId) {
        final var active = clientTracker.getActiveTransaction(clientId);
        return active != null && !active.getTransactionId().toString().equals(txId);
    }

    private static boolean retiredAnotherSlice(UUID clientId, String txId) {
        if (!holdsAnotherSlice(clientId, txId)) {
            return true;
        }
        final var active = clientTracker.getActiveTransaction(clientId).getTransactionId().toString();
        if (SliceStateMachine.isFenced(active)) {
            return false;
        }
        TransactionOperationHelper.rollback(clientId);
        logger.warning("Rolled back forwarded transaction " + active
                + " left behind on its session; the edge has moved on to " + txId);
        return clientTracker.getActiveTransaction(clientId) == null;
    }
}
