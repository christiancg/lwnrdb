package org.techhouse.cluster;

import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ReplicatedTxApplyHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.RequestParser;

// The transaction half of the cluster protocol: forwarding a session's writes to the owner, the
// 2PC votes and decisions, and the status queries a stuck participant uses to terminate.
final class ClusterTxMessageHandler {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private static final Tx2pcDirectory tx2pcDirectory = IocContainer.get(Tx2pcDirectory.class);
    private static final Logger logger = Logger.logFor(ClusterConnectionHandler.class);

    private ClusterTxMessageHandler() {
    }

    // Runs one operation of a forwarded transaction on this (owner) node, under a persistent synthetic client
    // keyed by the edge's session id so the buffered transaction survives across the session's messages. The
    // transaction is started lazily on the first data/read op; commit/rollback tears the session down.
    static ClusterMessage handleForwardTx(ClusterMessage request) {
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

    static ClusterMessage handleReplicateTx(ClusterMessage request) {
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
    static ClusterMessage handlePrepareTx(ClusterMessage request) {
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

    static ClusterMessage handleCommitTx(ClusterMessage request) {
        return resolveTx(request, true, ClusterMessageType.COMMIT_TX_ACK);
    }

    static ClusterMessage handleAbortTx(ClusterMessage request) {
        return resolveTx(request, false, ClusterMessageType.ABORT_TX_ACK);
    }

    // Commits or aborts a prepared participant slice. If the in-memory session is still present the work runs
    // on its executor thread; otherwise (e.g. after a participant restart during coordinator re-drive) it
    // resolves the durable slice directly.
    private static ClusterMessage resolveTx(ClusterMessage request, boolean commit, ClusterMessageType ackType) {
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
    static ClusterMessage handleTxStatus(ClusterMessage request) {
        return ClusterMessages.reply(ClusterMessageType.TX_STATUS_ACK, "Failed to read transaction status",
                response -> response.setTxStatus(Tx2pcLog.status(request.getTxId()).name()));
    }

    // Reports this node's own in-doubt (PREPARED) distributed transactions for a cluster-wide
    // LIST_TRANSACTIONS aggregation.
    static ClusterMessage handleListTx() {
        return ClusterMessages.reply(ClusterMessageType.LIST_TX_ACK, "Failed to list in-doubt transactions",
                response -> response.setInDoubtTransactions(tx2pcDirectory.localInDoubt()));
    }
}
