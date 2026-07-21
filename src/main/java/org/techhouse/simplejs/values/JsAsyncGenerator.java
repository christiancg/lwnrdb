package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.Coroutine;

public final class JsAsyncGenerator extends JsValue {
    private final Coroutine coroutine;
    private JsPromise pending;

    public JsAsyncGenerator(Coroutine coroutine) {
        this.coroutine = coroutine;
    }

    public Coroutine getCoroutine() {
        return coroutine;
    }

    public void setPending(JsPromise promise) {
        this.pending = promise;
    }

    public JsPromise clearPending() {
        final var current = pending;
        pending = null;
        return current;
    }
}
