package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.Coroutine;

public final class JsGenerator extends JsValue {
    private PropertyTable table;
    private JsValue proto;

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

    // A generator instance's [[Prototype]] is the generator function's own `.prototype`
    // (%GeneratorPrototype%-derived), one level below the shared realm intrinsic.
    @Override
    public JsValue getProto() {
        return proto;
    }

    @Override
    public void setProto(JsValue proto) {
        this.proto = proto;
    }
}
