package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.values.JsValue;

@FunctionalInterface
public interface Invoker {
    JsValue call(JsValue fn, JsValue thisArg, List<JsValue> args);
}
