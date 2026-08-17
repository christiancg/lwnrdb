package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ProxyBuiltins {
    private ProxyBuiltins() {
    }

    public static JsNativeFunction create() {
        final var proxy = new JsNativeFunction("Proxy", (_, args) -> construct(args));
        proxy.setProperty("revocable", new JsNativeFunction("revocable", (_, args) -> revocable(args)));
        return proxy;
    }

    private static JsProxy construct(List<JsValue> args) {
        final var target = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var handler = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        if (!InterpreterUtils.isObjectLike(target) || !InterpreterUtils.isObjectLike(handler)) {
            throw new TypeErrorException("Cannot create proxy with a non-object as target or handler");
        }
        return new JsProxy(target, handler);
    }

    private static JsValue revocable(List<JsValue> args) {
        final var proxy = construct(args);
        final var result = new JsObject();
        result.set("proxy", proxy);
        result.set("revoke", new JsNativeFunction("", (_, _) -> {
            proxy.revoke();
            return JsUndefined.getInstance();
        }));
        return result;
    }
}
