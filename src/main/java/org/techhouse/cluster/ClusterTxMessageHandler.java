package org.techhouse.cluster;

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
import org.techhouse.ops.ReplicatedTxApplyHelper;
import org.techhouse.ops.SchemaValidationHelper;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.RequestParser;

final class ClusterTxMessageHandler {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
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
            final var message = SliceActions.forwardedMessage(parsed.getType(), request.isTxContinuation());
            final var result = session.submit(
                    () -> SliceActions.forwarded(clientId, request.getActingUser(), request.getTxId(), message, parsed))
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
                        .submit(() -> SliceActions.prepare(session.clientId(), request.getTxId(), coordinatorAddress,
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
                final var result = session.submit(() -> SliceActions.resolveLive(session.clientId(), txId, commit))
                        .get(budget, TimeUnit.MILLISECONDS);
                if (clientTracker.getActiveTransaction(session.clientId()) == null) {
                    clientTracker.removeTxSession(sessionId);
                }
                resolved = TwoPhaseParticipant.resolved(result);
            } else {
                resolved = tx2pcRecovery.onRecoveryThread(() -> SliceActions.resolvesDurably(txId, commit), budget);
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
}
