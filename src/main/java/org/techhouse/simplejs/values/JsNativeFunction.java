package org.techhouse.simplejs.values;

import java.util.List;
import java.util.function.BiFunction;

public final class JsNativeFunction extends JsValue {
    private final String name;
    private final BiFunction<JsValue, List<JsValue>, JsValue> implementation;

    public JsNativeFunction(String name, BiFunction<JsValue, List<JsValue>, JsValue> implementation) {
        this.name = name;
        this.implementation = implementation;
    }

    public String getName() {
        return name;
    }

    public JsValue invoke(JsValue thisArg, List<JsValue> args) {
        return implementation.apply(thisArg, args);
    }
}
