package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.tx.SliceStateMachine;
import org.techhouse.ops.tx.SliceStateMachine.Action;
import org.techhouse.ops.tx.SliceStateMachine.Message;
import org.techhouse.ops.tx.SliceStateMachine.State;

public class SliceStateMachineTest extends VersionedReplaySupport {
    private static final String LOST = "409-13";
    private static final String FENCED = "500-33";
    private static final String COMMITTED_ALREADY = "409-14";
    private static final String PEER_ERROR = "";

    private static final String[][] EXPECTED = {{"NONE", "START_AND_RUN", LOST, LOST, LOST, "VOTE_NO", "ACK", "ACK"},
            {"ACTIVE", "RUN", "RUN", "COMMIT", "ABORT", "PREPARE", PEER_ERROR, "ABORT"},
            {"PREPARED", FENCED, FENCED, FENCED, FENCED, "VOTE_YES", "FINISH_COMMIT", "ABORT"},
            {"COMMITTING", FENCED, FENCED, "FINISH_COMMIT", FENCED, "VOTE_YES", "FINISH_COMMIT", PEER_ERROR},
            {"COMMITTED", LOST, LOST, "REPLAY_REPLY", COMMITTED_ALREADY, "VOTE_YES", "ACK", PEER_ERROR},
            {"ABORTED", LOST, LOST, LOST, "REPLAY_REPLY", "VOTE_NO", PEER_ERROR, "ACK"}};

    private static Executable cell(State state, Message message, String expected) {
        return () -> {
            final var decision = SliceStateMachine.decide(state, message);
            final var label = state + " x " + message;
            if (expected.isEmpty() || expected.contains("-")) {
                assertEquals(Action.REFUSE, decision.action(), label);
                assertEquals(expected.isEmpty() ? null : ErrorCode.byCode(expected), decision.refusal(), label);
            } else {
                assertEquals(Action.valueOf(expected), decision.action(), label);
                assertNull(decision.refusal(), label);
            }
        };
    }

    @Test
    public void test_every_state_and_message_is_decided_as_the_table_says() {
        final List<Executable> cells = new ArrayList<>();
        for (final var row : EXPECTED) {
            final var state = State.valueOf(row[0]);
            for (final var message : Message.values()) {
                cells.add(cell(state, message, row[message.ordinal() + 1]));
            }
        }
        assertEquals(State.values().length * Message.values().length, cells.size());
        assertAll(cells);
    }

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

        assertEquals(State.COMMITTED, SliceStateMachine.stateOf(txId, null));
    }

    @Test
    public void test_localcommit_outranks_part() throws Exception {
        final var txId = prepared();
        TxCommitLog.recordLocalCommit(txId, List.of(), List.of(collId));

        assertEquals(State.COMMITTING, SliceStateMachine.stateOf(txId, null));
        assertTrue(SliceStateMachine.isFenced(txId));
    }

    @Test
    public void test_a_prepared_slice_is_fenced() throws Exception {
        final var txId = prepared();

        assertEquals(State.PREPARED, SliceStateMachine.stateOf(txId, null));
        assertTrue(SliceStateMachine.isFenced(txId));
    }

    @Test
    public void test_a_live_transaction_with_no_marker_is_active() throws Exception {
        final var transaction = newTransaction();
        final var txId = transaction.getTransactionId().toString();

        assertEquals(State.ACTIVE, SliceStateMachine.stateOf(txId, transaction));
        assertEquals(State.NONE, SliceStateMachine.stateOf(UUID.randomUUID().toString(), transaction),
                "a live transaction under another id says nothing about this one");
        assertFalse(SliceStateMachine.isFenced(txId));
    }

    @Test
    public void test_no_marker_and_no_transaction_is_none() throws Exception {
        assertEquals(State.NONE, SliceStateMachine.stateOf(UUID.randomUUID().toString(), null));
    }

    @Test
    public void test_an_aborted_outcome_reads_as_aborted() throws Exception {
        final var txId = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(txId, false, null);

        assertEquals(State.ABORTED, SliceStateMachine.stateOf(txId, null));
    }

    @Test
    public void test_the_recorded_reply_round_trips() throws Exception {
        final var timedOut = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(timedOut, true, ErrorCode.REPLICATION_TIMEOUT.getCode());
        final var rolledBack = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(rolledBack, false, null);

        assertEquals(ErrorCode.REPLICATION_TIMEOUT.getCode(),
                SliceStateMachine.recordedReply(timedOut, OperationType.COMMIT_TRANSACTION).getErrorCode());
        assertEquals(OperationStatus.OK,
                SliceStateMachine.recordedReply(rolledBack, OperationType.ROLLBACK_TRANSACTION).getStatus());
        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), SliceStateMachine
                .recordedReply(UUID.randomUUID().toString(), OperationType.COMMIT_TRANSACTION).getErrorCode());
    }

    @Test
    public void test_no_restart_turns_a_decided_slice_into_a_lost_one() throws Exception {
        final var committing = prepared();
        TxCommitLog.recordLocalCommit(committing, List.of(), List.of(collId));
        final var committed = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(committed, true, null);

        for (final var txId : List.of(committing, committed)) {
            final var decision = SliceStateMachine.decide(SliceStateMachine.stateOf(txId, null), Message.COMMIT);
            assertFalse(decision.action() == Action.REFUSE && decision.refusal() == ErrorCode.TRANSACTION_SLICE_LOST,
                    "a re-sent COMMIT after a restart must learn the decided outcome, not that the slice was lost");
        }
    }
}
