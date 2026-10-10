package org.techhouse.ops.tx;

import java.util.EnumMap;
import java.util.Map;
import org.techhouse.ops.ErrorCode;

public final class SliceStateMachine {
    public enum State {
        NONE, ACTIVE, PREPARED, COMMITTING, COMMITTED, ABORTED
    }

    public enum Message {
        FIRST_OP, CONTINUED_OP, COMMIT, ROLLBACK, PREPARE, COMMIT_TX, ABORT_TX
    }

    public enum Action {
        START_AND_RUN, RUN, COMMIT, FINISH_COMMIT, ABORT, PREPARE, VOTE_YES, VOTE_NO, ACK, REPLAY_REPLY, REFUSE
    }

    public record Decision(Action action, ErrorCode refusal) {
    }

    private static final Map<State, Map<Message, Decision>> TABLE = new EnumMap<>(State.class);

    static {
        row(State.NONE, act(Action.START_AND_RUN), refuse(ErrorCode.TRANSACTION_SLICE_LOST),
                refuse(ErrorCode.TRANSACTION_SLICE_LOST), refuse(ErrorCode.TRANSACTION_SLICE_LOST), act(Action.VOTE_NO),
                act(Action.ACK), act(Action.ACK));
        row(State.ACTIVE, act(Action.RUN), act(Action.RUN), act(Action.COMMIT), act(Action.ABORT), act(Action.PREPARE),
                refuse(null), act(Action.ABORT));
        row(State.PREPARED, refuse(ErrorCode.TRANSACTION_HALF_APPLIED), refuse(ErrorCode.TRANSACTION_HALF_APPLIED),
                refuse(ErrorCode.TRANSACTION_HALF_APPLIED), refuse(ErrorCode.TRANSACTION_HALF_APPLIED),
                act(Action.VOTE_YES), act(Action.FINISH_COMMIT), act(Action.ABORT));
        row(State.COMMITTING, refuse(ErrorCode.TRANSACTION_HALF_APPLIED), refuse(ErrorCode.TRANSACTION_HALF_APPLIED),
                act(Action.FINISH_COMMIT), refuse(ErrorCode.TRANSACTION_HALF_APPLIED), act(Action.VOTE_YES),
                act(Action.FINISH_COMMIT), refuse(null));
        row(State.COMMITTED, refuse(ErrorCode.TRANSACTION_SLICE_LOST), refuse(ErrorCode.TRANSACTION_SLICE_LOST),
                act(Action.REPLAY_REPLY), refuse(ErrorCode.TRANSACTION_ALREADY_COMMITTED), act(Action.VOTE_YES),
                act(Action.ACK), refuse(null));
        row(State.ABORTED, refuse(ErrorCode.TRANSACTION_SLICE_LOST), refuse(ErrorCode.TRANSACTION_SLICE_LOST),
                refuse(ErrorCode.TRANSACTION_SLICE_LOST), act(Action.REPLAY_REPLY), act(Action.VOTE_NO), refuse(null),
                act(Action.ACK));
    }

    private SliceStateMachine() {
    }

    public static Decision decide(State state, Message message) {
        return TABLE.get(state).get(message);
    }

    private static Decision act(Action action) {
        return new Decision(action, null);
    }

    private static Decision refuse(ErrorCode refusal) {
        return new Decision(Action.REFUSE, refusal);
    }

    private static void row(State state, Decision... byMessage) {
        final var messages = Message.values();
        final var row = new EnumMap<Message, Decision>(Message.class);
        for (var i = 0; i < messages.length; i++) {
            row.put(messages[i], byMessage[i]);
        }
        TABLE.put(state, row);
    }
}
