package org.techhouse.cluster;

import java.util.List;
import java.util.UUID;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.tx.SliceStateMachine;
import org.techhouse.ops.tx.SliceStateMachine.Message;
import org.techhouse.ops.tx.SliceStates;

final class SliceActions {
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final Logger logger = Logger.logFor(SliceActions.class);

    private SliceActions() {
    }

    static Message forwardedMessage(OperationType type, boolean continuation) {
        return switch (type) {
            case COMMIT_TRANSACTION -> Message.COMMIT;
            case ROLLBACK_TRANSACTION -> Message.ROLLBACK;
            default -> continuation ? Message.CONTINUED_OP : Message.FIRST_OP;
        };
    }

    static OperationResponse forwarded(UUID clientId, String actingUser, String namedTxId, Message message,
            OperationRequest request) throws Exception {
        final var type = request.getType();
        final var txId = namedTxId != null ? namedTxId : liveOrFreshTxId(clientId);
        if (!retiredAnotherSlice(clientId, txId)) {
            return new OperationResponse(type, ErrorCode.TRANSACTION_PREVIOUS_UNRESOLVED);
        }
        final var live = clientTracker.getActiveTransaction(clientId);
        final var decision = SliceStateMachine.decide(SliceStates.stateOf(txId, live), message);
        return switch (decision.action()) {
            case START_AND_RUN -> {
                clientTracker.setAuthenticatedUser(clientId, actingUser);
                TransactionOperationHelper.start(clientId, UUID.fromString(txId), request.getTriggerDepth());
                yield operationProcessor.processMessage(request, clientId);
            }
            case RUN -> operationProcessor.processMessage(request, clientId);
            case COMMIT, FINISH_COMMIT -> live != null ? commitRecorded(clientId, txId) : finishDurably(txId, type);
            case ABORT -> rollbackRecorded(clientId, txId);
            case REPLAY_REPLY -> SliceStates.recordedReply(txId, type);
            default -> new OperationResponse(type, decision.refusal());
        };
    }

    static boolean prepare(UUID clientId, String txId, String coordinatorAddress, List<String> participants)
            throws Exception {
        if (holdsAnotherSlice(clientId, txId)) {
            logger.warning("Voting no on PREPARE for " + txId + ": its session holds transaction "
                    + clientTracker.getActiveTransaction(clientId).getTransactionId());
            return false;
        }
        final var live = clientTracker.getActiveTransaction(clientId);
        final var decision = SliceStateMachine.decide(SliceStates.stateOf(txId, live), Message.PREPARE);
        return switch (decision.action()) {
            case PREPARE -> TwoPhaseParticipant.prepare(clientId, coordinatorAddress, participants);
            case VOTE_YES -> true;
            default -> false;
        };
    }

    static OperationResponse resolveLive(UUID clientId, String txId, boolean commit) throws Exception {
        final var live = clientTracker.getActiveTransaction(clientId);
        final var decision = SliceStateMachine.decide(SliceStates.stateOf(txId, live),
                commit ? Message.COMMIT_TX : Message.ABORT_TX);
        final var type = commit ? OperationType.COMMIT_TRANSACTION : OperationType.ROLLBACK_TRANSACTION;
        return switch (decision.action()) {
            case FINISH_COMMIT -> TwoPhaseParticipant.commitPrepared(clientId);
            case ABORT -> TransactionOperationHelper.abort(clientId);
            case ACK -> OperationResponse.ok(type, "Already resolved");
            default -> new OperationResponse(type, ErrorCode.ERROR_TRANSACTION);
        };
    }

    static boolean resolvesDurably(String txId, boolean commit) throws Exception {
        final var decision = SliceStateMachine.decide(SliceStates.stateOf(txId, null),
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
        TwoPhaseParticipant.resolveFromDurable(txId, true, clusterConfig.replicationAckTimeoutMs());
        return SliceStates.recordedReply(txId, type);
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
        if (SliceStates.isFenced(active)) {
            return false;
        }
        TransactionOperationHelper.rollback(clientId);
        logger.warning("Rolled back forwarded transaction " + active
                + " left behind on its session; the edge has moved on to " + txId);
        return clientTracker.getActiveTransaction(clientId) == null;
    }
}
