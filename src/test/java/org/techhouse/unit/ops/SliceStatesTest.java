package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.tx.SliceStateMachine;
import org.techhouse.ops.tx.SliceStateMachine.Action;
import org.techhouse.ops.tx.SliceStateMachine.Message;
import org.techhouse.ops.tx.SliceStateMachine.State;
import org.techhouse.ops.tx.SliceStates;

public class SliceStatesTest extends VersionedReplaySupport {

    private String prepared() throws Exception {
        final var txId = UUID.randomUUID().toString();
        markPrepared(txId);
        return txId;
    }

    @Test
    public void test_outcome_outranks_every_other_marker() throws Exception {
        final var txId = prepared();
        TxCommitLog.recordLocalCommit(txId, List.of(), List.of(collId));
        Tx2pcLog.recordOutcome(txId, true, null);

        assertEquals(State.COMMITTED, SliceStates.stateOf(txId, null));
    }

    @Test
    public void test_localcommit_outranks_part() throws Exception {
        final var txId = prepared();
        TxCommitLog.recordLocalCommit(txId, List.of(), List.of(collId));

        assertEquals(State.COMMITTING, SliceStates.stateOf(txId, null));
        assertTrue(SliceStates.isFenced(txId));
    }

    @Test
    public void test_a_prepared_slice_is_fenced() throws Exception {
        final var txId = prepared();

        assertEquals(State.PREPARED, SliceStates.stateOf(txId, null));
        assertTrue(SliceStates.isFenced(txId));
    }

    @Test
    public void test_a_live_transaction_with_no_marker_is_active() throws Exception {
        final var transaction = newTransaction();
        final var txId = transaction.getTransactionId().toString();

        assertEquals(State.ACTIVE, SliceStates.stateOf(txId, transaction));
        assertEquals(State.NONE, SliceStates.stateOf(UUID.randomUUID().toString(), transaction),
                "a live transaction under another id says nothing about this one");
        assertFalse(SliceStates.isFenced(txId));
    }

    @Test
    public void test_no_marker_and_no_transaction_is_none() throws Exception {
        assertEquals(State.NONE, SliceStates.stateOf(UUID.randomUUID().toString(), null));
    }

    @Test
    public void test_an_aborted_outcome_reads_as_aborted() throws Exception {
        final var txId = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(txId, false, null);

        assertEquals(State.ABORTED, SliceStates.stateOf(txId, null));
    }

    @Test
    public void test_the_recorded_reply_round_trips() throws Exception {
        final var timedOut = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(timedOut, true, ErrorCode.REPLICATION_TIMEOUT.getCode());
        final var rolledBack = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(rolledBack, false, null);

        assertEquals(ErrorCode.REPLICATION_TIMEOUT.getCode(),
                SliceStates.recordedReply(timedOut, OperationType.COMMIT_TRANSACTION).getErrorCode());
        assertEquals(OperationStatus.OK,
                SliceStates.recordedReply(rolledBack, OperationType.ROLLBACK_TRANSACTION).getStatus());
        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), SliceStates
                .recordedReply(UUID.randomUUID().toString(), OperationType.COMMIT_TRANSACTION).getErrorCode());
    }

    @Test
    public void test_no_restart_turns_a_decided_slice_into_a_lost_one() throws Exception {
        final var committing = prepared();
        TxCommitLog.recordLocalCommit(committing, List.of(), List.of(collId));
        final var committed = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(committed, true, null);

        for (final var txId : List.of(committing, committed)) {
            final var decision = SliceStateMachine.decide(SliceStates.stateOf(txId, null), Message.COMMIT);
            assertFalse(decision.action() == Action.REFUSE && decision.refusal() == ErrorCode.TRANSACTION_SLICE_LOST,
                    "a re-sent COMMIT after a restart must learn the decided outcome, not that the slice was lost");
        }
    }
}
