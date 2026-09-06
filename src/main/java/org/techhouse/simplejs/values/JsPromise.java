package org.techhouse.simplejs.values;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.techhouse.simplejs.builtins.ErrorBuiltins;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;

public final class JsPromise extends JsValue {
    private PropertyTable table;

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

    public Intrinsics intrinsics() {
        return eventLoop.intrinsics();
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
        if (value == this) {
            reject(chainingCycleError());
            return;
        }
        final var ops = eventLoop.ops();
        if (ops == null && value instanceof JsPromise other) {
            other.subscribe(this::resolve, this::reject);
            return;
        }
        if (ops != null && isObjectLike(value)) {
            final JsValue then;
            try {
                then = ops.getMember(value, new JsString("then"));
            } catch (SimpleJsRuntimeException error) {
                reject(errorValueOf(error));
                return;
            }
            if (isCallable(then)) {
                resolveThenable(value, then, ops);
                return;
            }
        }
        settle(State.FULFILLED, value);
    }

    // Spec [[Resolve]] for a thenable: schedule a job that calls `then` with fresh resolve/reject
    // functions bound to this promise, rather than fulfilling with the thenable object itself.
    private void resolveThenable(JsValue thenable, JsValue then, InterpreterOps ops) {
        eventLoop.queueMicrotask(() -> {
            final var alreadyResolved = new boolean[]{false};
            final var resolveFn = new JsNativeFunction("", (_, args) -> {
                if (alreadyResolved[0]) {
                    return JsUndefined.getInstance();
                }
                alreadyResolved[0] = true;
                resolve(args.isEmpty() ? JsUndefined.getInstance() : args.getFirst());
                return JsUndefined.getInstance();
            });
            resolveFn.setLength(1);
            final var rejectFn = new JsNativeFunction("", (_, args) -> {
                if (alreadyResolved[0]) {
                    return JsUndefined.getInstance();
                }
                alreadyResolved[0] = true;
                reject(args.isEmpty() ? JsUndefined.getInstance() : args.getFirst());
                return JsUndefined.getInstance();
            });
            rejectFn.setLength(1);
            try {
                ops.call(then, thenable, List.of(resolveFn, rejectFn));
            } catch (SimpleJsRuntimeException error) {
                if (!alreadyResolved[0]) {
                    alreadyResolved[0] = true;
                    reject(errorValueOf(error));
                }
            }
        });
    }

    private JsValue chainingCycleError() {
        final var intrinsics = eventLoop.intrinsics();
        final var message = "Chaining cycle detected for promise";
        return intrinsics == null
                ? ErrorBuiltins.makeError("TypeError", message)
                : intrinsics.makeError("TypeError", message);
    }

    private JsValue errorValueOf(SimpleJsRuntimeException error) {
        if (error instanceof JsThrowException thrown) {
            return thrown.getValue();
        }
        return InterpreterUtils.toErrorValue(error, eventLoop.intrinsics());
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

    public void markHandled() {
        handled = true;
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

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}
