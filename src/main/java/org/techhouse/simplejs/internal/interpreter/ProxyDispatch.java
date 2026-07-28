package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsValue;

// Proxy trap dispatch: for each intercepted operation, look up the handler's trap and either call
// it or fall back to the raw operation on the target. Every fallback and re-entry routes through
// the interpreter's InterpreterOps seam, so this carries no interpreter state of its own.
public final class ProxyDispatch {
    private final InterpreterOps ops;

    public ProxyDispatch(InterpreterOps ops) {
        this.ops = ops;
    }

    public JsValue get(JsProxy proxy, JsValue key) {
        final var trap = trapOf(proxy, "get");
        if (trap == null) {
            return ops.getMember(proxy.getTarget(), key);
        }
        return ops.call(trap, proxy.getHandler(), List.of(proxy.getTarget(), key, proxy));
    }

    public void set(JsProxy proxy, JsValue key, JsValue value) {
        final var trap = trapOf(proxy, "set");
        if (trap == null) {
            ops.setMember(proxy.getTarget(), key, value);
            return;
        }
        ops.call(trap, proxy.getHandler(), List.of(proxy.getTarget(), key, value, proxy));
    }

    public boolean has(JsProxy proxy, JsValue key) {
        final var trap = trapOf(proxy, "has");
        if (trap == null) {
            return ops.has(proxy.getTarget(), key);
        }
        return JsCoercion.toBoolean(ops.call(trap, proxy.getHandler(), List.of(proxy.getTarget(), key)));
    }

    public boolean delete(JsProxy proxy, JsValue key) {
        final var trap = trapOf(proxy, "deleteProperty");
        if (trap == null) {
            return ops.deleteMember(proxy.getTarget(), key);
        }
        return JsCoercion.toBoolean(ops.call(trap, proxy.getHandler(), List.of(proxy.getTarget(), key)));
    }

    public List<JsValue> ownKeys(JsProxy proxy) {
        final var trap = trapOf(proxy, "ownKeys");
        if (trap == null) {
            return ops.ownKeys(proxy.getTarget());
        }
        final var result = ops.call(trap, proxy.getHandler(), List.of(proxy.getTarget()));
        return result instanceof JsArray array ? new ArrayList<>(array.getElements()) : new ArrayList<>();
    }

    public JsValue apply(JsProxy proxy, JsValue thisArg, List<JsValue> args) {
        final var trap = trapOf(proxy, "apply");
        if (trap == null) {
            return ops.call(proxy.getTarget(), thisArg, args);
        }
        return ops.call(trap, proxy.getHandler(),
                List.of(proxy.getTarget(), thisArg, new JsArray(new ArrayList<>(args))));
    }

    public JsValue construct(JsProxy proxy, List<JsValue> args) {
        final var trap = trapOf(proxy, "construct");
        if (trap == null) {
            return ops.construct(proxy.getTarget(), args);
        }
        return ops.call(trap, proxy.getHandler(),
                List.of(proxy.getTarget(), new JsArray(new ArrayList<>(args)), proxy));
    }

    private JsValue trapOf(JsProxy proxy, String name) {
        final var trap = ops.getMember(proxy.getHandler(), new JsString(name));
        if (isNullish(trap)) {
            return null;
        }
        if (!(trap instanceof JsFunction) && !(trap instanceof JsNativeFunction)) {
            throw new TypeErrorException("Proxy handler's '" + name + "' trap is not a function");
        }
        return trap;
    }
}
