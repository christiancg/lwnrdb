package org.techhouse.simplejs.values;

import java.util.List;
import org.techhouse.simplejs.internal.Coroutine;

@FunctionalInterface
public interface CallerAwareFunction {
    JsValue call(JsValue thisArg, List<JsValue> args, Coroutine caller);
}
