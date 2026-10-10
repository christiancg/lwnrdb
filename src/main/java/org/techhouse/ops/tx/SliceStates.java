package org.techhouse.ops.tx;

import org.techhouse.data.Transaction;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.tx.SliceStateMachine.State;

public final class SliceStates {
    private SliceStates() {
    }

    public static State stateOf(String txId, Transaction live) throws Exception {
        final var outcome = Tx2pcLog.readOutcome(txId);
        if (outcome != null) {
            return outcome.committed() ? State.COMMITTED : State.ABORTED;
        }
        if (TxCommitLog.isLocallyCommitted(txId)) {
            return State.COMMITTING;
        }
        if (Tx2pcLog.isPrepared(txId)) {
            return State.PREPARED;
        }
        return live != null && live.getTransactionId().toString().equals(txId) ? State.ACTIVE : State.NONE;
    }

    public static boolean isFenced(String txId) {
        return TxCommitLog.isLocallyCommitted(txId) || Tx2pcLog.isPrepared(txId);
    }

    public static OperationResponse recordedReply(String txId, OperationType type) throws Exception {
        final var outcome = Tx2pcLog.readOutcome(txId);
        if (outcome == null) {
            return new OperationResponse(type, ErrorCode.TRANSACTION_SLICE_LOST);
        }
        if (!outcome.replyCode().isEmpty()) {
            return new OperationResponse(type, ErrorCode.byCode(outcome.replyCode()));
        }
        return OperationResponse.ok(type, outcome.committed() ? "Transaction committed" : "Transaction rolled back");
    }
}
