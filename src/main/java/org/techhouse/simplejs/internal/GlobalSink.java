package org.techhouse.simplejs.internal;

import org.techhouse.simplejs.values.JsValue;

@FunctionalInterface
public interface GlobalSink {
    void declareBuiltin(String name, JsValue value);
}
