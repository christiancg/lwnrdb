package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.values.JsValue;

@FunctionalInterface
public interface IterableToList {
    List<JsValue> drain(JsValue iterable);
}
