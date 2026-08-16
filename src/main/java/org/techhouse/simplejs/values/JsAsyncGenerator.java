package org.techhouse.simplejs.values;

import java.util.ArrayDeque;
import java.util.Deque;
import org.techhouse.simplejs.internal.Coroutine;

public final class JsAsyncGenerator extends JsValue {
    public enum State {
        SUSPENDED_START, SUSPENDED_YIELD, EXECUTING, AWAITING_RETURN, COMPLETED
    }

    public enum RequestKind {
        NEXT, RETURN, THROW
    }

    public record Request(RequestKind kind, JsValue value, JsPromise capability) {
    }

    private PropertyTable table;

    private final Coroutine coroutine;
    private final Deque<Request> queue = new ArrayDeque<>();
    private State state = State.SUSPENDED_START;

    public JsAsyncGenerator(Coroutine coroutine) {
        this.coroutine = coroutine;
    }

    public Coroutine getCoroutine() {
        return coroutine;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public void enqueue(Request request) {
        queue.addLast(request);
    }

    public boolean hasRequests() {
        return !queue.isEmpty();
    }

    public Request peekRequest() {
        return queue.peekFirst();
    }

    public Request pollRequest() {
        return queue.pollFirst();
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}
