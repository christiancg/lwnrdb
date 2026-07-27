package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ProxyBuiltins {
    private ProxyBuiltins() {
    }

    public static JsNativeFunction create() {
        return new JsNativeFunction("Proxy", (_, args) -> construct(args));
    }

    private static JsValue construct(List<JsValue> args) {
        final var target = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var handler = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        if (!isObject(target) || !(handler instanceof JsObject handlerObject)) {
            throw new TypeErrorException("Cannot create proxy with a non-object as target or handler");
        }
        return new JsProxy(target, handlerObject);
    }

    private static boolean isObject(JsValue value) {
        return value instanceof JsObject || value instanceof JsArray || value instanceof JsFunction
                || value instanceof JsNativeFunction || value instanceof JsClass || value instanceof JsProxy;
    }
}
