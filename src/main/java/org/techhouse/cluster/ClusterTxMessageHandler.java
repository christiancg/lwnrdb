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
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;

final class ClusterTxMessageHandler {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private static final Tx2pcDirectory tx2pcDirectory = IocContainer.get(Tx2pcDirectory.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private static final Tx2pcRecovery tx2pcRecovery = IocContainer.get(Tx2pcRecovery.class);
    private static final Logger logger = Logger.logFor(ClusterConnectionHandler.class);

    private ClusterTxMessageHandler() {
    }

    static ClusterMessage handleForwardTx(ClusterMessage request) {
        final var response = new ClusterMessage();
        final var sessionId = request.getTxSessionId();
        try {
            final var edgeNodeId = request.getSender() != null ? request.getSender().getNodeId() : null;
            final var session = clientTracker.registerTxSession(sessionId, request.getActingUser(), edgeNodeId);
            final var clientId = session.clientId();
            final var parsed = RequestParser.parseRequest(ForwardBody.decode(request.getForwardBody()));
            final var schemaError = SchemaValidationHelper.check(parsed);
            if (schemaError != null) {
                response.setType(ClusterMessageType.FORWARD_RESPONSE);
                response.setForwardBody(ForwardBody.encode(eJson.toJson(schemaError)));
                return response;
            }
            final var type = parsed.getType();
            // Run every op of the session on its own single-thread executor so the collection write locks it
            // holds across messages are acquired and released by the same thread.
            final var txId = request.getTxId();
            final var continuation = request.isTxContinuation();
            final var result = session.submit(() -> {
                final var refusal = retireStaleTransaction(clientId, txId, type);
                if (refusal != null) {
                    return refusal;
                }
                final var lostSlice = refuseLostSlice(clientId, continuation, type);
                if (lostSlice != null) {
                    return lostSlice;
                }
                if (startsTransaction(type) && clientTracker.getActiveTransaction(clientId) == null) {
                    // Start with the coordinator's distributed-tx id so the buffered slice and 2PC markers
                    // key on the same id everywhere.
                    TransactionOperationHelper.start(clientId, java.util.UUID.fromString(txId),
                            parsed.getTriggerDepth());
                }
                return operationProcessor.processMessage(parsed, clientId);
            }).get(clusterConfig.replicationAckTimeoutMs(), TimeUnit.MILLISECONDS);
            clientTracker.updateLastCommandTime(clientId);
            if ((finishesSession(type) || lostItsSlice(result)) && TransactionOperationHelper.releasedItsLocks(result)
                    && clientTracker.getActiveTransaction(clientId) == null) {
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

    private static OperationResponse refuseLostSlice(UUID clientId, boolean continuation, OperationType type) {
        if (!continuation || clientTracker.getActiveTransaction(clientId) != null) {
            return null;
        }
        return new OperationResponse(type, ErrorCode.TRANSACTION_SLICE_LOST);
    }

    private static OperationResponse retireStaleTransaction(UUID clientId, String txId, OperationType type) {
        final var active = clientTracker.getActiveTransaction(clientId);
        if (active == null || txId == null || active.getTransactionId().toString().equals(txId)) {
            return null;
        }
        final var rolledBack = TransactionOperationHelper.rollback(clientId);
        if (TransactionOperationHelper.releasedItsLocks(rolledBack)) {
            logger.warning("Rolled back forwarded transaction " + active.getTransactionId()
                    + " left behind on its session; the edge has moved on to " + txId);
            return null;
        }
        return new OperationResponse(type, ErrorCode.TRANSACTION_PREVIOUS_UNRESOLVED);
    }

    private static boolean holdsOtherTransaction(UUID clientId, String txId) {
        final var active = clientTracker.getActiveTransaction(clientId);
        return active != null && !active.getTransactionId().toString().equals(txId);
    }

    private static boolean lostItsSlice(OperationResponse result) {
        return ErrorCode.TRANSACTION_SLICE_LOST.getCode().equals(result.getErrorCode());
    }

    private static boolean finishesSession(OperationType type) {
        return type == OperationType.COMMIT_TRANSACTION || type == OperationType.ROLLBACK_TRANSACTION;
    }

    private static boolean startsTransaction(OperationType type) {
        return switch (type) {
            case SAVE, BULK_SAVE, DELETE, FIND_BY_ID, AGGREGATE -> true;
            default -> false;
        };
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
        final var participants = request.getTxParticipants();
        var vote = false;
        if (session != null) {
            try {
                vote = session
                        .submit(() -> preparesNamedTransaction(session.clientId(), request.getTxId(),
                                coordinatorAddress, participants))
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

    private static boolean preparesNamedTransaction(UUID clientId, String txId, String coordinatorAddress,
            List<String> participants) {
        if (holdsOtherTransaction(clientId, txId)) {
            logger.warning("Voting no on PREPARE for " + txId + ": its session holds transaction "
                    + clientTracker.getActiveTransaction(clientId).getTransactionId());
            return false;
        }
        return TwoPhaseParticipant.prepare(clientId, coordinatorAddress, participants);
    }

    private static OperationResponse resolveNamedTransaction(UUID clientId, String txId, boolean commit) {
        if (holdsOtherTransaction(clientId, txId)) {
            return null;
        }
        return commit ? TwoPhaseParticipant.commitPrepared(clientId) : TransactionOperationHelper.abort(clientId);
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
        final var session = clientTracker.txSession(sessionId);
        final var txId = request.getTxId();
        try {
            final var resolved = session == null
                    ? null
                    : session.submit(() -> resolveNamedTransaction(session.clientId(), txId, commit))
                            .get(clusterConfig.replicationAckTimeoutMs(), TimeUnit.MILLISECONDS);
            if (resolved == null) {
                final var budget = clusterConfig.replicationAckTimeoutMs();
                tx2pcRecovery.onRecoveryThread(() -> {
                    TwoPhaseParticipant.resolveFromDurable(txId, commit, budget);
                    return null;
                }, budget);
            } else {
                if (TransactionOperationHelper.releasedItsLocks(resolved)) {
                    clientTracker.removeTxSession(sessionId);
                }
                if (resolved.getStatus() != OperationStatus.OK) {
                    response.setType(ClusterMessageType.ERROR);
                    response.setErrorMessage("Participant failed to resolve transaction: " + resolved.getMessage());
                    return response;
                }
            }
            response.setType(ackType);
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
}
