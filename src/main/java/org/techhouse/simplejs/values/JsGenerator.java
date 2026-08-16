package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.Coroutine;

public final class JsGenerator extends JsValue {
    private PropertyTable table;

    private final Coroutine coroutine;

    public JsGenerator(Coroutine coroutine) {
        this.coroutine = coroutine;
    }

    public Coroutine getCoroutine() {
        return coroutine;
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}
