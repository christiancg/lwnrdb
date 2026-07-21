package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.Coroutine;

public final class JsGenerator extends JsValue {
    private final Coroutine coroutine;

    public JsGenerator(Coroutine coroutine) {
        this.coroutine = coroutine;
    }

    public Coroutine getCoroutine() {
        return coroutine;
    }
}
