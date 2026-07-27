package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.techhouse.simplejs.internal.EventLoop;

public final class JsPromise extends JsValue {
    public enum State {
        PENDING, FULFILLED, REJECTED
    }

    private record Reaction(Consumer<JsValue> onFulfilled, Consumer<JsValue> onRejected) {
    }

    private final EventLoop eventLoop;
    private State state = State.PENDING;
    private JsValue result = JsUndefined.getInstance();
    private final List<Reaction> reactions = new ArrayList<>();
    private boolean handled;

    public JsPromise(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
        eventLoop.registerPromise(this);
    }

    public State getState() {
        return state;
    }

    public JsValue getResult() {
        return result;
    }

    public boolean isUnhandledRejection() {
        return state == State.REJECTED && !handled;
    }

    public void resolve(JsValue value) {
        if (state != State.PENDING) {
            return;
        }
        if (value instanceof JsPromise other) {
            other.subscribe(this::resolve, this::reject);
            return;
        }
        settle(State.FULFILLED, value);
    }

    public void reject(JsValue reason) {
        if (state != State.PENDING) {
            return;
        }
        settle(State.REJECTED, reason);
    }

    private void settle(State newState, JsValue value) {
        state = newState;
        result = value;
        for (final var reaction : reactions) {
            scheduleReaction(reaction);
        }
        reactions.clear();
    }

    public void subscribe(Consumer<JsValue> onFulfilled, Consumer<JsValue> onRejected) {
        handled = true;
        final var reaction = new Reaction(onFulfilled, onRejected);
        if (state == State.PENDING) {
            reactions.add(reaction);
        } else {
            scheduleReaction(reaction);
        }
    }

    private void scheduleReaction(Reaction reaction) {
        final var settledState = state;
        final var settledValue = result;
        eventLoop.queueMicrotask(() -> {
            if (settledState == State.FULFILLED) {
                reaction.onFulfilled().accept(settledValue);
            } else {
                reaction.onRejected().accept(settledValue);
            }
        });
    }
}
