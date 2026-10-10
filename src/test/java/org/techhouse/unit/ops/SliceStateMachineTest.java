package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.tx.SliceStateMachine;
import org.techhouse.ops.tx.SliceStateMachine.Action;
import org.techhouse.ops.tx.SliceStateMachine.Message;
import org.techhouse.ops.tx.SliceStateMachine.State;

public class SliceStateMachineTest {
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

    @Test
    public void test_a_decided_commit_is_never_aborted() {
        assertEquals(Action.REFUSE, SliceStateMachine.decide(State.COMMITTING, Message.ABORT_TX).action());
        assertEquals(Action.REFUSE, SliceStateMachine.decide(State.COMMITTED, Message.ABORT_TX).action());
    }

    @Test
    public void test_a_slice_the_node_never_saw_never_commits_a_fresh_one() {
        assertEquals(Action.REFUSE, SliceStateMachine.decide(State.NONE, Message.CONTINUED_OP).action());
        assertEquals(Action.REFUSE, SliceStateMachine.decide(State.NONE, Message.COMMIT).action());
        assertEquals(Action.VOTE_NO, SliceStateMachine.decide(State.NONE, Message.PREPARE).action());
    }
}
